package io.github.pixelsuite;

/** Free-crop hit testing and Doodles handle geometry (pure parts only). */
public final class ScreenshotEditorGeometryTest {
    static int checked;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); checked++; }

    public static void main(String[] args) {
        float touch = 63;   // 24dp at density 2.625

        // A normal crop box behaves like the stock test (20% of its width around each edge).
        check(CropHit.pick(100, 100, 900, 1500, 120, 800, touch) == 0, "left edge");
        check(CropHit.pick(100, 100, 900, 1500, 890, 800, touch) == 2, "right edge");
        check(CropHit.pick(100, 100, 900, 1500, 500, 110, touch) == 1, "top edge");
        check(CropHit.pick(100, 100, 900, 1500, 500, 1490, touch) == 3, "bottom edge");
        check(CropHit.pick(100, 100, 900, 1500, 110, 110, touch) == 4, "top-left");
        check(CropHit.pick(100, 100, 900, 1500, 890, 110, touch) == 5, "top-right");
        check(CropHit.pick(100, 100, 900, 1500, 110, 1490, touch) == 6, "bottom-left");
        check(CropHit.pick(100, 100, 900, 1500, 890, 1490, touch) == 7, "bottom-right");
        check(CropHit.pick(100, 100, 900, 1500, 500, 800, touch) == 8, "inside");
        check(CropHit.pick(100, 100, 900, 1500, 500, 1800, touch) == -1, "outside");

        // A 3 px wide strip: both side edges are still in reach, and the nearer one wins.
        check(CropHit.pick(500, 100, 503, 1500, 470, 800, touch) == 0, "thin strip, left side");
        check(CropHit.pick(500, 100, 503, 1500, 540, 800, touch) == 2, "thin strip, right side");
        // A one-line horizontal strip: the nearer of top and bottom wins.
        check(CropHit.pick(100, 700, 900, 702, 500, 690, touch) == 1, "flat strip, top");
        check(CropHit.pick(100, 700, 900, 702, 500, 712, touch) == 3, "flat strip, bottom");
        // A 2x2 px box: a corner is always grabbed near it, never nothing.
        check(CropHit.pick(500, 500, 502, 502, 520, 520, touch) == 7, "tiny box corner");
        check(CropHit.pick(500, 500, 502, 502, 480, 480, touch) == 4, "tiny box other corner");

        // Doodles geometry.
        check(Math.abs(Doodle.segmentDistance(5, 5, 0, 0, 10, 0) - 5) < 1e-4, "segment distance");
        check(Math.abs(Doodle.segmentDistance(-3, 4, 0, 0, 10, 0) - 5) < 1e-4, "segment distance before start");
        float[] box = {100, 200, 300, 400};
        check(Doodle.handleAt(false, box, 102, 198, 20) == 0, "box top-left handle");
        check(Doodle.handleAt(false, box, 298, 205, 20) == 1, "box top-right handle");
        check(Doodle.handleAt(false, box, 300, 400, 20) == 2, "box bottom-right handle");
        check(Doodle.handleAt(false, box, 95, 405, 20) == 3, "box bottom-left handle");
        check(Doodle.handleAt(false, box, 200, 300, 20) == -1, "no handle in the middle");
        float[] line = {10, 10, 200, 50};
        check(Doodle.handleAt(true, line, 12, 9, 20) == 0, "line start handle");
        check(Doodle.handleAt(true, line, 199, 52, 20) == 1, "line end handle");
        // Corner drags keep the opposite corner.
        float[] moved = Doodle.dragBoxHandle(box, 2, 350, 450);
        check(moved[0] == 100 && moved[1] == 200 && moved[2] == 350 && moved[3] == 450, "bottom-right drag");
        moved = Doodle.dragBoxHandle(box, 0, 50, 60);
        check(moved[0] == 300 && moved[1] == 400 && moved[2] == 50 && moved[3] == 60, "top-left drag");
        // A corner can be dragged past the opposite one (the box flips, no minimum size).
        moved = Doodle.dragBoxHandle(box, 2, 90, 190);
        check(moved[2] < moved[0] && moved[3] < moved[1], "flip past the opposite corner");
        check(Doodle.hits(true, line, 100, 32, 10), "line body hit");
        check(!Doodle.hits(true, line, 100, 80, 10), "line miss");
        check(Doodle.hits(false, box, 200, 300, 0), "box body hit");
        check(!Doodle.hits(false, box, 50, 300, 10), "box miss");
        // Arrow heads stay a sensible share of short and long arrows.
        check(Doodle.headLength(4, 10) <= 4.5f + 1e-4, "short arrow head capped at 45%");
        check(Doodle.headLength(4, 1000) >= 18 * 4.5f / 4.5f, "long arrow head at least 4.5x width");
        Doodle d = new Doodle(Doodle.RECT, 0, 0, 10, 20, 0, 2, false);
        d.scale(2, 5, 10);
        check(d.x1 == -5 && d.y1 == -10 && d.x2 == 15 && d.y2 == 30, "scale about the centre");
        d.offset(5, 10);
        check(d.left() == 0 && d.top() == 0 && d.right() == 20 && d.bottom() == 40, "offset");
        // Switching the kind of a shape that is already there.
        Doodle a = new Doodle(Doodle.ARROW, 0, 0, 100, 80, 0, 2, false);
        a.convertTo(Doodle.OVAL);
        check(a.kind == Doodle.OVAL && a.x1 == 0 && a.y2 == 80, "diagonal line keeps its corners as a circle");
        a.convertTo(Doodle.LINE);
        check(a.isLine() && a.x2 == 100 && a.y2 == 80, "box back to a line keeps its points");
        Doodle flat = new Doodle(Doodle.LINE, 0, 50, 100, 52, 0, 2, false);
        flat.convertTo(Doodle.RECT);
        check(Math.abs(flat.right() - flat.left() - 100) < 0.1f && Math.abs(flat.bottom() - flat.top() - 75) < 0.1f && Math.abs((flat.top() + flat.bottom()) / 2 - 51) < 0.01f, "flat line becomes a centred square-ish box");
        Doodle up = new Doodle(Doodle.DOUBLE_ARROW, 40, 0, 40, 60, 0, 2, false);
        up.convertTo(Doodle.STAR);
        check(up.right() - up.left() == 60 && up.bottom() - up.top() == 60, "upright line becomes a centred box");
        Doodle sq = new Doodle(Doodle.RECT, 0, 0, 30, 30, 0, 2, true);
        sq.convertTo(Doodle.TRIANGLE);
        check(sq.kind == Doodle.TRIANGLE && sq.right() == 30 && sq.fill, "box to box only changes the kind");
        System.out.println("PASS: " + checked + " free-crop and doodle geometry cases");
    }
}
