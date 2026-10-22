package io.github.pixelsuite;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class StatusBarFade {
    private StatusBarFade() {}
    static final String ACTION = "io.github.pixelsuite.STATUS_BAR_FADE";
    private static final List<WeakReference<View>> windows = new ArrayList<WeakReference<View>>();
    private static ValueAnimator running;
    private static float level = 1f;
    private static boolean registered;
    private static Method setTA;

    static void send(Context c, boolean hide) {
        try {
            Intent i = new Intent(ACTION).setPackage("com.android.systemui").putExtra("hide", hide);
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            c.sendBroadcast(i);
        } catch (Throwable error) { LauncherFeatures.logOnce("Status bar fade: request failed", error); }
    }

    static void installSystemUi(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> window = Xp.findClass("com.android.systemui.statusbar.window.StatusBarWindowView", cl);
            n += Xp.hookAllConstructors(window, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        final View v = (View) p.thisObject;
                        synchronized (windows) { windows.add(new WeakReference<View>(v)); }
                        register(v.getContext());
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("Status bar fade unavailable", error); }
        Module.log("Status bar fade: " + n + " hooks");
    }

    private static void register(Context c) {
        if (registered) return;
        registered = true;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { fade(false); return; }
                fade(intent.getBooleanExtra("hide", false));
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            Method m = Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
            m.invoke(app, r, f, 2);
        } catch (Throwable t) {
            try { app.registerReceiver(r, f); } catch (Throwable ignored) { registered = false; }
        }
    }

    private static void fade(final boolean hide) {
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
            float to = hide ? 0f : 1f;
            if (running != null) running.cancel();
            if (Math.abs(level - to) < 0.001f) { apply(to); return; }
            running = ValueAnimator.ofFloat(level, to);
            running.setDuration(hide ? 160 : 220);
            running.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            running.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator a) {
                level = (Float) a.getAnimatedValue();
                apply(level);
            } });
            running.start();
        } });
    }

    private static void apply(float a) {
        synchronized (windows) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                View v = windows.get(i).get();
                if (v == null) { windows.remove(i); continue; }
                try { if (setTA == null) setTA = View.class.getMethod("setTransitionAlpha", float.class); setTA.invoke(v, a); } catch (Throwable ignored) { }
            }
        }
    }
}
