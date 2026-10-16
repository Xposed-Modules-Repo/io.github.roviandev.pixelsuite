package io.github.pixelsuite;

/** Five caption sizes. Level 3 is the requested 15% reduction; icons are never scaled. */
final class IconLabelScale {
    static int level(int value){return Math.max(0,Math.min(4,value));}
    static int percent(int value){return 65+10*level(value);}
    static float factor(int value){return percent(value)/100f;}
    static String name(int value){return new String[]{"Smallest","Small","Medium","Large","Largest"}[level(value)];}
}
