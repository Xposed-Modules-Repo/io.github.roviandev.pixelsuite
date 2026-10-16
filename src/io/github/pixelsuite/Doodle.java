package io.github.pixelsuite;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * One shape of the screenshot editor's Doodles tool, in the editor's IMAGE coordinates (the
 * space its own pen strokes live in), so shapes stay on the picture through zoom, pan and crop.
 *
 * Line shapes (arrow, double arrow, line) run from (x1,y1) to (x2,y2). Box shapes use the same
 * two points as opposite corners. {@code width} is the stroke width in image units.
 */
final class Doodle {
    static final int ARROW = 0, DOUBLE_ARROW = 1, LINE = 2, RECT = 3, OVAL = 4, TRIANGLE = 5, STAR = 6, BUBBLE = 7;
    static final int KINDS = 8;
    static final String[] NAMES = {"Arrow", "Double arrow", "Line", "Square", "Circle", "Triangle", "Star", "Speech bubble"};

    int kind;
    float x1, y1, x2, y2;
    int color;
    float width;
    boolean fill;

    Doodle(int kind, float x1, float y1, float x2, float y2, int color, float width, boolean fill) {
        this.kind = kind; this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        this.color = color; this.width = width; this.fill = fill;
    }

    Doodle copy() { return new Doodle(kind, x1, y1, x2, y2, color, width, fill); }

    static boolean isLine(int kind) { return kind == ARROW || kind == DOUBLE_ARROW || kind == LINE; }
    boolean isLine() { return isLine(kind); }
    boolean fillable() { return !isLine(); }

    float left() { return Math.min(x1, x2); }
    float top() { return Math.min(y1, y2); }
    float right() { return Math.max(x1, x2); }
    float bottom() { return Math.max(y1, y2); }

    void offset(float dx, float dy) { x1 += dx; x2 += dx; y1 += dy; y2 += dy; }

    /**
     * Turns this shape into another kind, keeping its place and size. Line to box: the two points
     * stay the corners, unless the line was (nearly) flat or upright, which would make a flat box,
     * so then the box is centred on the line with the line's length as its width.
     */
    void convertTo(int newKind) {
        boolean wasLine = isLine();
        kind = newKind;
        if (!wasLine || isLine(newKind)) return;
        float dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), longest = Math.max(dx, dy);
        if (longest <= 0) return;
        if (Math.min(dx, dy) >= longest * 0.3f) return;
        float cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, half = (float) Math.hypot(dx, dy) / 2;
        float tall = (newKind == RECT || newKind == BUBBLE) ? half * 0.75f : half;
        x1 = cx - half; x2 = cx + half; y1 = cy - tall; y2 = cy + tall;
    }

    /** Uniform scale about (cx, cy); the stroke keeps its width so it stays as chosen. */
    void scale(float factor, float cx, float cy) {
        x1 = cx + (x1 - cx) * factor; y1 = cy + (y1 - cy) * factor;
        x2 = cx + (x2 - cx) * factor; y2 = cy + (y2 - cy) * factor;
    }

    // ------------------------------------------------------------------ drawing

    void draw(Canvas canvas, Paint paint) {
        paint.setAntiAlias(true);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setPathEffect(null);
        if (isLine()) {
            drawLine(canvas, paint, kind, x1, y1, x2, y2, width);
            return;
        }
        Path path = outline(kind, left(), top(), right(), bottom(), width);
        if (fill) {
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            canvas.drawPath(path, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, paint);
        }
    }

    /** Arrow head length for a stroke of this width on a line of this length. */
    static float headLength(float width, float length) {
        return Math.min(Math.max(width * 4.5f, length * 0.18f), length * 0.45f);
    }

    static void drawLine(Canvas canvas, Paint paint, int kind, float ax, float ay, float bx, float by, float width) {
        float dx = bx - ax, dy = by - ay, length = (float) Math.hypot(dx, dy);
        paint.setStyle(Paint.Style.STROKE);
        if (length < 0.5f) { canvas.drawPoint(ax, ay, paint); return; }
        float ux = dx / length, uy = dy / length;
        float head = kind == LINE ? 0 : headLength(width, length);
        // The shaft stops inside the head so its round cap does not poke through the point.
        float endInset = kind == LINE ? 0 : head * 0.6f;
        float startInset = kind == DOUBLE_ARROW ? head * 0.6f : 0;
        canvas.drawLine(ax + ux * startInset, ay + uy * startInset, bx - ux * endInset, by - uy * endInset, paint);
        if (kind == LINE) return;
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(Math.max(1f, width * 0.35f));
        canvas.drawPath(head(bx, by, ux, uy, head), paint);
        if (kind == DOUBLE_ARROW) canvas.drawPath(head(ax, ay, -ux, -uy, head), paint);
        paint.setStrokeWidth(width);
    }

    /** Filled triangular head with its point at (tx, ty), facing direction (ux, uy). */
    static Path head(float tx, float ty, float ux, float uy, float length) {
        float half = length * 0.55f;
        float bx = tx - ux * length, by = ty - uy * length;
        Path p = new Path();
        p.moveTo(tx, ty);
        p.lineTo(bx - uy * half, by + ux * half);
        p.lineTo(bx + uy * half, by - ux * half);
        p.close();
        return p;
    }

    static Path outline(int kind, float l, float t, float r, float b, float width) {
        Path p = new Path();
        float w = r - l, h = b - t, cx = (l + r) / 2, cy = (t + b) / 2;
        switch (kind) {
            case OVAL:
                p.addOval(new RectF(l, t, r, b), Path.Direction.CW);
                break;
            case TRIANGLE:
                p.moveTo(cx, t); p.lineTo(r, b); p.lineTo(l, b); p.close();
                break;
            case STAR: {
                float[] pts = starPoints(l, t, r, b);
                p.moveTo(pts[0], pts[1]);
                for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
                p.close();
                break;
            }
            case BUBBLE: {
                float body = t + h * 0.78f, radius = Math.min(Math.min(w, h * 0.78f) * 0.25f, Math.max(width * 3, 1));
                p.addRoundRect(new RectF(l, t, r, body), radius, radius, Path.Direction.CW);
                Path tail = new Path();
                tail.moveTo(l + w * 0.22f, body - Math.min(radius, h * 0.05f));
                tail.lineTo(l + w * 0.18f, b);
                tail.lineTo(l + w * 0.42f, body - Math.min(radius, h * 0.05f));
                tail.close();
                p.op(tail, Path.Op.UNION);
                break;
            }
            default: {   // RECT: slightly rounded like the rest of the editor's UI
                float radius = Math.min(Math.min(w, h) * 0.08f, Math.max(width, 1));
                p.addRoundRect(new RectF(l, t, r, b), radius, radius, Path.Direction.CW);
            }
        }
        return p;
    }

    /** Ten points of a five-pointed star filling the box (outer, inner, outer, ...). */
    static float[] starPoints(float l, float t, float r, float b) {
        float cx = (l + r) / 2, rx = (r - l) / 2, ry = (b - t) / 2;
        // Centre a little low so the star's top point and its two lower points touch the box.
        float cy = t + ry * 1.05f;
        float[] pts = new float[20];
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float k = (i % 2 == 0) ? 1f : 0.42f;
            pts[i * 2] = cx + (float) Math.cos(angle) * rx * k;
            pts[i * 2 + 1] = cy + (float) Math.sin(angle) * ry * k * 0.95f;
        }
        return pts;
    }

    // ------------------------------------------------------------------ geometry (pure)

    /** Distance from (px,py) to the segment (ax,ay)-(bx,by). */
    static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        float qx = ax + t * dx, qy = ay + t * dy;
        return (float) Math.hypot(px - qx, py - qy);
    }

    /**
     * Handle index under (px,py) in the given SCREEN coordinates of this shape, or -1.
     * Lines: 0 start, 1 end. Boxes: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
     */
    static int handleAt(boolean line, float[] screen, float px, float py, float radius) {
        float[] pts = handlePoints(line, screen);
        int best = -1; float bestD = radius;
        for (int i = 0; i < pts.length / 2; i++) {
            float d = (float) Math.hypot(px - pts[i * 2], py - pts[i * 2 + 1]);
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** Handle positions from screen corner points {x1,y1,x2,y2}. */
    static float[] handlePoints(boolean line, float[] s) {
        if (line) return new float[]{s[0], s[1], s[2], s[3]};
        float l = Math.min(s[0], s[2]), r = Math.max(s[0], s[2]), t = Math.min(s[1], s[3]), b = Math.max(s[1], s[3]);
        return new float[]{l, t, r, t, r, b, l, b};
    }

    /**
     * Moves handle {@code handle} of a box (screen corners {x1,y1,x2,y2}) to (px,py), keeping the
     * opposite corner fixed. Returns the new {x1,y1,x2,y2} with x1/y1 the fixed corner.
     */
    static float[] dragBoxHandle(float[] s, int handle, float px, float py) {
        float l = Math.min(s[0], s[2]), r = Math.max(s[0], s[2]), t = Math.min(s[1], s[3]), b = Math.max(s[1], s[3]);
        float fx, fy;
        switch (handle) {
            case 0: fx = r; fy = b; break;      // dragging top-left: bottom-right stays
            case 1: fx = l; fy = b; break;      // top-right
            case 2: fx = l; fy = t; break;      // bottom-right
            default: fx = r; fy = t; break;     // bottom-left
        }
        return new float[]{fx, fy, px, py};
    }

    /** True when (px,py) is on the shape in screen space (lines: near the segment; boxes: inside). */
    static boolean hits(boolean line, float[] s, float px, float py, float slop) {
        if (line) return segmentDistance(px, py, s[0], s[1], s[2], s[3]) <= slop;
        float l = Math.min(s[0], s[2]) - slop, r = Math.max(s[0], s[2]) + slop, t = Math.min(s[1], s[3]) - slop, b = Math.max(s[1], s[3]) + slop;
        return px >= l && px <= r && py >= t && py <= b;
    }
}
