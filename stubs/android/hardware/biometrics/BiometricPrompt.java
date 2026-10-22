package android.hardware.biometrics;
import android.content.Context;
import android.content.DialogInterface;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;
/** Public SDK signatures (API 28/30); the installed Android framework supplies this class at runtime. */
public class BiometricPrompt {
    public static class Builder {
        public Builder(Context context) {}
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setSubtitle(CharSequence subtitle) { return this; }
        public Builder setDescription(CharSequence description) { return this; }
        public Builder setAllowedAuthenticators(int authenticators) { return this; }
        public Builder setConfirmationRequired(boolean required) { return this; }
        public Builder setNegativeButton(CharSequence text, Executor executor, DialogInterface.OnClickListener listener) { return this; }
        public BiometricPrompt build() { return null; }
    }
    public static class AuthenticationResult { }
    public abstract static class AuthenticationCallback {
        public void onAuthenticationError(int errorCode, CharSequence errString) {}
        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {}
        public void onAuthenticationSucceeded(AuthenticationResult result) {}
        public void onAuthenticationFailed() {}
    }
    public void authenticate(CancellationSignal cancel, Executor executor, AuthenticationCallback callback) {}
}
