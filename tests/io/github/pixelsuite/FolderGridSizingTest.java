package io.github.pixelsuite;

public final class FolderGridSizingTest {
    public static void main(String[] args) {
        int checked = 0;
        for (int width : new int[]{320,360,393,411,432,480,600}) {
            for (int density : new int[]{1,2,3,4}) {
                int viewport=width*density, chrome=24*density;
                int cell=FolderGridSizing.cellWidth(viewport,chrome,2*density,12*density);
                int icon=FolderGridSizing.iconSize(52*density,cell,4*density);
                int preview30=Math.max(1,Math.min(Math.round(52*density*1.30f),cell-4*density));
                int expected=Math.min(Math.round(preview30*1.15f),cell-4*density);
                if(icon!=expected)throw new AssertionError("Not a bounded 15 percent increase");
                if (5*cell+chrome+8*density+24*density>viewport) throw new AssertionError("Grid overflow");
                if (icon<=0 || icon+4*density>cell) throw new AssertionError("Icon overflow");
                int oldCell=(viewport-40*density-16*density-32*density-16*density)/5;
                int oldIcon=Math.min(Math.round(52*density*1.10f),oldCell-6*density);
                if (width<=432 && icon<oldIcon*1.15f) throw new AssertionError("Increase too small: "+width);
                checked++;
            }
        }
        int widened=0;
        for (int width : new int[]{320,360,393,411,432,480,600}) {
            for (int density : new int[]{1,2,3,4}) {
                int viewport=width*density, chrome=24*density, gap=2*density;
                for (int columns : new int[]{4,5}) {
                    int margin=(columns==5?12:20)*density, min=(columns==5?8:12)*density;
                    int base=FolderGridSizing.cells(viewport,chrome,gap,columns,margin);
                    if(columns==5&&base!=FolderGridSizing.cellWidth(viewport,chrome,gap,margin))throw new AssertionError("cells != cellWidth");
                    for (int needed : new int[]{base-5*density, base, base+density, base+3*density, base+40*density}) {
                        int m=FolderGridSizing.marginFor(viewport,chrome,gap,columns,needed,margin,min);
                        int cell=FolderGridSizing.cells(viewport,chrome,gap,columns,m);
                        if(m>margin||m<min)throw new AssertionError("Margin out of range "+m);
                        if(needed<=base&&m!=margin)throw new AssertionError("Widened a cell that fit");
                        if(cell<base)throw new AssertionError("Cell narrowed");
                        if(m>min&&cell<needed)throw new AssertionError("Margin left but cell still short");
                        if(m<margin&&m+1<=margin&&FolderGridSizing.cells(viewport,chrome,gap,columns,m+1)>=needed)throw new AssertionError("Took more margin than needed");
                        if(columns*cell+(columns-1)*gap+chrome+2*m>viewport)throw new AssertionError("Widened grid overflow");
                        if(m<margin)widened++;
                        checked++;
                    }
                }
            }
        }
        if(widened==0)throw new AssertionError("Widening never exercised");
        if(FolderGridSizing.cellWidth(1,100,4,12)!=1) throw new AssertionError("Degenerate viewport");
        if(FolderGridSizing.iconSize(1,1,4)!=1) throw new AssertionError("Degenerate icon");
        System.out.println("PASS: "+checked+" viewport/density sizing cases and 2 degenerate bounds");
    }
}
