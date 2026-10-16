package io.github.pixelsuite;

import java.text.Collator;
import java.util.Locale;

/**
 * The drawer's strict order, by the name shown on screen (module renames included):
 *   1. English / Latin letters, A to Z
 *   2. Arabic letters
 *   3. Letters of any other script
 *   4. Numbers and special characters (Arabic-Indic digits count as numbers)
 * Within a group: a fixed collator for that script (never the device locale's), numbers
 * compared by value, then exact text, then the app's identity, so equal names never swap.
 * Pure Java: no Android classes, so it is tested on the desktop JVM.
 */
final class DrawerOrder {
    static final int LATIN = 0, ARABIC = 1, OTHER = 2, SYMBOL = 3;
    private static Collator latin, arabic, other;

    private DrawerOrder() {}

    /** Compare two drawer entries by display name, then identity (never 0 for different keys). */
    static synchronized int compare(String nameA, String keyA, String nameB, String keyB) {
        int byName = compareNames(nameA, nameB);
        if (byName != 0) return byName;
        return String.valueOf(keyA).compareTo(String.valueOf(keyB));
    }

    /**
     * The "Add apps" picker's order: English / Latin A to Z first, then names starting with a
     * number or symbol, then other scripts, and Arabic last. Within a group the same rules as
     * the drawer (collator, numbers by value), then the identity, so the order is total.
     */
    static synchronized int comparePicker(String nameA, String keyA, String nameB, String keyB) {
        String a = nameA == null ? "" : nameA, b = nameB == null ? "" : nameB;
        int ra = pickerRank(group(a, start(a))), rb = pickerRank(group(b, start(b)));
        if (ra != rb) return ra < rb ? -1 : 1;
        return compare(nameA, keyA, nameB, keyB);
    }

    static int pickerRank(int group) {
        if (group == LATIN) return 0;
        if (group == SYMBOL) return 1;
        if (group == OTHER) return 2;
        return 3;                                   // ARABIC
    }

    static synchronized int compareNames(String a, String b) {
        a = a == null ? "" : a; b = b == null ? "" : b;
        int ia = start(a), ib = start(b);
        int ga = group(a, ia), gb = group(b, ib);
        if (ga != gb) return ga < gb ? -1 : 1;
        String sa = a.substring(ia), sb = b.substring(ib);
        int r = runs(sa, sb, collator(ga));    // text by the group's collator, numbers by value
        if (r != 0) return r;
        r = natural(sa, sb);
        if (r != 0) return r;
        return a.compareTo(b);                 // exact text, so the order is total
    }

    /** First character that decides the group: skips spaces and invisible direction marks. */
    static int start(String s) {
        int i = 0;
        while (i < s.length()) {
            int c = s.codePointAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && Character.getType(c) != Character.FORMAT) break;
            i += Character.charCount(c);
        }
        return i;
    }

    static int group(String s, int index) {
        if (index >= s.length()) return SYMBOL;
        int c = s.codePointAt(index);
        if (!Character.isLetter(c)) return SYMBOL;   // digits (any script), punctuation, emoji, symbols
        if (isLatin(c)) return LATIN;
        if (isArabic(c)) return ARABIC;
        return OTHER;
    }

    static boolean isLatin(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= 0x00C0 && c <= 0x024F && c != 0x00D7 && c != 0x00F7)   // Latin-1 letters, Extended-A/B
                || (c >= 0x1E00 && c <= 0x1EFF)                                   // Latin Extended Additional
                || (c >= 0xFF21 && c <= 0xFF3A) || (c >= 0xFF41 && c <= 0xFF5A);  // full-width A-Z a-z
    }

    static boolean isArabic(int c) {
        return (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0x08A0 && c <= 0x08FF)
                || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF);
    }

    private static Collator collator(int group) {
        if (group == LATIN) {
            if (latin == null) { latin = Collator.getInstance(Locale.ENGLISH); latin.setStrength(Collator.SECONDARY); }
            return latin;
        }
        if (group == ARABIC) {
            if (arabic == null) { arabic = Collator.getInstance(new Locale("ar")); arabic.setStrength(Collator.SECONDARY); }
            return arabic;
        }
        if (other == null) { other = Collator.getInstance(Locale.ROOT); other.setStrength(Collator.SECONDARY); }
        return other;
    }

    /**
     * Splits both names into runs of digits and runs of other text: text runs compare with the
     * collator, digit runs by numeric value ("App 2" before "App 10", "٢" before "10"), and at the
     * same position a number comes before text.
     */
    static int runs(String a, String b, Collator text) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            boolean da = Character.isDigit(a.codePointAt(i)), db = Character.isDigit(b.codePointAt(j));
            int ei = end(a, i, da), ej = end(b, j, db);
            String ra = a.substring(i, ei), rb = b.substring(j, ej);
            int r;
            if (da && db) r = natural(ra, rb);
            else if (da != db) return da ? -1 : 1;
            else r = text.compare(ra, rb);
            if (r != 0) return r;
            i = ei; j = ej;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int end(String s, int from, boolean digits) {
        int k = from;
        while (k < s.length() && Character.isDigit(s.codePointAt(k)) == digits) k += Character.charCount(s.codePointAt(k));
        return k;
    }

    /**
     * Natural order: runs of digits (ASCII, Arabic-Indic or any other decimal digits) compare by
     * value, everything else by code point, case-insensitively.
     */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            boolean da = Character.isDigit(ca), db = Character.isDigit(cb);
            if (da && db) {
                StringBuilder na = new StringBuilder(), nb = new StringBuilder();
                while (i < a.length() && Character.isDigit(a.codePointAt(i))) { na.append(Character.digit(a.codePointAt(i), 10)); i += Character.charCount(a.codePointAt(i)); }
                while (j < b.length() && Character.isDigit(b.codePointAt(j))) { nb.append(Character.digit(b.codePointAt(j), 10)); j += Character.charCount(b.codePointAt(j)); }
                String xa = strip(na.toString()), xb = strip(nb.toString());
                if (xa.length() != xb.length()) return xa.length() < xb.length() ? -1 : 1;
                int r = xa.compareTo(xb);
                if (r != 0) return r;
                continue;
            }
            if (da != db) return da ? -1 : 1;          // a number sorts before a symbol at the same place
            int la = Character.toLowerCase(ca), lb = Character.toLowerCase(cb);
            if (la != lb) return la < lb ? -1 : 1;
            i += Character.charCount(ca); j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String strip(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }
}
