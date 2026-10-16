package io.github.pixelsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The "Add apps" / "Create folder" app picker, for drawer folders and home screen folders.
 * A search field with a magnifier sits at the top (it replaces the old Rename button), then the
 * apps with their icons: the folder's current apps first, then the rest, each part in the
 * picker order (English A-Z, then numbers and symbols, then other scripts, Arabic last). The
 * order is fixed when the picker opens, so rows don't jump while ticking. Ticks survive
 * searching. Only the folder it was opened for is changed (the caller saves).
 */
final class AppPicker {
    // Only open pickers participate; shape changes refresh visible rows without an idle task.
    private static final java.util.Map<BaseAdapter, Boolean> openAdapters = new java.util.WeakHashMap<BaseAdapter, Boolean>();
    static void refreshIcons() {
        for (BaseAdapter adapter : new ArrayList<BaseAdapter>(openAdapters.keySet())) adapter.notifyDataSetChanged();
    }
    interface Save { boolean save(List<String> chosen); }

    static final class Entry {
        final String key, name, match; final Object app; boolean on; final boolean was;
        Entry(String key, String name, Object app, boolean on) {
            this.key = key; this.name = name; this.app = app; this.on = on; this.was = on;
            match = LauncherFeatures.searchText(name) + "\n" + key.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private AppPicker() {}

    static void show(final Activity a, final String title, List<Object> apps, Set<String> selected, final int minimum, final Save save) {
        final List<Entry> all = new ArrayList<Entry>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (Object app : apps) {
            String key = LauncherFeatures.identity(app);
            if (key == null || !seen.add(key)) continue;
            all.add(new Entry(key, String.valueOf(LauncherFeatures.get(app, "title")), app, selected.contains(key)));
        }
        Collections.sort(all, new Comparator<Entry>() {
            public int compare(Entry x, Entry y) {
                if (x.was != y.was) return x.was ? -1 : 1;            // the folder's apps on top
                return DrawerOrder.comparePicker(x.name, x.key, y.name, y.key);
            }
        });
        final List<Entry> shown = new ArrayList<Entry>(all);
        final boolean dark = FrostedSurface.dark(a);
        final int ink = textColor(a);

        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(a, 4), 0, 0);

        // ---- search field: a pill with a magnifier, a clear button and an accent outline when focused
        final LinearLayout pill = new LinearLayout(a); pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(Gravity.CENTER_VERTICAL);
        final GradientDrawable pillBg = new GradientDrawable(); pillBg.setCornerRadius(dp(a, 26));
        pillBg.setColor(dark ? 0x26ffffff : 0x14000000);
        final int accent = accent(a), idle = dark ? 0x33ffffff : 0x1f000000;
        pillBg.setStroke(dp(a, 1), idle); pill.setBackground(pillBg);
        ImageView lens = new ImageView(a); LauncherGlyph lensGlyph = new LauncherGlyph(a, 11); lensGlyph.setTintList(ColorStateList.valueOf(ink)); lens.setImageDrawable(lensGlyph);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)); lp.leftMargin = dp(a, 16); lp.rightMargin = dp(a, 10);
        pill.addView(lens, lp);
        final EditText field = new EditText(a);
        field.setBackground(null); field.setSingleLine(true); field.setHint("Search apps"); field.setTextSize(16);
        field.setTextColor(ink); field.setHintTextColor((ink & 0xffffff) | 0x8c000000);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI); field.setPadding(0, 0, 0, 0);
        MenuFont.apply(field);
        pill.addView(field, new LinearLayout.LayoutParams(0, -1, 1f));
        final ImageView clear = new ImageView(a); LauncherGlyph clearGlyph = new LauncherGlyph(a, 12); clearGlyph.setTintList(ColorStateList.valueOf(ink)); clear.setImageDrawable(clearGlyph);
        clear.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12)); clear.setContentDescription("Clear search"); clear.setVisibility(View.GONE);
        pill.addView(clear, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(a, 52));
        pp.leftMargin = pp.rightMargin = dp(a, 20); pp.bottomMargin = dp(a, 8);
        root.addView(pill, pp);
        field.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean focused) { pillBg.setStroke(dp(a, focused ? 2 : 1), focused ? accent : idle); }
        });
        pill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { field.requestFocus(); keyboard(field, true); } });

        // ---- the list, with icons and checkboxes
        FrameLayout box = new FrameLayout(a);
        final ListView list = new ListView(a); list.setDivider(null); list.setVerticalScrollBarEnabled(true);
        list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(a, 8));
        final TextView empty = new TextView(a); empty.setGravity(Gravity.CENTER); empty.setTextSize(15); empty.setTextColor((ink & 0xffffff) | 0x99000000);
        box.addView(list, new FrameLayout.LayoutParams(-1, -1)); box.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        int height = Math.min(dp(a, 60) * Math.max(3, all.size()), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.45f));
        root.addView(box, new LinearLayout.LayoutParams(-1, height));

        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup parent) {
                Row row = old instanceof Row ? (Row) old : new Row(a, ink);
                row.bind(shown.get(i)); return row;
            }
        };
        list.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle(title).setView(root)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        final Runnable count = new Runnable() { public void run() {
            int n = 0; for (Entry e : all) if (e.on) n++;
            dialog.setTitle(n == 0 ? title : title + "  ·  " + n + " selected");
        }};
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                Entry e = shown.get(i); e.on = !e.on; ((Row) v).bind(e); count.run();
            }
        });
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) {
                String q = LauncherFeatures.searchText(s.toString().trim());
                clear.setVisibility(q.length() == 0 ? View.GONE : View.VISIBLE);
                shown.clear();
                for (Entry e : all) if (q.length() == 0 || e.match.contains(q)) shown.add(e);
                empty.setText(q.length() == 0 ? "" : "No apps match “" + s.toString().trim() + "”");
                adapter.notifyDataSetChanged(); list.setSelection(0);
            }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, android.view.KeyEvent ev) { keyboard(field, false); return true; }
        });
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { field.setText(""); } });

        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        FrostedSurface.show(dialog);
        openAdapters.put(adapter, Boolean.TRUE);
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) { openAdapters.remove(adapter); }
        });
        count.run();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<String> chosen = new ArrayList<String>();
                for (Entry e : all) if (e.on) chosen.add(e.key);
                if (chosen.size() < minimum) { Toast.makeText(a, "Select at least " + minimum + " apps for a folder", Toast.LENGTH_SHORT).show(); return; }
                if (save.save(chosen)) { keyboard(field, false); dialog.dismiss(); }
                else Toast.makeText(a, "Could not save the folder", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** One row: app icon, name, checkbox. */
    static final class Row extends LinearLayout {
        final ImageView icon; final TextView name; final CheckBox check;
        Row(Context c, int ink) {
            super(c); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(c, 60)); setPadding(dp(c, 24), 0, dp(c, 16), 0);
            TypedValue v = new TypedValue();
            if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, v, true) && v.resourceId != 0) setBackgroundResource(v.resourceId);
            icon = new ImageView(c); addView(icon, new LayoutParams(dp(c, 40), dp(c, 40)));
            name = new TextView(c); name.setTextSize(16); name.setTextColor(ink); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams np = new LayoutParams(0, -2, 1f); np.leftMargin = dp(c, 16); np.rightMargin = dp(c, 8); addView(name, np);
            check = new CheckBox(c); check.setClickable(false); check.setFocusable(false); addView(check, new LayoutParams(-2, -2));
        }
        void bind(Entry e) {
            name.setText(e.name); check.setChecked(e.on); setContentDescription(e.name + (e.on ? ", selected" : ""));
            icon.setImageDrawable(iconOf(e.app, getContext()));
        }
    }

    static android.graphics.drawable.Drawable iconOf(Object app, Context c) {
        try {
            // ItemInfoWithIcon supplies the current ThemeManager shape, disabled state,
            // profile badge and the same native drawing delegate used by launcher cells.
            String key = LauncherFeatures.identity(app);
            if (key != null) LauncherIcon.apply(app, key);
            // Flag 1 is the stock home/all-apps flag: it enables current themed/custom shapes.
            Object drawable = LauncherFeatures.call(app, "newIcon", 1, c);
            if (drawable instanceof android.graphics.drawable.Drawable) return (android.graphics.drawable.Drawable) drawable;
        } catch (Throwable missingItemFactory) { }
        try {
            Object info = LauncherFeatures.get(app, "bitmap");
            Object drawable = LauncherFeatures.call(info, "newIcon", c, 1, null);
            if (drawable instanceof android.graphics.drawable.Drawable) return (android.graphics.drawable.Drawable) drawable;
        } catch (Throwable ignored) { }
        return null;
    }

    static void keyboard(View field, boolean show) {
        InputMethodManager imm = (InputMethodManager) field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(field, 0); else imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    static int textColor(Context c) {
        TypedValue v = new TypedValue();
        try { if (c.getTheme().resolveAttribute(android.R.attr.textColorPrimary, v, true)) return c.getResources().getColorStateList(v.resourceId, c.getTheme()).getDefaultColor(); }
        catch (Throwable ignored) { }
        return FrostedSurface.dark(c) ? 0xffeee9f1 : 0xff202027;
    }

    static int accent(Context c) {
        TypedValue v = new TypedValue();
        try { if (c.getTheme().resolveAttribute(android.R.attr.colorAccent, v, true)) return v.resourceId != 0 ? c.getResources().getColor(v.resourceId, c.getTheme()) : v.data; }
        catch (Throwable ignored) { }
        return FrostedSurface.dark(c) ? 0xffa8c7fa : 0xff0b57d0;
    }

    static int dp(Context c, float v) { return FrostedSurface.dp(c, v); }
}
