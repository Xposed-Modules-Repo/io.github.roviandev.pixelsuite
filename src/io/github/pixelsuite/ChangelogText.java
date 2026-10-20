package io.github.pixelsuite;

final class ChangelogText {
    private ChangelogText() { }
    static CharSequence format(String source) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        int at = 0;
        while (at < source.length()) {
            int open = source.indexOf("**", at);
            if (open < 0) { text.append(source.substring(at)); break; }
            int close = source.indexOf("**", open + 2);
            if (close < 0) { text.append(source.substring(at)); break; }
            text.append(source.substring(at, open));
            int start = text.length();
            text.append(source.substring(open + 2, close));
            if (text.length() > start) text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            at = close + 2;
        }
        return text;
    }
}
