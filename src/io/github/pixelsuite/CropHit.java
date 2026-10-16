package io.github.pixelsuite;

/**
 * Which part of the screenshot editor's crop box a touch grabs (pure, for the free crop).
 * Indices follow the editor's own enum: 0 LEFT, 1 TOP, 2 RIGHT, 3 BOTTOM, 4 TOP_LEFT,
 * 5 TOP_RIGHT, 6 BOTTOM_LEFT, 7 BOTTOM_RIGHT, 8 INSIDE, -1 nothing.
 *
 * Same rule as the stock test (20% of the box's width around each edge, corners first) with
 * two changes for boxes far below the old 100dp minimum: every edge keeps at least
 * {@code touch} px, and when both opposite edges are in reach the nearer one wins, so a
 * one-line strip can still be grown from either side.
 */
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
