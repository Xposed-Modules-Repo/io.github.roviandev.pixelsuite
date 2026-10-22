package io.github.pixelsuite;

public class PrivacyLockMenuActivity extends PrivacyLockActivity {
    @Override protected boolean accepts(String m) {
        if (!PrivacyLock.MODE_LOCK.equals(m) && !PrivacyLock.MODE_UNLOCK.equals(m) && !PrivacyRecents.MODE_RECENTS.equals(m)
                && !PrivacyFolders.MODE_LOCK.equals(m) && !PrivacyFolders.MODE_UNLOCK.equals(m) && !PrivacyFolders.MODE_OPEN.equals(m)
                && !PrivacyGuard.MODE_REMOVE_APP.equals(m) && !PrivacyGuard.MODE_REMOVE_FOLDER.equals(m)) return false;
        try {
            Object from = android.app.Activity.class.getMethod("getLaunchedFromPackage").invoke(this);
            return PrivacyLock.LAUNCHER.equals(from);
        } catch (Throwable older) { return false; }
    }
}
