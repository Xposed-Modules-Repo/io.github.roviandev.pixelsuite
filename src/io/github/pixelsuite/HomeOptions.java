package io.github.pixelsuite;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class HomeOptions {
    static final String LABEL = "Pixel Suite settings";
    static final String PACKAGE = "io.github.roviandev.pixelsuite";
    static final String ACTIVITY = "io.github.pixelsuite.SettingsActivity";

    private HomeOptions() {}

    static volatile float downY = -1;

    static void follow(final View popup) {
        if (popup == null || downY < 0) return;
        boolean first = Xp.getAdditionalInstanceField(popup, "pixelsuite_follow") == null;
        Xp.setAdditionalInstanceField(popup, "pixelsuite_follow", downY);
        if (first) popup.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { place(v); }
        });
        popup.post(new Runnable() { public void run() { place(popup); } });
    }

    private static void place(View v) {
        Object f = Xp.getAdditionalInstanceField(v, "pixelsuite_follow"); if (!(f instanceof Float)) return;
        float touchY = (Float) f;
        int h = v.getHeight(); if (h <= 0) return;
        int[] at = new int[2]; v.getLocationOnScreen(at);
        float baseTop = at[1] - v.getTranslationY();
        int screen = v.getResources().getDisplayMetrics().heightPixels, margin = FrostedSurface.dp(v.getContext(), 16), top = margin, bottom = margin;
        try { android.view.WindowInsets in = (android.view.WindowInsets)LauncherFeatures.call(v, "getRootWindowInsets"); if (in != null) { top += in.getStableInsetTop(); bottom += in.getStableInsetBottom(); } } catch (Throwable ignored) { }
        float want = Math.max(top, Math.min(touchY - h / 2f, screen - bottom - h));
        v.setTranslationY(want - baseTop);
        try { Object arrow = LauncherFeatures.get(v, "mArrow"); if (arrow instanceof View) ((View) arrow).setVisibility(View.INVISIBLE); } catch (Throwable ignored) { }
    }

    static void install() {
        try {
            Xp.hookAllMethods(LauncherFeatures.type("views.BaseDragLayer"), "onInterceptTouchEvent", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length > 0 && p.args[0] instanceof android.view.MotionEvent) {
                        android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) downY = e.getRawY();
                    }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Home menu finger position unavailable", error); }
        try {
            final Class<?> options = LauncherFeatures.type("views.OptionsPopupView");
            Xp.hookAllMethods(LauncherFeatures.type("popup.ArrowPopup"), "onLayout", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { if (options.isInstance(p.thisObject) && Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_follow") == null) follow((View) p.thisObject); }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Home menu placement unavailable", error); }
        Class<?> popup = LauncherFeatures.type("views.OptionsPopupView");
        int hooks = 0;
        for (Method m : popup.getDeclaredMethods()) {
            if (!m.getName().equals("show") || !Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] types = m.getParameterTypes();
            int list = -1;
            for (int i = 0; i < types.length; i++) if (types[i] == List.class || types[i] == ArrayList.class) list = i;
            if (list < 0) continue;
            final int at = list;
            Xp.hookMethod(m, new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    try { p.args[at] = withSettings((List<?>) p.args[at]); }
                    catch (Throwable error) { LauncherFeatures.logOnce("Pixel Suite settings row unavailable", error); }
                }
            });
            hooks++;
        }
        if (hooks == 0) installNexusBuilder();
    }

    static void installNexusBuilder() {
        try {
            Class<?> builder=Xp.findClass("c7.m",LauncherFeatures.loader);
            Method build=builder.getDeclaredMethod("c");
            Xp.hookMethod(build,new Xp.Callback(){protected void afterHookedMethod(Xp.Param p){
                try{appendNativeRow(p.thisObject);}catch(Throwable error){LauncherFeatures.logOnce("Pixel Suite native home row unavailable",error);}
            }});
        }catch(Throwable error){LauncherFeatures.logOnce("Pixel Suite home menu builder unavailable",error);}
    }
    static void appendNativeRow(final Object builder) {
        android.view.ViewGroup popup;
        try{popup=(android.view.ViewGroup)LauncherFeatures.get(builder,"j");}
        catch(Throwable otherMapping){popup=(android.view.ViewGroup)LauncherFeatures.get(builder,"f9310j");}
        if(popup!=null)removeAppsList(popup);
        if(popup!=null)follow(popup);
        if(popup==null||popup.findViewWithTag("pixelsuite_settings")!=null)return;
        Context context=popup.getContext();int homeId=context.getResources().getIdentifier("settings_button_text","string",context.getPackageName());
        if(homeId==0)return;CharSequence home=context.getText(homeId);View sample=null;android.widget.TextView sampleLabel=null;int at=-1;
        for(int i=0;i<popup.getChildCount();i++){
            View child=popup.getChildAt(i);Object label;
            try{label=LauncherFeatures.get(child,"mBubbleText");}catch(Throwable ignored){continue;}
            if(label instanceof android.widget.TextView&&home.toString().contentEquals(((android.widget.TextView)label).getText())){sample=child;sampleLabel=(android.widget.TextView)label;at=i;break;}
        }
        if(sample==null)return;
        int layout=context.getResources().getIdentifier("wallpaper_options_popup_item","layout",context.getPackageName());if(layout==0)return;
        final Object option=newOption();
        LauncherFeatures.set(option,"label",LABEL);LauncherFeatures.set(option,"icon",new LauncherGlyph(context,10));
        try{LauncherFeatures.set(option,"eventId",LauncherFeatures.type("logging.StatsLogManager$LauncherEvent").getField("IGNORE").get(null));}catch(Exception error){throw new IllegalStateException(error);}
        LauncherFeatures.set(option,"clickListener",new View.OnLongClickListener(){public boolean onLongClick(View view){open(view);return true;}});
        View row=android.view.LayoutInflater.from(context).inflate(layout,popup,false);row.setTag("pixelsuite_settings");
        android.widget.TextView text=(android.widget.TextView)LauncherFeatures.get(row,"mBubbleText");text.setText(LABEL);MenuFont.copyInto(text,sampleLabel);
        View icon=(View)LauncherFeatures.get(row,"mIconView");icon.setBackground(new LauncherGlyph(context,10));icon.setBackgroundTintList(text.getTextColors());
        row.setBackgroundTintList(sample.getBackgroundTintList());
        try{Object mode=LauncherFeatures.call(sample,"getBackgroundTintBlendMode");LauncherFeatures.call(row,"setBackgroundTintBlendMode",mode);}catch(Throwable ignored){}
        row.getLayoutParams().width=sample.getLayoutParams().width;
        row.setOnClickListener(new View.OnClickListener(){public void onClick(View view){LauncherFeatures.call(builder,"d",view,option);}});
        row.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View view){return Boolean.TRUE.equals(LauncherFeatures.call(builder,"d",view,option));}});
        popup.addView(row,at+1);
    }
    static CharSequence appsListLabel(Context c){
        try{int id=c.getResources().getIdentifier("all_apps_button_label","string",c.getPackageName());if(id!=0)return c.getText(id);}catch(Throwable ignored){}
        return "Apps list";
    }
    static void removeAppsList(android.view.ViewGroup popup){
        CharSequence apps=appsListLabel(popup.getContext());
        for(int i=popup.getChildCount()-1;i>=0;i--){
            View child=popup.getChildAt(i);Object label;
            try{label=LauncherFeatures.get(child,"mBubbleText");}catch(Throwable ignored){continue;}
            if(!(label instanceof android.widget.TextView))continue;
            CharSequence text=((android.widget.TextView)label).getText();
            if(text==null||!(apps.toString().contentEquals(text)||"Apps list".contentEquals(text)))continue;
            popup.removeViewAt(i);
            try{LauncherFeatures.call(popup,"assignMarginsAndBackgrounds",popup);}catch(Throwable noReshape){}
        }
    }

    static Object newOption(){
        Class<?> type=LauncherFeatures.type("views.OptionsPopupView$OptionItem");
        try{return Xp.newInstance(type);}catch(Throwable constructorRemoved){
            try{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);}
            catch(Exception error){throw new IllegalStateException("Cannot allocate native menu item",error);}
        }
    }

    static List<?> withSettings(List<?> items) {
        Activity a = LauncherFeatures.activity.get();
        if (a == null || items == null || items.isEmpty()) return items;
        boolean homeMenu=false; for(Object item:items)if(item!=null&&isHomeSettings(item,a)){homeMenu=true;break;}
        if(!homeMenu)return items;
        CharSequence apps = appsListLabel(a);
        ArrayList<Object> kept = new ArrayList<Object>();
        for (Object item : items) { CharSequence l = item == null ? "" : label(item); if (!(apps.toString().contentEquals(l) || "Apps list".contentEquals(l))) kept.add(item); }
        if (kept.size() != items.size()) items = kept;
        int home = -1;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item == null) continue;
            if (LABEL.contentEquals(label(item))) return items;
            if (isHomeSettings(item, a)) home = i;
        }
        if (home < 0) return items;
        Object ours = create(items.get(home), a);
        if (ours == null) return items;
        ArrayList<Object> copy = new ArrayList<Object>(items);
        copy.add(home + 1, ours);
        return copy;
    }

    static boolean isHomeSettings(Object item, Context c) {
        Object res = field(item, null, "labelRes", "mLabelRes");
        if (res instanceof Integer && ((Integer) res) != 0) {
            try { if (c.getResources().getResourceEntryName((Integer) res).equals("settings_button_text")) return true; }
            catch (Throwable ignored) { }
        }
        int id = c.getResources().getIdentifier("settings_button_text", "string", c.getPackageName());
        if (id != 0) {
            try { if (c.getString(id).contentEquals(label(item))) return true; } catch (Throwable ignored) { }
        }
        Object event = field(item, null, "eventId", "mEventId");
        return event != null && String.valueOf(event).contains("SETTINGS_BUTTON");
    }

    static CharSequence label(Object item) {
        Object label = field(item, CharSequence.class, "label", "mLabel");
        return label instanceof CharSequence ? (CharSequence) label : "";
    }

    static Object create(Object settings, final Context c) {
        for (Constructor<?> k : settings.getClass().getDeclaredConstructors()) {
            Class<?>[] t = k.getParameterTypes();
            if (t.length != 4 || t[0] != CharSequence.class || t[1] != Drawable.class || t[3] != View.OnLongClickListener.class) continue;
            Object event = field(settings, t[2], "eventId", "mEventId");
            if (event == null) return null;
            try {
                k.setAccessible(true);
                return k.newInstance(LABEL, new LauncherGlyph(c, 10), event, new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { open(v); return true; }
                });
            } catch (Throwable error) { LauncherFeatures.logOnce("Pixel Suite settings row could not be created", error); return null; }
        }
        LauncherFeatures.logOnce("Pixel Suite settings row: no OptionItem(label, icon, event, listener)", null);
        return null;
    }

    static void open(View v) {
        Context c = v.getContext();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(PACKAGE, ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle options = null;
        try { options = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle(); } catch (Throwable ignored) { }
        try { c.startActivity(intent, options); }
        catch (Throwable error) {
            Module.log("Pixel Suite settings could not open", error);
            Toast.makeText(c, "Pixel Suite isn't available", Toast.LENGTH_SHORT).show();
        }
    }

    static Object field(Object o, Class<?> type, String... names) {
        for (String name : names) {
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (Throwable ignored) { }
            }
        }
        if (type == null) return null;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                try { f.setAccessible(true); return f.get(o); } catch (Throwable ignored) { }
            }
        }
        return null;
    }
}
