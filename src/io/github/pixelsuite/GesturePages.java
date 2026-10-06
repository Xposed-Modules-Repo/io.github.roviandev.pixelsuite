package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.CompoundButton;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Settings > System > Gestures: native-looking rows and pages for three gestures, right under
 * "Double press power button" (whose page carries the flashlight option, see Flashlight):
 *
 *   Double press volume down   On / Open Camera
 *   Three-finger screenshot    On
 *   (Tap or Double Tap to check phone, the stock row, moved up here; see DoubleTapWake)
 *   Double tap to sleep        On
 *
 * Each row opens a page with a main switch and a footer, built the way Settings builds its own:
 * the page is hosted by the stock DoubleTapPowerSettings fragment (Settings can't load
 * fragments from a module), opened through Settings' SubSettingLauncher with a page id
 * argument, and its contents replaced. Stock controllers only touch preferences they find by
 * key, so they stay idle.
 *
 * Every switch is a Settings.Secure key (Feature), the same one Pixel Suite's own screen
 * shows, so both always agree. Settings runs as the system uid and writes it directly.
 */
final class GesturePages {

    /** One gesture row + page. */
    private static final class Page {
        final String id, key, title, switchTitle, footer;
        final boolean cameraSummary;

        Page(String id, String key, String title, String switchTitle, String footer,
             boolean cameraSummary) {
            this.id = id;
            this.key = key;
            this.title = title;
            this.switchTitle = switchTitle;
            this.footer = footer;
            this.cameraSummary = cameraSummary;
        }

        String entryKey() { return "pixelsuite_entry_" + id; }
        String switchKey() { return SWITCH_PREFIX + id; }
        String footerKey() { return "pixelsuite_footer_" + id; }
    }

    private static final String SWITCH_PREFIX = "pixelsuite_switch_";

    private static final Page[] PAGES = {
            new Page("voldown", Feature.VOLDOWN_CAMERA,
                    "Double press volume down", "Use double press",
                    "Double press the volume down button while the Always On Display is "
                            + "showing to open the camera. Works as soon as the lock screen "
                            + "fades into the Always On Display.\n\n"
                            + "It won't open after you tap to wake the screen, while media is "
                            + "playing, or during a call.",
                    true),
            new Page("threefinger", Feature.THREE_FINGER,
                    "Three-finger screenshot", "Use three-finger screenshot",
                    "Swipe down anywhere on the screen with three fingers to take a "
                            + "screenshot. What's on screen stays still while you swipe.",
                    false),
            new Page("doubletapsleep", Feature.DOUBLE_TAP_SLEEP,
                    "Double tap to sleep", "Use double tap to sleep",
                    "Double-tap an empty spot on your home screen to turn off the screen "
                            + "and lock your phone.",
                    false),
    };

    private static final String DASHBOARD = "com.android.settings.dashboard.DashboardFragment";
    private static final String DTPS = "com.android.settings.gestures.DoubleTapPowerSettings";
    private static final String STOCK_ENTRY_KEY = "gesture_double_tap_power_input_summary";
    /** Stock "Tap to check phone" row; it goes right above our Double tap to sleep row. */
    private static final String TAP_CHECK_KEY = "gesture_tap_screen_input_summary";
    private static final String TAP_CHECK_FRAGMENT =
            "com.android.settings.gestures.TapScreenGestureSettings";
    private static final String TAP_CHECK_BEFORE = "doubletapsleep";
    private static final String EXTRA_PAGE = "pixelsuite_page";
    private static final String F_PAGE = "pixelsuite.page";
    private static final long PENDING_WINDOW_MS = 5000L;

    private static volatile String sPendingId;
    private static volatile long sPendingAt;
    /** True while we set a switch from the stored value, so the save hook ignores it. */
    private static boolean sSyncing;

    private GesturePages() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> dash = Xp.findClass(DASHBOARD, cl);
            Xp.Callback built = new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    onPageBuilt(param.thisObject);
                }
            };
            if (Xp.hookAllMethods(dash, "refreshAllPreferences", built).isEmpty()) {
                Xp.hookAllMethods(dash, "onCreatePreferences", built);
            }
            Xp.hookAllMethods(dash, "onResume", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    onPageResumed(param.thisObject);
                }
            });

            // Save whenever one of our switches changes, however the tap is delivered.
            Xp.Callback save = new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    if (sSyncing || param.args.length == 0
                            || !(param.args[0] instanceof Boolean)) return;
                    try {
                        Object k = Xp.callMethod(param.thisObject, "getKey");
                        Page page = k == null ? null : pageForSwitchKey(k.toString());
                        if (page != null) {
                            write(context(param.thisObject), page, (Boolean) param.args[0]);
                        }
                    } catch (Throwable ignored) { }
                }
            };
            Xp.hookAllMethods(Xp.findClass("androidx.preference.TwoStatePreference", cl),
                    "setChecked", save);
            try {   // in case MainSwitchPreference overrides setChecked without calling super
                Xp.hookAllMethods(Xp.findClass(
                        "com.android.settingslib.widget.MainSwitchPreference", cl),
                        "setChecked", save);
            } catch (Throwable ignored) { }

            Module.log("gesture pages installed in Settings");
        } catch (Throwable t) {
            Module.log("gesture pages failed", t);
        }
    }

    // ------------------------------------------------------------------ dispatch

    private static void onPageBuilt(Object fragment) {
        try {
            Object screen = Xp.callMethod(fragment, "getPreferenceScreen");
            if (screen == null) return;
            if (isDoubleTapPowerFragment(fragment)) {
                Page page = ourPage(fragment);
                if (page != null) buildPage(fragment, screen, page);
                return;
            }
            Object stock = findStockEntry(screen);
            if (stock != null) addOrSyncEntries(stock);
        } catch (Throwable t) {
            Module.log("gesture pages: build failed", t);
        }
    }

    private static void onPageResumed(Object fragment) {
        try {
            Object screen = Xp.callMethod(fragment, "getPreferenceScreen");
            if (screen == null) return;
            if (isDoubleTapPowerFragment(fragment)) {
                Page page = ourPage(fragment);
                if (page == null) return;
                Object sw = find(screen, page.switchKey());
                if (sw == null) {
                    buildPage(fragment, screen, page);   // rebuilt without our hook
                } else {
                    sync(sw, page);
                    setPageTitle(fragment, page);
                }
                return;
            }
            Object stock = findStockEntry(screen);
            if (stock != null) addOrSyncEntries(stock);
        } catch (Throwable t) {
            Module.log("gesture pages: resume failed", t);
        }
    }

    // ------------------------------------------------------------------ Gestures rows

    private static void addOrSyncEntries(Object stock) {
        Object group = Xp.callMethod(stock, "getParent");
        if (group == null) return;
        Context ctx = context(stock);
        Object visible = Xp.callMethod(stock, "isVisible");

        Object[] ours = new Object[PAGES.length];
        for (int i = 0; i < PAGES.length; i++) {
            Page page = PAGES[i];
            Object row = find(group, page.entryKey());
            if (row == null) {
                row = newPreference(stock.getClass(), "androidx.preference.Preference", ctx);
                Xp.callMethod(row, "setKey", page.entryKey());
                Xp.callMethod(row, "setTitle", (CharSequence) page.title);
                Xp.callMethod(row, "setPersistent", false);
                Xp.callMethod(row, "setLayoutResource", Xp.callMethod(stock, "getLayoutResource"));
                Xp.callMethod(row, "setWidgetLayoutResource",
                        Xp.callMethod(stock, "getWidgetLayoutResource"));
                Xp.callMethod(row, "setIconSpaceReserved",
                        Xp.callMethod(stock, "isIconSpaceReserved"));
                if (!setClickToOpenPage(row, ctx, page)) {
                    Xp.callMethod(row, "setFragment", DTPS);
                    ((Bundle) Xp.callMethod(row, "getExtras")).putString(EXTRA_PAGE, page.id);
                }
                Xp.callMethod(group, "addPreference", row);
            }
            Xp.callMethod(row, "setVisible", visible);
            Xp.callMethod(row, "setSummary", summary(ctx, page));
            ours[i] = row;
        }
        Object tapCheck = findTapCheckEntry(group);
        if (tapCheck != null) {
            Object[] rows = new Object[ours.length + 1];
            int j = 0;
            for (int i = 0; i < PAGES.length; i++) {
                if (TAP_CHECK_BEFORE.equals(PAGES[i].id)) rows[j++] = tapCheck;
                rows[j++] = ours[i];
            }
            if (j == ours.length) rows[j] = tapCheck;   // no Double tap to sleep row: last
            ours = rows;
        }
        placeAfter(group, stock, ours);
    }

    /** The stock tap-to-check-phone row, if it sits in the same list as ours. */
    private static Object findTapCheckEntry(Object group) {
        Object p = find(group, TAP_CHECK_KEY);
        if (p == null) p = findByFragment(group, TAP_CHECK_FRAGMENT);
        if (p == null) {
            int n = (Integer) Xp.callMethod(group, "getPreferenceCount");
            for (int i = 0; i < n && p == null; i++) {
                Object c = Xp.callMethod(group, "getPreference", i);
                Object key = c == null ? null : Xp.callMethod(c, "getKey");
                Object title = c == null ? null : Xp.callMethod(c, "getTitle");
                if (title == null || (key != null && key.toString().startsWith("pixelsuite_"))) {
                    continue;
                }
                String s = title.toString();
                if (DoubleTapWake.OLD_TITLE.equals(s) || DoubleTapWake.NEW_TITLE.equals(s)) p = c;
            }
        }
        return p != null && Xp.callMethod(p, "getParent") == group ? p : null;
    }

    /**
     * Renumbers the group so our rows sit, in order, directly under the stock entry; every
     * other row keeps its relative position. Only orders that actually change are written.
     */
    private static void placeAfter(Object group, Object stock, Object[] ours) {
        List<Object> others = new ArrayList<Object>();
        int n = (Integer) Xp.callMethod(group, "getPreferenceCount");
        outer:
        for (int i = 0; i < n; i++) {
            Object p = Xp.callMethod(group, "getPreference", i);
            for (Object o : ours) if (o == p) continue outer;
            others.add(p);
        }
        Collections.sort(others, new Comparator<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public int compare(Object a, Object b) {
                return ((Comparable<Object>) a).compareTo(b);   // Settings' own row order
            }
        });
        List<Object> seq = new ArrayList<Object>();
        for (Object p : others) {
            seq.add(p);
            if (p == stock) Collections.addAll(seq, ours);
        }
        for (int i = 0; i < seq.size(); i++) {
            Object p = seq.get(i);
            if ((Integer) Xp.callMethod(p, "getOrder") != i) Xp.callMethod(p, "setOrder", i);
        }
    }

    /** "On / Open Camera", "On" or "Off", in Settings' own strings. */
    private static CharSequence summary(Context ctx, Page page) {
        if (!Feature.on(ctx, page.key)) return string(ctx, "gesture_setting_off", "Off");
        String on = string(ctx, "gesture_setting_on", "On");
        if (!page.cameraSummary) return on;
        String action = string(ctx, "double_tap_power_camera_action_summary", "Open Camera");
        try {
            int fmt = ctx.getResources().getIdentifier(
                    "double_tap_power_summary", "string", ctx.getPackageName());
            if (fmt != 0) return ctx.getString(fmt, on, action);
        } catch (Throwable ignored) { }
        return on + " / " + action;
    }

    private static boolean setClickToOpenPage(Object row, final Context ctx, final Page page) {
        try {
            ClassLoader cl = row.getClass().getClassLoader();
            Class<?> iface = cl.loadClass("androidx.preference.Preference$OnPreferenceClickListener");
            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            String name = m.getName();
                            if ("onPreferenceClick".equals(name)) {
                                Context c = ctx;
                                try {
                                    c = context(args[0]);
                                } catch (Throwable ignored) { }
                                openPage(c, page);
                                return Boolean.TRUE;
                            }
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("toString".equals(name)) return "PixelSuiteRowClick";
                            return null;
                        }
                    });
            Xp.callMethod(row, "setOnPreferenceClickListener", listener);
            return true;
        } catch (Throwable t) {
            Module.log("gesture pages: row click unavailable", t);
            return false;
        }
    }

    private static void openPage(Context ctx, Page page) {
        sPendingId = page.id;
        sPendingAt = SystemClock.uptimeMillis();
        Bundle args = new Bundle();
        args.putString(EXTRA_PAGE, page.id);
        try {
            Class<?> launcherCls = ctx.getClassLoader()
                    .loadClass("com.android.settings.core.SubSettingLauncher");
            Object launcher = Xp.newInstance(launcherCls, ctx);
            Xp.callMethod(launcher, "setDestination", DTPS);
            Xp.callMethod(launcher, "setArguments", args);
            Xp.callMethod(launcher, "setTitleText", (CharSequence) page.title);
            Xp.callMethod(launcher, "setSourceMetricsCategory", 0);
            Xp.callMethod(launcher, "launch");
            return;
        } catch (Throwable t) {
            Module.log("gesture pages: SubSettingLauncher failed, using an intent", t);
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(ctx.getPackageName(), "com.android.settings.SubSettings");
            i.putExtra(":settings:show_fragment", DTPS);
            i.putExtra(":settings:show_fragment_args", args);
            i.putExtra(":settings:show_fragment_title", page.title);
            i.putExtra(":settings:source_metrics", 0);
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Module.log("gesture pages: could not open " + page.id, t);
        }
    }

    // ------------------------------------------------------------------ pages

    private static void buildPage(Object fragment, Object screen, Page page) {
        Context ctx = context(screen);
        ClassLoader cl = screen.getClass().getClassLoader();
        Object stockSwitch = findByClassName(screen, "MainSwitchPreference");
        Object stockFooter = findByClassName(screen, "FooterPreference");

        Xp.callMethod(screen, "removeAll");

        final Object sw = newPreference(stockSwitch != null ? stockSwitch.getClass() : null,
                "com.android.settingslib.widget.MainSwitchPreference", ctx);
        Xp.callMethod(sw, "setKey", page.switchKey());
        Xp.callMethod(sw, "setTitle", (CharSequence) page.switchTitle);
        Xp.callMethod(sw, "setPersistent", false);
        sync(sw, page);
        wireSwitch(sw, cl);
        Xp.callMethod(screen, "addPreference", sw);

        try {
            Object footer = newPreference(stockFooter != null ? stockFooter.getClass() : null,
                    "com.android.settingslib.widget.FooterPreference", ctx);
            Xp.callMethod(footer, "setKey", page.footerKey());
            Xp.callMethod(footer, "setTitle", (CharSequence) page.footer);
            Xp.callMethod(screen, "addPreference", footer);
        } catch (Throwable t) {
            Module.log("gesture pages: footer unavailable", t);
        }
        setPageTitle(fragment, page);
    }

    /**
     * A tap on the bar goes through OnPreferenceChangeListener (implemented with a Proxy that
     * always accepts), a tap on the toggle through the switch listener; both end in setChecked,
     * where the save hook writes the setting.
     */
    private static void wireSwitch(final Object sw, ClassLoader cl) {
        try {
            Class<?> iface = cl.loadClass("androidx.preference.Preference$OnPreferenceChangeListener");
            Object accept = Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            String name = m.getName();
                            if ("onPreferenceChange".equals(name)) return Boolean.TRUE;
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("toString".equals(name)) return "PixelSuiteSwitchAccept";
                            return null;
                        }
                    });
            Xp.callMethod(sw, "setOnPreferenceChangeListener", accept);
        } catch (Throwable t) {
            Module.log("gesture pages: change listener unavailable", t);
        }
        try {
            Xp.callMethod(sw, "addOnSwitchChangeListener",
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton button, boolean checked) {
                            try {
                                Object cur = Xp.callMethod(sw, "isChecked");
                                if (!Boolean.valueOf(checked).equals(cur)) {
                                    Xp.callMethod(sw, "setChecked", checked);
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable t) {
            Module.log("gesture pages: switch listener unavailable", t);
        }
    }

    private static void sync(Object sw, Page page) {
        sSyncing = true;
        try {
            Xp.callMethod(sw, "setChecked", Feature.on(context(sw), page.key));
        } finally {
            sSyncing = false;
        }
    }

    private static void write(Context ctx, Page page, boolean on) {
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), page.key, on ? 1 : 0);
            Module.log("gesture pages: " + page.id + " " + (on ? "on" : "off"));
        } catch (Throwable t) {
            Module.log("gesture pages: could not save " + page.id, t);
        }
    }

    private static void setPageTitle(Object fragment, Page page) {
        try {
            Object activity = Xp.callMethod(fragment, "getActivity");
            if (activity instanceof Activity) ((Activity) activity).setTitle(page.title);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ helpers

    private static Page pageForSwitchKey(String key) {
        if (!key.startsWith(SWITCH_PREFIX)) return null;
        return pageById(key.substring(SWITCH_PREFIX.length()));
    }

    private static Page pageById(String id) {
        if (id == null) return null;
        for (Page p : PAGES) if (p.id.equals(id)) return p;
        return null;
    }

    private static boolean isDoubleTapPowerFragment(Object fragment) {
        for (Class<?> c = fragment.getClass(); c != null; c = c.getSuperclass()) {
            if (DTPS.equals(c.getName())) return true;
        }
        return false;
    }

    /** Which of our pages this DoubleTapPowerSettings instance shows, or null for the stock page. */
    private static Page ourPage(Object fragment) {
        Object marked = Xp.getAdditionalInstanceField(fragment, F_PAGE);
        if (marked instanceof String) return pageById((String) marked);
        String id = null;
        try {
            Object args = Xp.callMethod(fragment, "getArguments");
            if (args instanceof Bundle) id = ((Bundle) args).getString(EXTRA_PAGE);
        } catch (Throwable ignored) { }
        if (id == null) {
            long age = SystemClock.uptimeMillis() - sPendingAt;
            if (sPendingId != null && age >= 0 && age <= PENDING_WINDOW_MS) id = sPendingId;
        }
        Page page = pageById(id);
        if (page != null) {
            sPendingId = null;   // one tap opens one page
            Xp.setAdditionalInstanceField(fragment, F_PAGE, page.id);
        }
        return page;
    }

    private static Object findStockEntry(Object screen) {
        Object entry = find(screen, STOCK_ENTRY_KEY);
        return entry != null ? entry : findByFragment(screen, DTPS);
    }

    private static Object newPreference(Class<?> template, String fallbackClass, Context ctx) {
        if (template != null) {
            try {
                return Xp.newInstance(template, ctx);
            } catch (Throwable ignored) { }
        }
        try {
            return Xp.newInstance(ctx.getClassLoader().loadClass(fallbackClass), ctx);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object find(Object group, String key) {
        return Xp.callMethod(group, "findPreference", (CharSequence) key);
    }

    private static boolean isGroup(Object pref) {
        for (Class<?> c = pref.getClass(); c != null; c = c.getSuperclass()) {
            if ("androidx.preference.PreferenceGroup".equals(c.getName())) return true;
        }
        return false;
    }

    private static Object findByFragment(Object group, String fragmentName) {
        int n = (Integer) Xp.callMethod(group, "getPreferenceCount");
        for (int i = 0; i < n; i++) {
            Object p = Xp.callMethod(group, "getPreference", i);
            if (p == null) continue;
            Object key = Xp.callMethod(p, "getKey");
            if (fragmentName.equals(Xp.callMethod(p, "getFragment"))
                    && (key == null || !key.toString().startsWith("pixelsuite_"))) {
                return p;
            }
            if (isGroup(p)) {
                Object hit = findByFragment(p, fragmentName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static Object findByClassName(Object group, String simpleName) {
        int n = (Integer) Xp.callMethod(group, "getPreferenceCount");
        for (int i = 0; i < n; i++) {
            Object p = Xp.callMethod(group, "getPreference", i);
            if (p == null) continue;
            if (p.getClass().getSimpleName().equals(simpleName)) return p;
            if (isGroup(p)) {
                Object hit = findByClassName(p, simpleName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String string(Context ctx, String name, String fallback) {
        try {
            int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
            if (id != 0) return ctx.getString(id);
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static Context context(Object pref) {
        return (Context) Xp.callMethod(pref, "getContext");
    }
}
