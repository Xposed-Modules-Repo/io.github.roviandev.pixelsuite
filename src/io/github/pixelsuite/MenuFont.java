package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;

final class MenuFont {
    private static Typeface typeface;
    private static String variation;
    private static float spacing;
    private static boolean captured;

    private MenuFont() {}

    static void match(ViewGroup popup) {
        TextView sample = null;
        Object rows = null;
        try { rows = LauncherFeatures.get(popup, "systemShortcutContainer"); } catch (Throwable ignored) { }
        if (rows instanceof ViewGroup) sample = stockText((ViewGroup) rows);
        if (sample == null) sample = stockText(popup);
        if (sample == null) return;
        typeface = sample.getTypeface(); variation = variationOf(sample); spacing = sample.getLetterSpacing(); captured = true;
        copyToModuleRows(popup, sample);
    }

    static void apply(TextView text) {
        if (captured) {
            text.setTypeface(typeface);
            setVariation(text, variation);
            text.setLetterSpacing(spacing);
        } else text.setTypeface(medium(text.getContext()));
    }

    static boolean moduleRow(View v) {
        Object tag = v.getTag();
        return tag instanceof String && ((String) tag).startsWith("pixelsuite_");
    }

    static TextView stockText(View v) {
        if (moduleRow(v) || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView) return ((TextView) v).getText().length() > 0 ? (TextView) v : null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { TextView t = stockText(g.getChildAt(i)); if (t != null) return t; }
        }
        return null;
    }

    static void copyToModuleRows(View v, TextView sample) {
        if (moduleRow(v)) { copyInto(v, sample); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) copyToModuleRows(g.getChildAt(i), sample);
        }
    }

    static void copyInto(View v, TextView sample) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTypeface(sample.getTypeface());
            setVariation(t, variationOf(sample));
            t.setLetterSpacing(sample.getLetterSpacing());
            t.setFontFeatureSettings(sample.getFontFeatureSettings());
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.getTextSize());
            t.setTextColor(sample.getTextColors());
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) copyInto(g.getChildAt(i), sample);
        }
    }

    static String variationOf(TextView t) {
        try { Method m = TextView.class.getMethod("getFontVariationSettings"); return (String) m.invoke(t); }
        catch (Throwable ignored) { return null; }
    }

    static void setVariation(TextView t, String settings) {
        try { Method m = TextView.class.getMethod("setFontVariationSettings", String.class); m.invoke(t, settings); }
        catch (Throwable ignored) { }
    }

    static Typeface medium(Context c) {
        Typeface result = null;
        try {
            int id = c.getResources().getIdentifier("config_bodyFontFamilyMedium", "string", "android");
            String family = id == 0 ? null : c.getResources().getString(id);
            if (family != null && family.length() > 0) result = Typeface.create(family, Typeface.NORMAL);
        } catch (Throwable ignored) { }
        if (result == null || result.equals(Typeface.DEFAULT)) result = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return result;
    }
}
