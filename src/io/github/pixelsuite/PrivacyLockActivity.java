package io.github.pixelsuite;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Set;
import java.util.concurrent.Executor;

public class PrivacyLockActivity extends Activity {
    private static final int BIOMETRIC_ANY = 0x00FF, DEVICE_CREDENTIAL = 0x8000;
    private static final int ERROR_CANCELED = 5, ERROR_LOCKOUT = 7, ERROR_LOCKOUT_PERMANENT = 9, ERROR_USER_CANCELED = 10,
            ERROR_NO_BIOMETRICS = 11, ERROR_HW_NOT_PRESENT = 12, ERROR_NEGATIVE_BUTTON = 13, ERROR_HW_UNAVAILABLE = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor onMain = new Executor() { public void execute(Runnable r) { main.post(r); } };
    private String mode, pkg, label;
    private CancellationSignal signal;
    private int failures, generation;
    private String folder;
    private boolean folderMode() { return PrivacyFolders.MODE_LOCK.equals(mode) || PrivacyFolders.MODE_UNLOCK.equals(mode) || PrivacyFolders.MODE_OPEN.equals(mode) || PrivacyGuard.MODE_REMOVE_FOLDER.equals(mode); }
    private boolean finished, started;

    protected boolean accepts(String m) { return PrivacyLock.MODE_OPEN.equals(m) || PrivacyLock.MODE_COVER.equals(m) || PrivacyLock.MODE_CONFIRM.equals(m); }
    private String reason() { String r = getIntent().getStringExtra(PrivacyLock.EXTRA_REASON); return PrivacyLock.REASON_UNINSTALL.equals(r) || PrivacyLock.REASON_DISABLE.equals(r) || PrivacyLock.REASON_PAUSE.equals(r) ? r : null; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        quiet();
        Intent i = getIntent();
        mode = i.getStringExtra(PrivacyLock.EXTRA_MODE);
        pkg = i.getStringExtra(PrivacyLock.EXTRA_PACKAGE);
        if (!accepts(mode)) { done(false); return; }
        if (folderMode()) {
            folder = i.getStringExtra(PrivacyFolders.EXTRA_FOLDER);
            if (!PrivacyFolders.validKey(folder)) { done(false); return; }
            String n = i.getStringExtra(PrivacyFolders.EXTRA_NAME);
            label = n == null || n.trim().isEmpty() ? "Folder" : n.length() > 60 ? n.substring(0, 60) : n;
        } else {
            if (!PrivacyLock.validPackage(pkg)) { done(false); return; }
            label = labelOf(pkg);
        }
        if (PrivacyLock.MODE_COVER.equals(mode)) {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xf2000000));
            try {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.flags |= 0x4;
                android.view.WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(lp, Math.round(60 * getResources().getDisplayMetrics().density));
                getWindow().setAttributes(lp);
            } catch (Throwable ignored) { }
        }
    }

    private void quiet() {
        try {
            android.view.Window w = getWindow();
            w.setStatusBarColor(0); w.setNavigationBarColor(0);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = w.getAttributes(); lp.windowAnimations = 0; w.setAttributes(lp);
            try { android.view.Window.class.getMethod("setDecorFitsSystemWindows", boolean.class).invoke(w, false); } catch (Throwable ignored) { }
            try { android.view.Window.class.getMethod("setNavigationBarContrastEnforced", boolean.class).invoke(w, false); } catch (Throwable ignored) { }
            w.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) { }
        noTransition();
    }
    private void noTransition() {
        try {
            java.lang.reflect.Method m = Activity.class.getMethod("overrideActivityTransition", int.class, int.class, int.class);
            m.invoke(this, 0, 0, 0); m.invoke(this, 1, 0, 0);
        } catch (Throwable older) { }
        overridePendingTransition(0, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (started || finished) return;
        started = true;
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            Toast.makeText(this, "Set a screen lock first to lock apps", Toast.LENGTH_LONG).show();
            if (PrivacyLock.MODE_OPEN.equals(mode)) proceed();
            else if (PrivacyLock.MODE_CONFIRM.equals(mode)) success();
            else done(false);
            return;
        }
        fingerprint();
    }

    private String labelOf(String p) {
        try { PackageManager pm = getPackageManager(); ApplicationInfo ai = pm.getApplicationInfo(p, 0); return String.valueOf(pm.getApplicationLabel(ai)); }
        catch (Throwable t) { return p; }
    }

    private String why() {
        if (PrivacyFolders.MODE_LOCK.equals(mode)) return "Confirm it's you to lock this folder";
        if (PrivacyFolders.MODE_UNLOCK.equals(mode)) return "Confirm it's you to unlock this folder";
        if (PrivacyLock.MODE_LOCK.equals(mode)) return "Confirm it's you to lock this app";
        if (PrivacyLock.MODE_UNLOCK.equals(mode)) return "Confirm it's you to unlock this app";
        if (PrivacyGuard.MODE_REMOVE_APP.equals(mode)) return "Confirm it's you to remove this app";
        if (PrivacyGuard.MODE_REMOVE_FOLDER.equals(mode)) return "Confirm it's you to remove this folder";
        String r = reason();
        if (PrivacyLock.REASON_UNINSTALL.equals(r)) return "Confirm it's you to uninstall this app";
        if (PrivacyLock.REASON_DISABLE.equals(r)) return "Confirm it's you to disable this app";
        if (PrivacyLock.REASON_PAUSE.equals(r)) return "Confirm it's you to pause this app";
        return "Please authenticate to proceed";
    }

    private void fingerprint() {
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this);
            b.setTitle(label);
            b.setDescription(why());
            b.setAllowedAuthenticators(BIOMETRIC_ANY);
            b.setConfirmationRequired(false);
            b.setNegativeButton("Pin", onMain, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { toCredential(); }
            });
            BiometricPrompt prompt = b.build();
            signal = new CancellationSignal();
            failures = 0;
            final int mine = ++generation;
            prompt.authenticate(signal, onMain, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { if (mine == generation) success(); }
                @Override public void onAuthenticationFailed() { if (mine == generation && ++failures >= 3) toCredential(); }
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    if (mine != generation || finished) return;
                    if (code == ERROR_NEGATIVE_BUTTON || code == ERROR_LOCKOUT || code == ERROR_LOCKOUT_PERMANENT
                            || code == ERROR_NO_BIOMETRICS || code == ERROR_HW_NOT_PRESENT || code == ERROR_HW_UNAVAILABLE) { toCredential(); return; }
                    done(false);
                }
            });
        } catch (Throwable error) {
            Module.log("App lock: fingerprint sheet unavailable, screen lock used", error);
            credential();
        }
    }

    private void toCredential() {
        if (finished) return;
        final int next = ++generation;
        try { if (signal != null) signal.cancel(); } catch (Throwable ignored) { }
        main.postDelayed(new Runnable() { public void run() { if (next == generation) credential(); } }, 250);
    }

    private void credential() {
        if (finished) return;
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this);
            b.setTitle(label);
            b.setDescription(why());
            b.setAllowedAuthenticators(DEVICE_CREDENTIAL);
            BiometricPrompt prompt = b.build();
            signal = new CancellationSignal();
            final int mine = generation;
            prompt.authenticate(signal, onMain, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { if (mine == generation) success(); }
                @Override public void onAuthenticationError(int code, CharSequence msg) { if (mine == generation && !finished) done(false); }
            });
        } catch (Throwable error) {
            Module.log("App lock: screen lock entry unavailable", error);
            Toast.makeText(this, "Could not check your screen lock", Toast.LENGTH_SHORT).show();
            done(false);
        }
    }

    private void success() {
        if (finished) return;
        if (PrivacyGuard.MODE_REMOVE_FOLDER.equals(mode)) {
            final String token = getIntent().getStringExtra(PrivacyRecents.EXTRA_TOKEN), key = folder;
            new Thread(new Runnable() { public void run() {
                try {
                    java.util.List<String> out = Root.run("settings get secure " + PrivacyFolders.KEY);
                    Set<String> list = PrivacyLock.parse(out == null || out.isEmpty() ? null : out.get(0));
                    if (list.remove(key)) {
                        String value = PrivacyLock.join(list);
                        Root.run(value.isEmpty() ? "settings delete secure " + PrivacyFolders.KEY : "settings put secure " + PrivacyFolders.KEY + " '" + value + "'");
                    }
                } catch (Throwable error) { Module.log("App lock: folder lock not cleared before deleting", error); }
                main.post(new Runnable() { public void run() {
                    try { sendBroadcast(new Intent(PrivacyGuard.ACTION_OK).setPackage(PrivacyLock.LAUNCHER).putExtra(PrivacyRecents.EXTRA_TOKEN, token)); } catch (Throwable ignored) { }
                    done(true);
                } });
            } }, "PixelSuiteFolderUnlock").start();
            return;
        }
        if (PrivacyGuard.MODE_REMOVE_APP.equals(mode) || PrivacyGuard.MODE_REMOVE_FOLDER.equals(mode)) {
            try { sendBroadcast(new Intent(PrivacyGuard.ACTION_OK).setPackage(PrivacyLock.LAUNCHER).putExtra(PrivacyRecents.EXTRA_TOKEN, getIntent().getStringExtra(PrivacyRecents.EXTRA_TOKEN))); } catch (Throwable ignored) { }
            done(true);
            return;
        }
        if (PrivacyLock.MODE_CONFIRM.equals(mode)) {
            try { sendBroadcast(new Intent(PrivacyLock.ACTION_CONFIRMED).setPackage("android").putExtra(PrivacyLock.EXTRA_TOKEN, getIntent().getStringExtra(PrivacyLock.EXTRA_TOKEN))); } catch (Throwable ignored) { }
            done(true);
            return;
        }
        if (reason() == null && (PrivacyLock.MODE_OPEN.equals(mode) || PrivacyRecents.MODE_RECENTS.equals(mode) || PrivacyLock.MODE_COVER.equals(mode))) {
            try { if (PrivacyLock.validPackage(pkg)) sendBroadcast(new Intent(PrivacyBadges.ACTION_UNLOCKED).setPackage(PrivacyLock.LAUNCHER).putExtra(PrivacyLock.EXTRA_PACKAGE, pkg)); } catch (Throwable ignored) { }
        }
        if (PrivacyLock.MODE_OPEN.equals(mode)) { proceed(); return; }
        if (PrivacyRecents.MODE_RECENTS.equals(mode)) {
            try { sendBroadcast(new Intent(PrivacyLock.ACTION_OPENED).setPackage("android").putExtra(PrivacyLock.EXTRA_PACKAGE, pkg)); } catch (Throwable ignored) { }
            try { sendBroadcast(new Intent(PrivacyRecents.ACTION_RECENTS_OK).setPackage(PrivacyLock.LAUNCHER).putExtra(PrivacyRecents.EXTRA_TOKEN, getIntent().getStringExtra(PrivacyRecents.EXTRA_TOKEN))); } catch (Throwable ignored) { }
            done(true);
            return;
        }
        if (PrivacyLock.MODE_COVER.equals(mode)) {
            try { sendBroadcast(new Intent(PrivacyLock.ACTION_OPENED).setPackage("android").putExtra(PrivacyLock.EXTRA_PACKAGE, pkg)); } catch (Throwable ignored) { }
            done(true);
            return;
        }
        if (PrivacyFolders.MODE_OPEN.equals(mode)) {
            try { sendBroadcast(new Intent(PrivacyFolders.ACTION_OK).setPackage(PrivacyLock.LAUNCHER).putExtra(PrivacyRecents.EXTRA_TOKEN, getIntent().getStringExtra(PrivacyRecents.EXTRA_TOKEN))); } catch (Throwable ignored) { }
            done(true);
            return;
        }
        final boolean lock = PrivacyLock.MODE_LOCK.equals(mode) || PrivacyFolders.MODE_LOCK.equals(mode);
        final boolean isFolder = folderMode();
        final String key = isFolder ? PrivacyFolders.KEY : PrivacyLock.KEY, item = isFolder ? folder : pkg;
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            try {
                java.util.List<String> out = Root.run("settings get secure " + key);
                Set<String> list = PrivacyLock.parse(out == null || out.isEmpty() ? null : out.get(0));
                if (lock) list.add(item); else list.remove(item);
                String value = PrivacyLock.join(list);
                ok = Root.run(value.isEmpty() ? "settings delete secure " + key : "settings put secure " + key + " '" + value + "'") != null;
            } catch (Throwable error) { Module.log("App lock: list not saved", error); }
            final boolean saved = ok;
            main.post(new Runnable() { public void run() {
                Toast.makeText(PrivacyLockActivity.this, !saved ? "Could not save. Is root allowed for Pixel Suite?" : lock ? label + " is locked" : label + " is unlocked", Toast.LENGTH_SHORT).show();
                done(saved);
            } });
        } }, "PixelSuiteAppLockSave").start();
    }

    private void proceed() {
        Object extra = getIntent().getParcelableExtra(Intent.EXTRA_INTENT);
        if (extra instanceof IntentSender) {
            try {
                Bundle options = null;
                try {
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    android.app.ActivityOptions.class.getMethod("setPendingIntentBackgroundActivityStartMode", int.class).invoke(o, 1);
                    options = o.toBundle();
                } catch (Throwable ignored) { }
                int forward = getCallingActivity() != null || (getIntent().getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 ? Intent.FLAG_ACTIVITY_FORWARD_RESULT : 0;
                startIntentSender((IntentSender) extra, null, forward, forward, 0, options);
            } catch (Throwable error) { Module.log("App lock: the app could not be opened after unlocking", error); }
        }
        done(true);
    }

    private void done(boolean ok) {
        if (finished) return;
        finished = true;
        if (!ok && (PrivacyGuard.MODE_REMOVE_FOLDER.equals(mode) || PrivacyGuard.MODE_REMOVE_APP.equals(mode))) {
            try { sendBroadcast(new Intent(PrivacyGuard.ACTION_NO).setPackage(PrivacyLock.LAUNCHER).putExtra(PrivacyRecents.EXTRA_TOKEN, getIntent().getStringExtra(PrivacyRecents.EXTRA_TOKEN))); } catch (Throwable ignored) { }
        }
        if (!ok && PrivacyLock.MODE_CONFIRM.equals(mode)) {
            try { sendBroadcast(new Intent(PrivacyLock.ACTION_CONFIRMED).setPackage("android").putExtra(PrivacyLock.EXTRA_TOKEN, getIntent().getStringExtra(PrivacyLock.EXTRA_TOKEN)).putExtra(PrivacyLock.EXTRA_OK, false)); } catch (Throwable ignored) { }
        }
        generation++;
        try { if (signal != null) signal.cancel(); } catch (Throwable ignored) { }
        if (!ok && PrivacyLock.MODE_COVER.equals(mode)) {
            try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) { }
        }
        if (!PrivacyLock.MODE_OPEN.equals(mode) || !ok) setResult(ok ? RESULT_OK : RESULT_CANCELED);
        finish();
        noTransition();
    }

    @Override public void onBackPressed() { done(false); }
}
