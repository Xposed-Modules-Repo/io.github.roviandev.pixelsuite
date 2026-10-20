package io.github.pixelsuite;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 2.0.4 build 169 (owner's screenshots and follow-up): Google Photos' "Saved" toast at the top of the screen, and
 * "Moved to trash / Undo" (and Photos' other snackbars) kept at the bottom but as small pills; both black under
 * Super Dark-Mode.
 *
 * Two kinds: "Moved to trash / Undo" (and Photos' other messages with an action) are Material snackbars; "Saved" is
 * Photos' own toast bar, a small window. Both found by stable names: the snackbar by Material's class
 * Snackbar$SnackbarLayout and its ids snackbar_text / snackbar_action, the toast by its window's root view
 * (com.google.android.apps.photos.actionabletoast.ToastRootView) and Photos' ids toastbar / toastbar_message /
 * toastbar_button1.
 *
 * Snackbar: as wide as its words and button (side margins set before it is drawn), a pill (Photos' own background
 * drawn inside a pill-shaped clip, or black with the Doodles menu's soft rim under Super Dark), where Photos puts it.
 * Toast: its window goes to the top (Photos' own toast root already supports a top window: it then pads for the
 * status bar) and slides in from above instead of from below: while it is on screen, its slide (translationY) is
 * mirrored. Nothing else in Photos is touched.
 */
final class PhotosToasts {
    private PhotosToasts() {}

    static final String SNACK = "com.google.android.material.snackbar.Snackbar$SnackbarLayout";
    static final String TOAST = "com.google.android.apps.photos.actionabletoast.ToastRootView";

    /** Views whose vertical slide is mirrored (they enter from the top). */
    static final Map<View, Boolean> mirrored = new WeakHashMap<View, Boolean>();
    static Object translationHook;

    static void install(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> snack = Class.forName(SNACK, false, cl);
            Class<?> base = snack.getSuperclass();
            n += Xp.hookAllMethods(base, "onAttachedToWindow", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { attached((View) p.thisObject); }
            }).size();
            n += Xp.hookAllMethods(base, "onLayout", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { seat((View) p.thisObject); }
            }).size();
        } catch (Throwable error) { Module.log("Photos messages: snackbar unavailable", error); }
        try {
            Class<?> wm = Class.forName("android.view.WindowManagerImpl");
            n += Xp.hookAllMethods(wm, "addView", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length < 2 || !(p.args[0] instanceof View) || !(p.args[1] instanceof android.view.WindowManager.LayoutParams)) return;
                    View root = (View) p.args[0];
                    if (!TOAST.equals(root.getClass().getName())) return;
                    android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) p.args[1];
                    if ((lp.gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) != android.view.Gravity.BOTTOM) return;
                    lp.gravity = android.view.Gravity.TOP;   // ToastRootView pads for the status bar only with exactly TOP
                    lp.y = dp(root, 8);
                    toast(root);
                }
            }).size();
        } catch (Throwable error) { Module.log("Photos messages: toast bar unavailable", error); }
        try {
            Xp.hookAllMethods(android.app.Activity.class, "onResume", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { prewarm((android.app.Activity) p.thisObject); }
            });
        } catch (Throwable ignored) { }
        // build 180 (owner: the first message after Photos started still changed width; the resume-time search ran
        // before the bar existed): the bar's own class (Material's FloatingToolbarLayout, the owner's log) is hooked,
        // so its edges are taken the moment it is laid out, before any message.
        try {
            Class<?> ftl = Class.forName("com.google.android.material.floatingtoolbar.FloatingToolbarLayout", false, cl);
            // build 182 (owner's log of 181: nothing laid out): the class does not override onLayout (a plain
            // FrameLayout), so its constructors are hooked and each bar gets a layout-change listener instead.
            int m = Xp.hookAllConstructors(ftl, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    final View v = (View) p.thisObject;
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View x, int l, int t, int r, int b, int ol, int ot, int or, int ob) { barLaidOut(v); }
                    });
                }
            }).size();
            n += m;
        } catch (Throwable missing) { }   // no FloatingToolbarLayout: the tab bar is searched for instead
        Module.log("Photos messages: " + n + " hooks");
    }

    // ------------------------------------------------------------------ snackbar

    static void attached(final View bar) {
        try {
            // build 172: the folders' frost in every mode (blurred grid under it), black + rim under Super Dark
            // build 204 (owner's screenshots, Stock and Blur Off in light theme: "Moved to trash" was Photos' own black
            // pill): messages follow the theme in every style, like the recording pills. Stock / Blur Off get the solid
            // surface (white in light theme, the tinted black in dark), Frosted Glass the frost, Super Dark black.
            if (!(bar.getBackground() instanceof PillFrost)) matchBar(bar, PillFrost.apply(bar, bar, true));
            // build 177 (owner's log: "not found" on the first delete, "matched ... FloatingToolbarLayout" on a later
            // one): right after a delete Photos is still bringing its tab bar back, so it is looked for again a moment
            // later, and remembered from then on.
            final int screenW = bar.getResources().getDisplayMetrics().widthPixels;
            for (int delay : new int[]{400, 900}) bar.postDelayed(new Runnable() { public void run() {
                if (!bar.isAttachedToWindow() || (tabLeft >= 0 && tabScreenW == screenW)) return;
                findTabBar(bar, screenW);
                if (tabLeft >= 0) { tries.remove(bar); bar.invalidate(); }
            } }, delay);
            bar.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (!bar.isAttachedToWindow()) { bar.getViewTreeObserver().removeOnPreDrawListener(this); return true; }
                    // build 178 (owner: the pill jumped when leaving the photo right after a delete): Photos' layout moves
                    // the message with its anchor (the viewer's bottom row) by offsets, without a new layout, so the seat is
                    // also checked before every frame; it only offsets, so it never asks for another layout.
                    seat(bar);
                    return narrow(bar);
                }
            });
        } catch (Throwable error) { Module.log("Photos messages: snackbar not restyled", error); }
    }

    /**
     * Build 174 (owner: the pill must sit in one fixed place whether the photo was trashed from the grid or from the
     * viewer; build 173's margin change in pre-draw made Photos re-lay out every frame: nothing drew, the app seemed
     * frozen): after each of the bar's own layouts, it is offset (no new layout, so no loop) so its bottom is always
     * at one height above the bottom of the screen: the space Photos' floating tab bar takes (its height + its margin,
     * from Photos' own dimens) plus 12dp above the navigation bar. Photos' slide-in (translation) is left alone.
     */
    static void seat(View bar) {
        try {
            if (!(bar.getParent() instanceof View) || bar.getHeight() <= 0) return;
            View root = bar.getRootView();
            android.content.res.Resources r = bar.getResources(); String pkg = bar.getContext().getPackageName();
            int hId = r.getIdentifier("photos_tabbar_floating_tabbar_height", "dimen", pkg), mId = r.getIdentifier("photos_tabbar_floating_tabbar_vertical_margin", "dimen", pkg);
            int tabs = hId == 0 ? dp(bar, 64) : r.getDimensionPixelSize(hId), gap = mId == 0 ? dp(bar, 24) : r.getDimensionPixelSize(mId);
            int nav = 0; android.view.WindowInsets in = root.getRootWindowInsets(); if (in != null) nav = in.getStableInsetBottom();
            int[] rs = new int[2], bs = new int[2]; root.getLocationOnScreen(rs); bar.getLocationOnScreen(bs);
            int wantBottom = rs[1] + root.getHeight() - nav - gap - tabs - dp(bar, 12);
            int isBottom = bs[1] - Math.round(bar.getTranslationY()) + bar.getHeight();
            int by = wantBottom - isBottom;
            if (by != 0) bar.offsetTopAndBottom(by);
        } catch (Throwable ignored) { }
    }

    /**
     * Side margins. Build 176 (owner: the pill exactly as long as the Photos / Collections / Create pill, stacked on
     * it): the pill takes the floating tab bar's left and right edges on screen (remembered, so a message in the photo
     * viewer, which has no tab bar, gets the same width and place). Before the tab bar was ever seen: as wide as its
     * words and button. False (skip this frame) when the margins changed; at most a few tries per message.
     */
    static int tabLeft = -1, tabRight = -1, tabScreenW = -1;
    static final java.util.Map<View, Integer> tries = new java.util.WeakHashMap<View, Integer>();
    static final java.util.Map<View, Boolean> searched = new java.util.WeakHashMap<View, Boolean>();
    static boolean narrow(View bar) {
        if (!(bar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) || !(bar.getParent() instanceof View)) return true;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        View parent = (View) bar.getParent();
        int parentW = parent.getWidth();
        if (parentW <= 0) return true;
        int screenW = bar.getResources().getDisplayMetrics().widthPixels;
        if ((tabScreenW != screenW || tabLeft < 0) && !searched.containsKey(bar)) { searched.put(bar, Boolean.TRUE); findTabBar(bar, screenW); }   // once per message
        int left, right;
        if (tabLeft >= 0 && tabScreenW == screenW) {
            int[] at = new int[2]; parent.getLocationOnScreen(at);
            left = tabLeft - at[0]; right = at[0] + parentW - tabRight;
        } else {
            TextView msg = byName(bar, "snackbar_text"), act = byName(bar, "snackbar_action");
            if (msg == null) return true;
            float text = msg.getPaint().measureText(String.valueOf(msg.getText()));
            float action = act != null && act.getVisibility() == View.VISIBLE ? act.getPaint().measureText(String.valueOf(act.getText())) + dp(bar, 40) : 0;
            int want = (int) Math.min(parentW - dp(bar, 32), text + action + dp(bar, 56));
            left = right = Math.max(dp(bar, 16), (parentW - want) / 2);
        }
        if (left < 0 || right < 0 || left + right > parentW - dp(bar, 100)) return true;
        if (lp.leftMargin == left && lp.rightMargin == right) return true;
        Integer n = tries.get(bar); if (n != null && n >= 4) return true;
        tries.put(bar, n == null ? 1 : n + 1);
        lp.leftMargin = left; lp.rightMargin = right;
        bar.setLayoutParams(lp);
        return false;
    }

    /** Photos' floating tab bar: a shown view 56dp tall (its own dimen), narrower than the screen, near the bottom. */
    static void findTabBar(View bar, int screenW) {
        try {
            android.content.res.Resources r = bar.getResources(); String pkg = bar.getContext().getPackageName();
            int hId = r.getIdentifier("photos_tabbar_floating_tabbar_height", "dimen", pkg);
            int h = hId == 0 ? dp(bar, 56) : r.getDimensionPixelSize(hId);
            View root = bar.getRootView();
            int[] rs = new int[2]; root.getLocationOnScreen(rs);
            View found = scan(root, bar, h, dp(bar, 3), rs[1] + root.getHeight() - dp(bar, 160), screenW - dp(bar, 20), dp(bar, 150));
            if (found == null) return;   // hidden right after a delete: looked for again
            int[] at = new int[2]; found.getLocationOnScreen(at);
            if (tabLeft == at[0] && tabRight == at[0] + found.getWidth() && tabScreenW == screenW) return;
            tabLeft = at[0]; tabRight = at[0] + found.getWidth(); tabScreenW = screenW;
            remember(bar.getContext());
        } catch (Throwable ignored) { }
    }

    /**
     * Build 179 (owner: the pill still jumped when a photo was deleted from inside it): there the tab bar had not been
     * seen yet, so the pill first took the width of its words, then the tab bar's once the bar came back. Now the bar
     * is measured whenever a Photos screen comes to the front (before any message), and its edges are kept in Photos'
     * cache folder (pixelsuite-photos-tabbar: three numbers, screen width and the bar's left and right), so even the
     * first message after Photos starts already has them.
     */
    static void prewarm(final android.app.Activity a) {
        try {
            if (tabLeft < 0) load(a);
            final View decor = a.getWindow().getDecorView();
            for (int delay : new int[]{600, 1500}) decor.postDelayed(new Runnable() { public void run() {
                if (decor.isAttachedToWindow()) findTabBar(decor, decor.getResources().getDisplayMetrics().widthPixels);
            } }, delay);
        } catch (Throwable ignored) { }
    }
    static int tabColour; static boolean tabDark;
    /**
     * build 206 (owner's screenshots, Stock / Blur Off in light theme: the white "Moved to trash" pill on Photos' own
     * #f6f3f4 tab bar): where Photos' bar keeps its stock surface (Stock, Blur Off in light theme), a message is drawn
     * in the bar's own colour, so the two pills are one tone. Restyled styles already share the frost / tint.
     */
    static void matchBar(View bar, PillFrost d) {
        try {
            if (d == null || SurfaceStyle.restyled(bar.getContext()) || tabColour == 0 || tabDark != FrostedSurface.dark(bar.getContext())) return;
            d.fill.setColor(tabColour | 0xff000000); d.invalidateSelf();
        } catch (Throwable ignored) { }
    }
    static void barLaidOut(View v) {
        try {
            int screenW = v.getResources().getDisplayMetrics().widthPixels;
            if (v.getWidth() < dp(v, 150) || v.getWidth() > screenW - dp(v, 20) || Math.abs(v.getHeight() - dp(v, 56)) > dp(v, 3)) return;
            int[] at = new int[2]; v.getLocationOnScreen(at);
            // build 206: its own (stock) colour, read before it may be frosted, for the messages that sit on it
            if (!(v.getBackground() instanceof PillFrost)) { Integer c = ComposeFrost.fillOf(v.getBackground()); if (c != null) { tabColour = c; tabDark = FrostedSurface.dark(v.getContext()); } }
            frostBar(v);
            if (tabLeft == at[0] && tabRight == at[0] + v.getWidth() && tabScreenW == screenW) return;
            tabLeft = at[0]; tabRight = at[0] + v.getWidth(); tabScreenW = screenW;
            remember(v.getContext());
        } catch (Throwable ignored) { }
    }

    /**
     * Build 181 (owner, consistency): the Photos / Collections / Create bar and the search button beside it in the
     * same frost as the pills (the grid under them blurred, under the folder tint; black with the rim under Super
     * Dark). Both are Material FloatingToolbarLayouts (Photos' layout photos_tabbar_floating, decompiled: the bar
     * photos_tabbar_layout, the button search_fab with a round TabBarButton inside); each gets the pill as its
     * background in place of Material's shape, which also takes its shadow away (build 186, owner's screenshot of
     * 185: the shadow showed as a dark ring inside the see-through button). Their own content is left alone.
     */
    static final java.util.Map<View, Boolean> frosted = new java.util.WeakHashMap<View, Boolean>();
    static void frostBar(View bar) {
        try {
            if (!SurfaceStyle.restyled(bar.getContext())) return;   // build 190: Stock (or Blur Off in light theme): Photos' own bar
            if (!frosted.containsKey(bar)) { frosted.put(bar, Boolean.TRUE); PillFrost.frost(bar, true); }
            View fab = byId(bar.getRootView(), "search_fab");
            if (fab == null || frosted.containsKey(fab)) return;
            frosted.put(fab, Boolean.TRUE);
            PillFrost.frost(fab, true);
        } catch (Throwable error) { Module.log("Photos messages: tab bar not frosted", error); }
    }
    static java.io.File cacheFile(android.content.Context c) { return new java.io.File(c.getCacheDir(), "pixelsuite-photos-tabbar"); }
    static void remember(android.content.Context c) {
        try { java.io.FileOutputStream o = new java.io.FileOutputStream(cacheFile(c)); try { o.write((tabScreenW + " " + tabLeft + " " + tabRight).getBytes("UTF-8")); } finally { o.close(); } } catch (Throwable ignored) { }
    }
    static void load(android.content.Context c) {
        try {
            java.io.File f = cacheFile(c); if (!f.isFile()) return;
            byte[] b = new byte[(int) Math.min(64, f.length())]; java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { in.read(b); } finally { in.close(); }
            String[] v = new String(b, "UTF-8").trim().split(" ");
            if (v.length == 3) { tabScreenW = Integer.parseInt(v[0]); tabLeft = Integer.parseInt(v[1]); tabRight = Integer.parseInt(v[2]); }
        } catch (Throwable ignored) { }
    }
    static View scan(View v, View skip, int h, int dh, int minTop, int maxW, int minW) {
        if (v == skip || v.getVisibility() == View.GONE) return null;   // build 179: a bar that is only hidden still has its size
        if (Math.abs(v.getHeight() - h) <= dh && v.getWidth() >= minW && v.getWidth() <= maxW) {
            int[] at = new int[2]; v.getLocationOnScreen(at);
            if (at[1] >= minTop) return v;
        }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { View f = scan(g.getChildAt(i), skip, h, dh, minTop, maxW, minW); if (f != null) return f; } }
        return null;
    }

    // ------------------------------------------------------------------ toast bar ("Saved")

    static void toast(final View root) {
        try {
            View bar = byId(root, "toastbar");
            if (bar != null) mirror(bar, true);
            final View tracked = bar;
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }
                @Override public void onViewDetachedFromWindow(View v) { if (tracked != null) mirror(tracked, false); v.removeOnAttachStateChangeListener(this); }
            });
            if (bar == null) return;   // build 204: every style (see attached)
            matchBar(bar, PillFrost.apply(bar, root, false));   // build 172: its own window, so the app behind it is blurred
        } catch (Throwable error) { Module.log("Photos messages: toast bar not restyled", error); }
    }

    // ------------------------------------------------------------------ shared

    /** While a message is on screen, its slide comes from the top: View.setTranslationY is mirrored for it only. */
    static synchronized void mirror(View v, boolean on) {
        if (on) mirrored.put(v, Boolean.TRUE); else mirrored.remove(v);
        if (!mirrored.isEmpty() && translationHook == null) {
            try {
                translationHook = Xp.hookMethod(View.class.getDeclaredMethod("setTranslationY", float.class), new Xp.Callback() {
                    @Override protected void beforeHookedMethod(Xp.Param p) {
                        if (mirrored.containsKey(p.thisObject) && p.args[0] instanceof Float) p.args[0] = -(Float) p.args[0];
                    }
                });
            } catch (Throwable error) { Module.log("Photos messages: slide from the top unavailable", error); }
        } else if (mirrored.isEmpty() && translationHook != null) {
            try { if (translationHook instanceof io.github.libxposed.api.XposedInterface.HookHandle) ((io.github.libxposed.api.XposedInterface.HookHandle) translationHook).unhook(); } catch (Throwable ignored) { }
            translationHook = null;
        }
    }

    static View byId(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }
    static TextView byName(View root, String name) {
        View v = byId(root, name);
        return v instanceof TextView ? (TextView) v : null;
    }
    static int dp(View v, float d) { return Math.round(d * v.getResources().getDisplayMetrics().density); }

    /** A pill: Photos' own background clipped to it, or plain black (Super Dark). */
    static final class Pill extends Drawable implements Drawable.Callback {
        final Drawable in; final Path clip = new Path(); final android.graphics.Paint black = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Pill(Drawable in) { this.in = in; if (in != null) in.setCallback(this); black.setColor(Color.BLACK); }
        @Override protected void onBoundsChange(Rect b) {
            if (in != null) in.setBounds(b);
            clip.reset(); float r = b.height() / 2f;
            clip.addRoundRect(new RectF(b), r, r, Path.Direction.CW);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds(); if (b.isEmpty()) return;
            float r = b.height() / 2f;
            if (in == null) { c.drawRoundRect(new RectF(b), r, r, black); return; }
            int save = c.save(); c.clipPath(clip); in.draw(c); c.restoreToCount(save);
        }
        @Override public void getOutline(android.graphics.Outline o) { Rect b = getBounds(); o.setRoundRect(b, b.height() / 2f); }
        @Override public boolean isStateful() { return in != null && in.isStateful(); }
        @Override protected boolean onStateChange(int[] s) { return in != null && in.setState(s); }
        @Override public void setAlpha(int a) { if (in != null) in.setAlpha(a); black.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
}
