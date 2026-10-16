package android.view;
/** Compile-only stub; the real class is in the framework. */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel inputChannel, android.os.Looper looper) {
        throw new RuntimeException("stub");
    }
    public void onInputEvent(InputEvent event) { throw new RuntimeException("stub"); }
    public final void finishInputEvent(InputEvent event, boolean handled) {
        throw new RuntimeException("stub");
    }
    public void dispose() { throw new RuntimeException("stub"); }
}
