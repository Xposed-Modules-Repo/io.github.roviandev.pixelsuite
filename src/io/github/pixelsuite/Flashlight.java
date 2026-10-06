package io.github.pixelsuite;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.lang.reflect.Method;

final class Flashlight {

    static final String KEY_TARGET = "double_tap_power_button_gesture";
    static final String KEY_ENABLED = "double_tap_power_button_gesture_enabled";

    private Flashlight() {}

    static boolean isTorchSelected(Context ctx) {
        return Feature.on(ctx, Feature.FLASHLIGHT);
    }

    private static final String GLS = "com.android.server.GestureLauncherService";
    private static final int SOURCE_POWER_DOUBLE_TAP = 1;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    private static final long SEQUENCE_GAP_MS = 500L;

    private static volatile boolean sTorchSelected;
    private static volatile boolean sInitDone;
    private static volatile long sLastPowerDown;
    private static volatile boolean sSequenceStartedInteractive = true;

    static void installSystem(ClassLoader cl) {
        final Class<?> gls;
        try {
            gls = Xp.findClass(GLS, cl);
        } catch (Throwable t) {
            Module.log("GestureLauncherService not found, system side disabled", t);
            return;
        }

        try {
            Xp.hookAllMethods(gls, "interceptPowerKeyDown", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (param.args.length < 2 || !(param.args[0] instanceof KeyEvent)
                            || !(param.args[1] instanceof Boolean)) return;
                    KeyEvent ev = (KeyEvent) param.args[0];
                    if (ev.isLongPress() || ev.getRepeatCount() > 0) return;
                    long t = ev.getEventTime();
                    if (t - sLastPowerDown > SEQUENCE_GAP_MS) {
                        sSequenceStartedInteractive = (Boolean) param.args[1];
                    }
                    sLastPowerDown = t;
                    ensureInit(param.thisObject);
                }
            });

            Xp.hookAllMethods(gls, "handleCameraGesture", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (param.args.length >= 2 && param.args[1] instanceof Integer
                            && (Integer) param.args[1] != SOURCE_POWER_DOUBLE_TAP) return;
                    if (handleIfTorch(param.thisObject)) param.setResult(Boolean.TRUE);
                }
            });

            hookIfPresent(gls, "handleWalletGesture");
            hookIfPresent(gls, "sendGestureTargetActivityPendingIntent");

            Module.log("system hooks installed");
        } catch (Throwable t) {
            Module.log("system hook setup failed", t);
        }
    }

    private static void hookIfPresent(Class<?> gls, String name) {
        try {
            Xp.hookAllMethods(gls, name, new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (handleIfTorch(param.thisObject)) param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean handleIfTorch(Object gls) {
        final Context ctx = context(gls);
        if (ctx == null) return false;
        ensureInit(gls);
        if (!sTorchSelected) return false;

        final boolean sleepAfter = !sSequenceStartedInteractive;
        Torch.toggle(ctx, new Runnable() {
            @Override
            public void run() {
                Haptics.click(ctx);
                if (sleepAfter) {
                    Torch.handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            goToSleep(ctx);
                        }
                    }, 500L);
                }
            }
        });
        return true;
    }

    private static synchronized void ensureInit(Object gls) {
        if (sInitDone) return;
        final Context ctx = context(gls);
        if (ctx == null) return;
        try {
            Torch.init(ctx);
            sTorchSelected = isTorchSelected(ctx);
            Uri uri = Settings.Secure.getUriFor(Feature.FLASHLIGHT);
            ctx.getContentResolver().registerContentObserver(uri, false,
                    new ContentObserver(Torch.handler()) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sTorchSelected = isTorchSelected(ctx);
                            Module.log("flashlight on double press: " + sTorchSelected);
                        }
                    });
            sInitDone = true;
            Module.log("initialised, flashlight on double press: " + sTorchSelected);
        } catch (Throwable t) {
            Module.log("init failed", t);
        }
    }

    private static Context context(Object gls) {
        try {
            Object c = Xp.callMethod(gls, "getContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        try {
            Object c = Xp.getObjectField(gls, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        return null;
    }

    private static void goToSleep(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            try {
                Method m = PowerManager.class.getMethod(
                        "goToSleep", long.class, int.class, int.class);
                m.invoke(pm, SystemClock.uptimeMillis(), SLEEP_REASON_POWER_BUTTON, 0);
            } catch (NoSuchMethodException e) {
                PowerManager.class.getMethod("goToSleep", long.class)
                        .invoke(pm, SystemClock.uptimeMillis());
            }
        } catch (Throwable t) {
            Module.log("goToSleep failed", t);
        }
    }

    private static final String PKG = "com.android.settings.gestures.";
    private static final String CAMERA_CTRL = PKG + "DoubleTapPowerForCameraPreferenceController";
    private static final String WALLET_CTRL = PKG + "DoubleTapPowerForWalletPreferenceController";

    static final String KEY_OURS = "gesture_double_power_tap_flashlight";
    private static final String LABEL = "Toggle flashlight";
    private static final String F_OURS = "powertorch.pref";
    private static final String F_OBSERVER = "powertorch.observer";

    static void installSettings(ClassLoader cl) {
        final Class<?> camera;
        try {
            camera = Xp.findClass(CAMERA_CTRL, cl);
        } catch (Throwable t) {
            Module.log("camera controller not found: this Settings build has no "
                    + "Camera/Wallet choice for double press", t);
            return;
        }

        try {
            Xp.hookAllMethods(camera, "displayPreference", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    addOurPreference(param.thisObject, param.args[0]);
                }
            });

            Xp.hookAllMethods(camera, "updateState", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    uncheckIfTorch(param.args[0]);
                    refresh(param.thisObject);
                }
            });

            Xp.hookAllMethods(camera, "handlePreferenceTreeClick", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    Object pref = param.args[0];
                    String key = key(pref);
                    if (KEY_OURS.equals(key)) {
                        selectTorch(pref);
                        param.setResult(Boolean.TRUE);
                    } else if (key != null && key.equals(controllerKey(param.thisObject))) {
                        deselectTorch(pref);
                    }
                }
            });

            Xp.hookAllMethods(camera, "onStart", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    startObserving(param.thisObject);
                }
            });
            Xp.hookAllMethods(camera, "onStop", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    stopObserving(param.thisObject);
                }
            });
        } catch (Throwable t) {
            Module.log("camera controller hooks failed", t);
            return;
        }

        try {
            Class<?> wallet = Xp.findClass(WALLET_CTRL, cl);
            Xp.hookAllMethods(wallet, "updateState", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    uncheckIfTorch(param.args[0]);
                }
            });
            Xp.hookAllMethods(wallet, "handlePreferenceTreeClick", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    String key = key(param.args[0]);
                    if (key != null && key.equals(controllerKey(param.thisObject))) {
                        deselectTorch(param.args[0]);
                    }
                }
            });
        } catch (Throwable t) {
            Module.log("wallet controller hooks skipped", t);
        }

        installSummaryHooks(cl);
        Module.log("settings hooks installed");
    }

    private static void installSummaryHooks(ClassLoader cl) {
        hookSummary(cl, PKG + "DoubleTapPowerPreferenceController", true);
        hookSummary(cl, PKG + "DoubleTapPowerScreen", false);
    }

    private static void hookSummary(ClassLoader cl, String className, final boolean isController) {
        try {
            Class<?> c = Xp.findClass(className, cl);
            Xp.hookAllMethods(c, "getSummary", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    Object result = param.getResult();
                    if (!(result instanceof CharSequence)) return;
                    Context ctx = null;
                    if (!isController && param.args.length > 0
                            && param.args[0] instanceof Context) {
                        ctx = (Context) param.args[0];
                    } else {
                        try {
                            ctx = (Context) Xp.getObjectField(
                                    param.thisObject, "mContext");
                        } catch (Throwable ignored) { }
                    }
                    if (ctx == null) return;
                    CharSequence fixed = torchSummary(ctx, (CharSequence) result);
                    if (fixed != result) param.setResult(fixed);
                }
            });
        } catch (Throwable t) {
            Module.log("summary hook skipped for " + className + ": " + t);
        }
    }

    private static CharSequence torchSummary(Context ctx, CharSequence original) {
        if (!isTorchSelected(ctx)) return original;
        if (Settings.Secure.getInt(ctx.getContentResolver(), KEY_ENABLED, 1) != 1) {
            return original;
        }
        String s = original.toString();
        String camera = settingsString(ctx, "double_tap_power_camera_action_summary");
        if (camera != null && camera.length() > 0 && s.contains(camera)) {
            return s.replace(camera, LABEL);
        }
        String wallet = settingsString(ctx, "double_tap_power_wallet_action_summary");
        if (wallet != null && wallet.length() > 0 && s.contains(wallet)) {
            return s.replace(wallet, LABEL);
        }
        int i = s.lastIndexOf(" / ");
        return i >= 0 ? s.substring(0, i + 3) + LABEL : original;
    }

    private static String settingsString(Context ctx, String name) {
        try {
            int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
            return id != 0 ? ctx.getString(id) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void notifyTargetChanged(Context ctx) {
        try {
            ctx.getContentResolver().notifyChange(
                    Settings.Secure.getUriFor(KEY_TARGET), null);
        } catch (Throwable ignored) { }
    }

    private static void addOurPreference(Object controller, Object screen) {
        try {
            Object cameraPref = Xp.callMethod(
                    screen, "findPreference", (CharSequence) controllerKey(controller));
            if (cameraPref == null) return;
            Object group = Xp.callMethod(cameraPref, "getParent");
            if (group == null) return;

            Object ours = Xp.callMethod(group, "findPreference", (CharSequence) KEY_OURS);
            if (ours == null) {
                Context ctx = (Context) Xp.callMethod(cameraPref, "getContext");
                ours = Xp.newInstance(cameraPref.getClass(), ctx);
                Xp.callMethod(ours, "setKey", KEY_OURS);
                Xp.callMethod(ours, "setTitle", (CharSequence) LABEL);
                Xp.callMethod(ours, "setPersistent", false);
                Xp.callMethod(group, "addPreference", ours);
            }
            Xp.setAdditionalInstanceField(controller, F_OURS, ours);
            refresh(controller);
        } catch (Throwable t) {
            Module.log("could not add the flashlight option", t);
        }
    }

    private static void selectTorch(Object ours) {
        try {
            Context ctx = prefContext(ours);
            ContentResolver cr = ctx.getContentResolver();
            Settings.Secure.putInt(cr, Feature.FLASHLIGHT, 1);
            Settings.Secure.putInt(cr, KEY_TARGET, 0);
            notifyTargetChanged(ctx);
            Xp.callMethod(ours, "setChecked", true);
            uncheckSiblings(ours);
        } catch (Throwable t) {
            Module.log("selecting flashlight failed", t);
        }
    }

    private static void deselectTorch(Object clickedStockPref) {
        try {
            Context ctx = prefContext(clickedStockPref);
            Settings.Secure.putInt(ctx.getContentResolver(), Feature.FLASHLIGHT, 0);
            notifyTargetChanged(ctx);
            Object ours = sibling(clickedStockPref, KEY_OURS);
            if (ours != null) Xp.callMethod(ours, "setChecked", false);
        } catch (Throwable t) {
            Module.log("deselecting flashlight failed", t);
        }
    }

    private static void refresh(Object controller) {
        try {
            Object ours = Xp.getAdditionalInstanceField(controller, F_OURS);
            if (ours == null) return;
            Context ctx = prefContext(ours);
            boolean torch = isTorchSelected(ctx);
            boolean enabled = Settings.Secure.getInt(
                    ctx.getContentResolver(), KEY_ENABLED, 1) == 1;
            Xp.callMethod(ours, "setEnabled", enabled);
            Xp.callMethod(ours, "setChecked", torch);
            if (torch) uncheckSiblings(ours);
        } catch (Throwable t) {
            Module.log("refresh failed", t);
        }
    }

    private static void uncheckIfTorch(Object stockPref) {
        try {
            if (stockPref != null && isTorchSelected(prefContext(stockPref))) {
                Xp.callMethod(stockPref, "setChecked", false);
            }
        } catch (Throwable ignored) { }
    }

    private static void uncheckSiblings(Object ours) {
        Object group = Xp.callMethod(ours, "getParent");
        if (group == null) return;
        int n = (Integer) Xp.callMethod(group, "getPreferenceCount");
        for (int i = 0; i < n; i++) {
            Object p = Xp.callMethod(group, "getPreference", i);
            if (p != ours && p != null && p.getClass() == ours.getClass()) {
                Xp.callMethod(p, "setChecked", false);
            }
        }
    }

    private static void startObserving(final Object controller) {
        try {
            stopObserving(controller);
            Object ours = Xp.getAdditionalInstanceField(controller, F_OURS);
            if (ours == null) return;
            ContentResolver cr = prefContext(ours).getContentResolver();
            ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh(controller);
                }
            };
            cr.registerContentObserver(Settings.Secure.getUriFor(Feature.FLASHLIGHT), false, obs);
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_ENABLED), false, obs);
            Xp.setAdditionalInstanceField(controller, F_OBSERVER, obs);
            refresh(controller);
        } catch (Throwable t) {
            Module.log("observer registration failed", t);
        }
    }

    private static void stopObserving(Object controller) {
        try {
            Object obs = Xp.getAdditionalInstanceField(controller, F_OBSERVER);
            Object ours = Xp.getAdditionalInstanceField(controller, F_OURS);
            if (obs instanceof ContentObserver && ours != null) {
                prefContext(ours).getContentResolver().unregisterContentObserver((ContentObserver) obs);
            }
            Xp.setAdditionalInstanceField(controller, F_OBSERVER, null);
        } catch (Throwable ignored) { }
    }

    private static Object sibling(Object pref, String key) {
        Object group = Xp.callMethod(pref, "getParent");
        return group == null ? null
                : Xp.callMethod(group, "findPreference", (CharSequence) key);
    }

    private static String controllerKey(Object controller) {
        Object k = Xp.callMethod(controller, "getPreferenceKey");
        return k == null ? null : k.toString();
    }

    private static String key(Object pref) {
        if (pref == null) return null;
        Object k = Xp.callMethod(pref, "getKey");
        return k == null ? null : k.toString();
    }

    private static Context prefContext(Object pref) {
        return (Context) Xp.callMethod(pref, "getContext");
    }
}
