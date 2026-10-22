package io.github.pixelsuite;

final class PrivacyPageGate {
    private boolean resumed, unlocked, prompting;
    private int generation;
    void resume() { resumed = true; }
    void pause() { resumed = false; unlocked = false; }
    void stop() { pause(); prompting = false; generation++; }
    boolean isOpen() { return resumed && unlocked; }
    int begin() {
        if (!resumed || unlocked || prompting) return 0;
        prompting = true;
        return ++generation;
    }
    boolean isPending(int attempt) { return resumed && prompting && attempt == generation; }
    int replace(int attempt) {
        if (!isPending(attempt)) return 0;
        return ++generation;
    }
    boolean success(int attempt) {
        if (!prompting || attempt != generation) return false;
        prompting = false;
        if (!resumed) return false;
        unlocked = true;
        return true;
    }
    boolean error(int attempt) {
        if (!prompting || attempt != generation) return false;
        prompting = false;
        return resumed;
    }
}
