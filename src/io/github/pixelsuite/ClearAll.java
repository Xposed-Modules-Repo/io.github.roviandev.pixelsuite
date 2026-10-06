package io.github.pixelsuite;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;


/**
 * Feature 6: always-visible "Clear all" in Pixel Launcher's recents, and the stock far-end
 * Clear all hidden. Stateless: the button rides the launcher's own Screenshot row, so it
 * never disappears until a force stop. On/off: Settings.Secure Feature.CLEAR_ALL
 * (Pixel Suite restarts Pixel Launcher when you change it).
 */
final class ClearAll {

    private static final String SUB = "clear-all: ";

    private ClearAll() {}


    static final String TAG = "[ClearAllFix] ";
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String RECENTS_VIEW = "com.android.quickstep.views.RecentsView";
    private static final String ACTIONS_VIEW = "com.android.quickstep.views.OverviewActionsView";
    private static final String STOCK_CLEAR_ALL = "com.android.quickstep.views.ClearAllButton";
    private static final String KEY_BUTTON = "clearallfix.button";
    private static final int MAX_INSTALL_TRIES = 5;

    /** Every RecentsView constructed, weakly held. Only a fallback for the click path. */
    private static final Set<View> sRecentsViews =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static Class<?> sRecentsClass;
    private static Method sDismissAllWithView;
    private static Method sDismissAllNoArg;

    static void installLauncher(ClassLoader cl) {
        try {
            sRecentsClass = Xp.findClass(RECENTS_VIEW, cl);
            Class<?> actionsClass = Xp.findClass(ACTIONS_VIEW, cl);

            sDismissAllWithView = Xp.findMethodExactIfExists(
                    sRecentsClass, "dismissAllTasks", View.class);
            if (sDismissAllWithView != null) sDismissAllWithView.setAccessible(true);
            sDismissAllNoArg = Xp.findMethodExactIfExists(
                    sRecentsClass, "dismissAllTasks");
            if (sDismissAllNoArg != null) sDismissAllNoArg.setAccessible(true);

            Xp.hookAllConstructors(sRecentsClass, new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    if (param.thisObject instanceof View) {
                        synchronized (sRecentsViews) {
                            sRecentsViews.add((View) param.thisObject);
                        }
                    }
                }
            });

            // Every OverviewActionsView gets its own button. A launcher re-inflate
            // (theme, wallpaper colours, display size...) simply installs a fresh one.
            Xp.findAndHookMethod(actionsClass, "onFinishInflate", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    // Defer until subclasses have finished their own onFinishInflate.
                    scheduleInstall((ViewGroup) param.thisObject, 0, 0);
                }
            });

            // Separate step: if anything here fails, the new button still works.
            hideStockClearAll(cl);

            Module.log(SUB + "hooks installed (dismissAllTasks(View)="
                    + (sDismissAllWithView != null) + ", ()=" + (sDismissAllNoArg != null) + ")");
        } catch (Throwable t) {
            Module.log(SUB + "hook setup failed");
            Xp.log(t);
        }
    }

    /**
     * Removes the stock Clear all at the far end of the task list.
     *
     * 1. Scrolling: RecentsView already has setDisallowScrollToClearAll(), which the
     *    launcher uses for its own states that have no Clear all button. With it on,
     *    the scroll range ends at the last task and PagedView's snapping
     *    (ensureWithinScrollBounds) never lands on the Clear all page. We force it on.
     * 2. Drawing: ClearAllButton.draw() is skipped, so even a peek of it never shows.
     *    Its own alpha logic already makes it unclickable when it is not fully shown.
     */
    private static void hideStockClearAll(ClassLoader cl) {
        try {
            final Method setDisallow = Xp.findMethodExactIfExists(
                    sRecentsClass, "setDisallowScrollToClearAll", boolean.class);
            if (setDisallow != null) {
                setDisallow.setAccessible(true);
                Xp.findAndHookMethod(sRecentsClass, "setDisallowScrollToClearAll",
                        boolean.class, new Xp.Callback() {
                            @Override
                            protected void beforeHookedMethod(Xp.Param param) {
                                if (!enabled(param.thisObject)) return;
                                param.args[0] = Boolean.TRUE;
                            }
                        });
                // Re-assert whenever Overview is entered, in case a launcher build stops
                // calling setDisallowScrollToClearAll there. Idempotent when already on.
                if (Xp.findMethodExactIfExists(
                        sRecentsClass, "setOverviewStateEnabled", boolean.class) != null) {
                    Xp.findAndHookMethod(sRecentsClass, "setOverviewStateEnabled",
                            boolean.class, new Xp.Callback() {
                                @Override
                                protected void afterHookedMethod(Xp.Param param) {
                                    if (!Boolean.TRUE.equals(param.args[0])) return;
                                    if (!enabled(param.thisObject)) return;
                                    try {
                                        setDisallow.invoke(param.thisObject, Boolean.TRUE);
                                    } catch (Throwable t) {
                                        Xp.log(t);
                                    }
                                }
                            });
                }
            } else {
                Module.log(SUB + "setDisallowScrollToClearAll not found: stock button "
                        + "hidden, but its page stays scrollable on this build");
            }
        } catch (Throwable t) {
            Module.log(SUB + "could not block scrolling to stock Clear all");
            Xp.log(t);
        }

        try {
            Class<?> stock = Xp.findClass(STOCK_CLEAR_ALL, cl);
            Xp.findAndHookMethod(stock, "draw", Canvas.class, new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (!enabled(param.thisObject)) return;
                    param.setResult(null);   // never drawn
                }
            });
            Xp.hookAllConstructors(stock, new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    if (!enabled(param.thisObject)) return;
                    View v = (View) param.thisObject;
                    v.setImportantForAccessibility(
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    v.setFocusable(false);
                }
            });
            Module.log(SUB + "stock Clear all hidden");
        } catch (Throwable t) {
            Module.log(SUB + "could not hide stock Clear all");
            Xp.log(t);
        }
    }

    private static final Feature.Cached sOn = new Feature.Cached(Feature.CLEAR_ALL);

    /** Feature switch, read through any launcher View's context (cached: draw() is per frame). */
    private static boolean enabled(Object view) {
        try {
            return sOn.on(((View) view).getContext());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void scheduleInstall(final ViewGroup host, final int attempt, long delayMs) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!install(host) && attempt + 1 < MAX_INSTALL_TRIES) {
                    scheduleInstall(host, attempt + 1, 400L);
                }
            }
        };
        if (delayMs <= 0) host.post(r); else host.postDelayed(r, delayMs);
    }

    /** @return true when installed (or already present), false to retry later. */
    private static boolean install(final ViewGroup host) {
        try {
            if (!Feature.on(host.getContext(), Feature.CLEAR_ALL)) return true;
            Object existing = Xp.getAdditionalInstanceField(host, KEY_BUTTON);
            if (existing instanceof View) return true;

            final LinearLayout row = actionRow(host);
            if (row == null) {
                Module.log(SUB + "action_buttons row not found yet");
                return false;
            }
            final TextView stock = styleSource(row, host);
            if (stock == null) {
                Module.log(SUB + "no stock button to copy the look from yet");
                return false;
            }

            final Button mine = new Button(stock.getContext(), null, 0);
            mine.setId(View.generateViewId());
            String label = label(host);
            mine.setText(label);
            mine.setContentDescription(label);
            Look.apply(stock, mine);

            mine.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearAll(host, v);
                }
            });

            addToRow(row, mine, stock, host);
            Xp.setAdditionalInstanceField(host, KEY_BUTTON, mine);

            // Self-heal: if the launcher ever rebuilds the row's children or restyles the
            // stock button in place, put ours back / re-copy the look. Layout callbacks
            // only fire on real layout passes, never per frame.
            row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    row.post(new Runnable() {
                        @Override
                        public void run() {
                            heal(host, row, mine);
                        }
                    });
                }
            });

            Module.log(SUB + "button installed in " + host.getClass().getName());
            return true;
        } catch (Throwable t) {
            Module.log(SUB + "install failed");
            Xp.log(t);
            return false;
        }
    }

    private static void heal(ViewGroup host, LinearLayout row, Button mine) {
        try {
            TextView stock = styleSource(row, host);
            if (stock == null) return;
            if (mine.getParent() == null) {
                addToRow(row, mine, stock, host);
                Module.log(SUB + "button re-attached to row");
            }
            Look.applyIfChanged(stock, mine);
        } catch (Throwable t) {
            Xp.log(t);
        }
    }

    private static void addToRow(LinearLayout row, Button mine, TextView stock, ViewGroup host) {
        LinearLayout.LayoutParams lp;
        ViewGroup.LayoutParams src = stock.getLayoutParams();
        if (src instanceof LinearLayout.LayoutParams) {
            lp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src);
        } else {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.weight = 0f;
        lp.setMarginStart(spacing(host, row));

        // Right after the last button, so a trailing spacer (if a launcher build has one)
        // stays last. action_buttons is wrap_content + centred, so everything re-centres.
        int insertAt = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof TextView && c != mine) insertAt = i + 1;
        }
        row.addView(mine, insertAt, lp);
    }

    private static LinearLayout actionRow(ViewGroup host) {
        int id = id(host, "action_buttons", "id");
        View v = id != 0 ? host.findViewById(id) : null;
        return v instanceof LinearLayout ? (LinearLayout) v : null;
    }

    /** Prefer the Screenshot button; otherwise the first real button in the row. */
    private static TextView styleSource(LinearLayout row, ViewGroup host) {
        int sid = id(host, "action_screenshot", "id");
        if (sid != 0) {
            View v = row.findViewById(sid);
            if (v instanceof TextView) return (TextView) v;
        }
        TextView fallback = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            if (Xp.getAdditionalInstanceField(host, KEY_BUTTON) == c) continue;
            if (c.getVisibility() == View.VISIBLE) return (TextView) c;
            if (fallback == null) fallback = (TextView) c;
        }
        return fallback;
    }

    private static int spacing(ViewGroup host, LinearLayout row) {
        int dimen = id(host, "overview_actions_button_spacing", "dimen");
        if (dimen != 0) {
            try {
                return host.getResources().getDimensionPixelSize(dimen);
            } catch (Throwable ignored) { }
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup.LayoutParams p = row.getChildAt(i).getLayoutParams();
            if (p instanceof ViewGroup.MarginLayoutParams) {
                int m = ((ViewGroup.MarginLayoutParams) p).getMarginStart();
                if (m > 0) return m;
            }
        }
        return Math.round(8 * host.getResources().getDisplayMetrics().density);
    }

    private static String label(ViewGroup host) {
        int s = id(host, "recents_clear_all", "string");
        if (s != 0) {
            try {
                return host.getResources().getString(s);
            } catch (Throwable ignored) { }
        }
        return "Clear all";
    }

    private static int id(View v, String name, String type) {
        Resources res = v.getResources();
        return res.getIdentifier(name, type, v.getContext().getPackageName());
    }

    // ---------------------------------------------------------------- click path

    private static void clearAll(ViewGroup host, View button) {
        View rv = findRecentsView(host);
        if (rv == null) {
            Module.log(SUB + "click: no attached RecentsView found");
            return;
        }
        try {
            if (sDismissAllWithView != null) {
                sDismissAllWithView.invoke(rv, button);
                return;
            }
            if (sDismissAllNoArg != null) {
                sDismissAllNoArg.invoke(rv);
                return;
            }
        } catch (Throwable t) {
            Module.log(SUB + "dismissAllTasks failed, falling back to stock button");
            Xp.log(t);
        }
        int cid = id(host, "clear_all", "id");
        View stockClear = cid != 0 ? rv.findViewById(cid) : null;
        if (stockClear != null) {
            stockClear.performClick();
        } else {
            Module.log(SUB + "click: no way to clear tasks on this launcher build");
        }
    }

    /**
     * Resolved fresh on every tap from the button's own window, so it can never point
     * at a RecentsView from an earlier launcher instance.
     */
    private static View findRecentsView(ViewGroup host) {
        Class<?> cls = sRecentsClass;
        if (cls == null) return null;

        View attachedOnly = null;
        ArrayDeque<View> stack = new ArrayDeque<View>();
        stack.push(host.getRootView());
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (cls.isInstance(v)) {
                if (v.isAttachedToWindow()) {
                    if (v.isShown()) return v;
                    if (attachedOnly == null) attachedOnly = v;
                }
                continue;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) stack.push(g.getChildAt(i));
            }
        }
        if (attachedOnly != null) return attachedOnly;

        // Overview hosted in a different window than the actions bar: use the registry.
        ArrayList<View> all;
        synchronized (sRecentsViews) {
            all = new ArrayList<View>(sRecentsViews);
        }
        for (View v : all) {
            if (v != null && v.isAttachedToWindow() && v.isShown()) return v;
        }
        for (View v : all) {
            if (v != null && v.isAttachedToWindow()) return v;
        }
        return null;
    }

    // ---------------------------------------------------------------- styling

    static final class Look {
        private static final String KEY_SIG = "clearallfix.sig";

        static void applyIfChanged(TextView stock, TextView mine) {
            String sig = signature(stock);
            Object old = Xp.getAdditionalInstanceField(mine, KEY_SIG);
            if (!sig.equals(old)) apply(stock, mine);
        }

        static void apply(TextView stock, TextView mine) {
            Drawable bg = stock.getBackground();
            if (bg != null && bg.getConstantState() != null) {
                mine.setBackground(bg.getConstantState()
                        .newDrawable(stock.getResources(), stock.getContext().getTheme())
                        .mutate());
            }
            mine.setBackgroundTintList(stock.getBackgroundTintList());
            if (stock.getStateListAnimator() != null) {
                mine.setStateListAnimator(stock.getStateListAnimator().clone());
            } else {
                mine.setStateListAnimator(null);
            }
            mine.setElevation(stock.getElevation());

            mine.setTextColor(stock.getTextColors());
            mine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, stock.getTextSize());
            mine.setTypeface(stock.getTypeface());
            mine.setLetterSpacing(stock.getLetterSpacing());
            mine.setTransformationMethod(stock.getTransformationMethod());
            mine.setIncludeFontPadding(stock.getIncludeFontPadding());
            mine.setGravity(stock.getGravity());
            if (stock.getMaxLines() > 0 && stock.getMaxLines() < Integer.MAX_VALUE) {
                mine.setMaxLines(stock.getMaxLines());
            }
            mine.setEllipsize(stock.getEllipsize());

            mine.setPaddingRelative(stock.getPaddingStart(), stock.getPaddingTop(),
                    stock.getPaddingEnd(), stock.getPaddingBottom());
            mine.setMinWidth(stock.getMinWidth());
            mine.setMinHeight(stock.getMinHeight());
            mine.setMinimumWidth(stock.getMinimumWidth());
            mine.setMinimumHeight(stock.getMinimumHeight());

            // Leading icon sized like the stock one (Screenshot has one).
            Drawable start = stock.getCompoundDrawablesRelative()[0];
            if (start != null) {
                int w = start.getBounds().width() > 0 ? start.getBounds().width()
                        : start.getIntrinsicWidth();
                int h = start.getBounds().height() > 0 ? start.getBounds().height()
                        : start.getIntrinsicHeight();
                ColorStateList tint = stock.getCompoundDrawableTintList();
                CrossDrawable x = new CrossDrawable(tint != null ? tint : stock.getTextColors());
                x.setBounds(0, 0, Math.max(w, 1), Math.max(h, 1));
                mine.setCompoundDrawablesRelative(x, null, null, null);
                mine.setCompoundDrawablePadding(stock.getCompoundDrawablePadding());
            } else {
                mine.setCompoundDrawablesRelative(null, null, null, null);
            }

            Xp.setAdditionalInstanceField(mine, KEY_SIG, signature(stock));
        }

        private static String signature(TextView s) {
            Drawable bg = s.getBackground();
            Drawable start = s.getCompoundDrawablesRelative()[0];
            return s.getTextSize() + "|" + s.getTextColors().getDefaultColor() + "|"
                    + s.getPaddingStart() + "," + s.getPaddingTop() + ","
                    + s.getPaddingEnd() + "," + s.getPaddingBottom() + "|"
                    + (bg == null ? 0 : System.identityHashCode(bg.getConstantState())) + "|"
                    + (start == null ? 0 : start.getBounds().width()) + "|"
                    + System.identityHashCode(s.getTypeface());
        }
    }
}
