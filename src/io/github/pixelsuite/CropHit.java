package io.github.pixelsuite;

final class CropHit {
    private CropHit() {}

    static int pick(float l, float t, float r, float b, float x, float y, float touch) {
        float tol = Math.max(0.2f * (r - l), touch);
        float dl = Math.abs(x - l), dr = Math.abs(x - r), dt = Math.abs(y - t), db = Math.abs(y - b);
        boolean nl = dl <= tol, nr = dr <= tol, nt = dt <= tol, nb = db <= tol;
        if (nl && nr) { if (dl <= dr) nr = false; else nl = false; }
        if (nt && nb) { if (dt <= db) nb = false; else nt = false; }
        if (nl && nt) return 4;
        if (nr && nt) return 5;
        if (nl && nb) return 6;
        if (nr && nb) return 7;
        if (nl) return 0;
        if (nr) return 2;
        if (nt) return 1;
        if (nb) return 3;
        if (x >= l && x <= r && y >= t && y <= b) return 8;
        return -1;
    }
}
