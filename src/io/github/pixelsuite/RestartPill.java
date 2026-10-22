package io.github.pixelsuite;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A small floating pill in Pixel Suite's own screen that offers to restart the app a changed
 * setting lives in (Pixel Launcher, Files). It sits in the bottom corner over every page,
 * follows the user through scrolling and between pages (the pending list is shared), and is
 * never automatic: restarting the launcher closes whatever the user has open there, so the user
 * picks the moment. Settings that apply live never show it.
 */
final class RestartPill {
    static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    static final String FILES = "com.google.android.apps.nbu.files";
    /** Apps waiting for a restart, in the order their settings changed: package -> label. */
    private static final Map<String, String> pending = new LinkedHashMap<String, String>();
    private static boolean wasShown;
    /** One restart at a time, in order; the thread ends after 10 s idle instead of living on. */
    private static final ExecutorService IO = new ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    private static final PathInterpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);

    private final Activity activity;
    private final LinearLayout pill;
    private final TextView label;
    private final RestartStatusIcon status;
    private final ImageView icon;
    private final int accent, onAccent;
    private TextView notice;
    private final Runnable clearNotice=new Runnable(){public void run(){dismissNotice();}};
    private void restoreRestart(){if(pill.getVisibility()==View.INVISIBLE)pill.setVisibility(View.VISIBLE);refresh();}
    private void stopNoticeAnimation(){notice.animate().cancel();}
    private void dismissNotice(){
        stopNoticeAnimation();
        if(!status.motion()||!notice.hasWindowFocus()){notice.setVisibility(View.GONE);restoreRestart();return;}
        notice.animate().translationY(dp(activity.getResources().getDisplayMetrics().density,24)).scaleX(.9f).scaleY(.9f).setDuration(220).setInterpolator(EASE)
                .withEndAction(new Runnable(){public void run(){notice.setVisibility(View.GONE);notice.setTranslationY(0);notice.setScaleX(1);notice.setScaleY(1);restoreRestart();}}).start();
    }
    private boolean busy;
    private boolean showingDone;
    private final Runnable finishDone = new Runnable() { public void run() {
        showingDone=false;
        synchronized(pending){if(pending.isEmpty()){hide(true);return;}}
        refresh();
    }};
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Which app must restart for a setting to take full effect, or null when it applies live (or the app is already
     * restarted by the save itself, like Clear all and the screenshot editor).
     */
    static String scopeOf(String key) {
        if (key == null) return null;
        if (key.equals(LauncherFeatures.GRID) || key.equals(LauncherFeatures.GRID_SIZE) || key.equals(LauncherFeatures.DRAWER)
                || key.equals(LauncherFeatures.DRAWER_GRID_SIZE) || key.equals(LauncherFeatures.ICONS)
                || key.equals(LauncherFeatures.HIDE_SHORTCUTS) || key.equals(LauncherFeatures.LABEL_SIZE)
                || key.equals(LauncherFeatures.LABEL_LEVEL) || key.equals(LauncherFeatures.RENAME)
                || key.equals(SuperDark.KEY) || key.equals(SurfaceStyle.KEY)) return LAUNCHER;   // Super Dark: folder colours and the drawer scrim are set when the launcher starts
        if (key.equals(Feature.FILES_SORT)) return FILES;
        return null;
    }

    /** A setting was saved: remember that its app wants a restart. */
    static void changed(String key) {
        String pkg = scopeOf(key); if (pkg == null) return;
        synchronized (pending) { pending.put(pkg, LAUNCHER.equals(pkg) ? "Pixel Launcher" : "Files"); }
    }

    RestartPill(Activity a, FrameLayout root, int accent, int onAccent) {
        activity = a;
        boolean dark = FrostedSurface.dark(a);
        this.accent = dark ? LauncherFeatures.surface(a,"system_surface_container_high_dark",0xff2b2930) : accent;
        this.onAccent = dark ? 0xfff5f5f7 : onAccent;
        float d = a.getResources().getDisplayMetrics().density;
        pill = new LinearLayout(a); pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(d, 14), 0, dp(d, 18), 0); pill.setMinimumHeight(dp(d, 48));
        pill.setBackground(AppSurfaces.pill(a,outlinedBackground(this.accent,dark,d)));
        pill.setElevation(dp(d, 6));
        status = new RestartStatusIcon(a,this.onAccent);
        icon = new ImageView(a){
            @Override protected void onWindowVisibilityChanged(int v){super.onWindowVisibilityChanged(v);status.setActive(v==View.VISIBLE&&hasWindowFocus());}
            @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);status.setActive(focus&&getWindowVisibility()==View.VISIBLE);}
            @Override protected void onDetachedFromWindow(){status.setActive(false);super.onDetachedFromWindow();}
        };icon.setImageDrawable(status);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20)); ip.rightMargin = dp(d, 10);
        pill.addView(icon, ip);
        label = new TextView(a); label.setTextColor(this.onAccent); label.setTextSize(14); label.setSingleLine(true);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        pill.addView(label);
        pill.setClickable(true); pill.setFocusable(true);
        pill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { restart(); } });
        // Long-press: not now (the setting still applies after the next restart of that app).
        pill.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { synchronized (pending) { pending.clear(); } hide(true); return true; } });
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        final int margin = dp(d, 16); lp.setMargins(margin, margin, margin, margin);
        root.addView(pill, lp);
        notice=new TextView(a);notice.setTextColor(this.onAccent);notice.setTextSize(13);notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(d,18),dp(d,12),dp(d,18),dp(d,12));notice.setMinimumHeight(dp(d,48));
        notice.setMaxLines(2);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setBackground(AppSurfaces.pill(a,outlinedBackground(this.accent,dark,d)));notice.setElevation(dp(d,6));
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);np.setMargins(dp(d,24),dp(d,16),dp(d,24),dp(d,16));root.addView(notice,np);notice.setVisibility(View.GONE);
        notice.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener(){public WindowInsets onApplyWindowInsets(View v,WindowInsets in){np.bottomMargin=dp(d,16)+in.getSystemWindowInsetBottom();np.leftMargin=dp(d,24)+in.getSystemWindowInsetLeft();np.rightMargin=dp(d,24)+in.getSystemWindowInsetRight();v.setLayoutParams(np);return in;}});
        // Above the gesture bar, whatever the insets.
        pill.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                lp.bottomMargin = margin + insets.getSystemWindowInsetBottom(); lp.leftMargin=margin+insets.getSystemWindowInsetLeft(); lp.rightMargin = margin + insets.getSystemWindowInsetRight();
                v.setLayoutParams(lp); return insets;
            }
        });
        pill.setVisibility(View.GONE);
    }

    void showRestriction(){
        refreshPalette();
        if(activity.isFinishing()||activity.isDestroyed())return;
        main.removeCallbacks(clearNotice);
        android.text.SpannableString message=new android.text.SpannableString("Super Dark-Mode uses its own black style.\nTurn it off to change styles.");
        message.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,"Super Dark-Mode".length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);notice.setText(message);
        if(pill.getVisibility()==View.VISIBLE)pill.setVisibility(View.INVISIBLE);
        boolean already=notice.getVisibility()==View.VISIBLE;
        stopNoticeAnimation();notice.setVisibility(View.VISIBLE);notice.requestApplyInsets();notice.setScaleX(1);notice.setScaleY(1);notice.setTranslationY(0);
        if(!already&&status.motion()){
            notice.setTranslationY(dp(activity.getResources().getDisplayMetrics().density,24));notice.setScaleX(.9f);notice.setScaleY(.9f);
            notice.animate().translationY(0).scaleX(1).scaleY(1).setDuration(320).setInterpolator(EASE).start();
        }
        main.postDelayed(clearNotice,5000);
    }
    void dispose(){stopNoticeAnimation();main.removeCallbacks(clearNotice);main.removeCallbacks(finishDone);status.setActive(false);}

    /** Shows or hides the pill for the current pending list (call from onResume and after each save). */
    void refreshPalette(){int color=AppSurfaces.active(activity)?0xffffffff:onAccent;label.setTextColor(color);notice.setTextColor(color);status.setColor(color);pill.invalidate();notice.invalidate();}

    void refresh() {
        refreshPalette();
        if (busy) return;
        if(showingDone)return;
        String text;
        synchronized (pending) {
            if (pending.isEmpty()) { hide(false); return; }
            text = pending.size() == 1 ? "Restart " + pending.values().iterator().next() : "Restart " + pending.size() + " apps";
        }
        main.removeCallbacks(finishDone);
        pill.animate().cancel();label.animate().cancel();label.setAlpha(1f);label.setTranslationY(0);
        ViewGroup.LayoutParams width=pill.getLayoutParams();width.width=ViewGroup.LayoutParams.WRAP_CONTENT;pill.setLayoutParams(width);
        LinearLayout.LayoutParams textSize=(LinearLayout.LayoutParams)label.getLayoutParams();textSize.width=ViewGroup.LayoutParams.WRAP_CONTENT;textSize.weight=0;label.setLayoutParams(textSize);
        label.setText(text);status.show(0);
        pill.setContentDescription(text + ". Applies your changes. Long-press to hide.");
        if(notice.getVisibility()==View.VISIBLE){pill.setVisibility(View.INVISIBLE);return;}
        if (pill.getVisibility() == View.VISIBLE) return;
        pill.setVisibility(View.VISIBLE);
        if (wasShown) { pill.setAlpha(1f); pill.setTranslationY(0f); pill.setScaleX(1f); pill.setScaleY(1f); return; }   // already there on the previous page
        wasShown = true;
        pill.setAlpha(1f); pill.setTranslationY(dp(activity.getResources().getDisplayMetrics().density, 24)); pill.setScaleX(.9f); pill.setScaleY(.9f);
        pill.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(EASE).start();
    }

    private void hide(boolean animate) {
        wasShown = false;
        main.removeCallbacks(finishDone);
        if (pill.getVisibility() != View.VISIBLE) return;
        if (!animate) { pill.setVisibility(View.GONE);status.setActive(false); return; }
        pill.animate().translationY(dp(activity.getResources().getDisplayMetrics().density, 24)).scaleX(.9f).scaleY(.9f).setDuration(220).setInterpolator(EASE)
                .withEndAction(new Runnable() { public void run() { pill.setVisibility(View.GONE);status.setActive(false); } }).start();
    }

    private void restart() {
        if (busy) return;
        final String command; synchronized (pending) {
            if (pending.isEmpty()) return;
            StringBuilder b = new StringBuilder();
            for (String pkg : pending.keySet()) { if (b.length() > 0) b.append("; "); b.append("am force-stop ").append(pkg); }
            command = b.toString();
        }
        busy = true;showingDone=false;main.removeCallbacks(finishDone);
        // Lock the pill's dimensions for the whole sequence; only its content animates.
        ViewGroup.LayoutParams width=pill.getLayoutParams();width.width=pill.getWidth();pill.setLayoutParams(width);
        LinearLayout.LayoutParams textSize=(LinearLayout.LayoutParams)label.getLayoutParams();textSize.width=0;textSize.weight=1;label.setLayoutParams(textSize);label.setGravity(Gravity.CENTER);
        status.setActive(pill.hasWindowFocus());status.show(1);changeLabel("Restarting…");
        final long started=android.os.SystemClock.uptimeMillis();
        IO.execute(new Runnable() { public void run() {
            final boolean ok = Root.run(command) != null;
            main.postDelayed(new Runnable() { public void run() {
                busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!ok) { refresh(); Toast.makeText(activity, "Couldn't restart: root access needed", Toast.LENGTH_SHORT).show(); return; }
                synchronized (pending) { pending.clear(); }
                showingDone=true;status.show(2);changeLabel("Done");
                main.postDelayed(finishDone,1100);
            }},Math.max(0,650-(android.os.SystemClock.uptimeMillis()-started)));
        }});
    }

    private void changeLabel(final String text){
        pill.setContentDescription(text);
        label.animate().cancel();
        if(!status.motion()){label.setText(text);label.setAlpha(1);label.setTranslationY(0);return;}
        label.animate().alpha(0).translationY(-3*activity.getResources().getDisplayMetrics().density).setDuration(100).setInterpolator(EASE).withEndAction(new Runnable(){public void run(){
            label.setText(text);label.setTranslationY(3*activity.getResources().getDisplayMetrics().density);
            label.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(EASE).start();
        }}).start();
    }

    private static GradientDrawable outlinedBackground(int color,boolean dark,float density){
        GradientDrawable background=new GradientDrawable();background.setColor(color|0xff000000);background.setAlpha(255);
        background.setCornerRadius(dp(density,24));background.setStroke(dp(density,1),dark?0xff55545d:0xff8c8995);return background;
    }

    private static int dp(float density, float v) { return Math.round(v * density); }
}
