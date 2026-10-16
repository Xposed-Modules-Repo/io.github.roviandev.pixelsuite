package io.github.pixelsuite;

final class FolderGridSizing {
    private FolderGridSizing() {}
    static int cellWidth(int viewport, int chrome, int gap, int margin) {
        return Math.max(1, (viewport - 2 * margin - chrome - 4 * gap) / 5);
    }
    static int cells(int viewport, int chrome, int gap, int columns, int margin) {
        return Math.max(1, (viewport - 2 * margin - chrome - (columns - 1) * gap) / columns);
    }
    static int marginFor(int viewport, int chrome, int gap, int columns, int needed, int margin, int minMargin) {
        if (cells(viewport, chrome, gap, columns, margin) >= needed) return margin;
        int m = (viewport - chrome - (columns - 1) * gap - columns * needed) / 2;
        return Math.max(minMargin, Math.min(margin, m));
    }
    static int iconSize(int nativeSize, int cellWidth, int breathingRoom) {
        int previous=Math.max(1,Math.min(Math.round(nativeSize*1.30f),cellWidth-breathingRoom));
        return Math.max(1, Math.min(Math.round(previous*1.15f), cellWidth - breathingRoom));
    }
}
