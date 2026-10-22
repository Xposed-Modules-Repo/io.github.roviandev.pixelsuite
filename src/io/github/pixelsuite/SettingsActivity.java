package io.github.pixelsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Module-only feature pages. Setting writes are queued through the user's root manager. */
public class SettingsActivity extends Activity {

    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";

    private boolean mDark;
    private boolean mAppSuperDark;
    private View mAppRoot;
    private android.database.ContentObserver mPaletteObserver;
    private float mDp;
    private int mBg, mCard, mText, mTextSecondary, mAccent, mOnAccent;

    private static final Map<String,Boolean> sSwitchValues=new java.util.concurrent.ConcurrentHashMap<String,Boolean>();
    private final Map<String, Switch> mSwitches = new LinkedHashMap<String, Switch>();
    /**
     * One queue for every root call, shared by all instances of this screen, so a save made
     * just before a rotation or theme change still runs before the new screen's reload. Its
     * single thread ends after 10 s idle.
     */
    private static final ExecutorService IO = new ThreadPoolExecutor(0, 1, 10L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mBanner;
    private View mMiscChooseApps;
    /** True while switches are set from stored values, so their listeners don't save. */
    private boolean mUpdating;
    /** Counts saves (main thread), so a reload that started before one doesn't undo it. */
    private int mSaves;
    private android.widget.SeekBar mLabelSlider;
    private android.widget.SeekBar mStyleSlider;
    private final TextView[] mStyleStops=new TextView[3];
    private SurfacePreview mStylePreview;
    private boolean mStyleSuperDark,mStyleLocked,mStyleLoaded;
    private int mSavedStyle=SurfaceStyle.FROSTED;
    private long mLastStyleRejection;
    private static final String[] STYLE_LABELS={"Blur Off","Stock","Frosted Glass"};
    private TextView mLabelPreview;
    private final TextView[] mLabelStops=new TextView[5];
    private FeaturePreview mFeaturePreview;
    private RestartPill mRestart;
    private ImageView mDeveloperGear;
    private android.animation.ObjectAnimator mGearRotation;
    private PrivacyPageGate mPrivacyGate;
    private View mPrivacyContent;
    private android.os.CancellationSignal mPrivacyAuthSignal;
    private int mPrivacyFailures;
    private final java.util.List<FeaturePreview> mPreviews=new java.util.ArrayList<FeaturePreview>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        // The manifest theme selects light/night resources before Android draws the launch window.
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mDp = getResources().getDisplayMetrics().density;
        resolvePalette();
        styleWindow();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(96));

        String pageId = getIntent().getStringExtra("page");
        if (pageId != null && !pageId.equals("home")) {
            TextView back = text("\u2039  Back", 17, mAccent, true);
            back.setPadding(dp(8),dp(12),dp(8),dp(8));back.setMinimumHeight(dp(48));
            back.setOnClickListener(new View.OnClickListener(){public void onClick(View v){finish();}});
            page.addView(back);
        }
        LinearLayout heading=page;
        if(pageId==null||"home".equals(pageId)){
            FrameLayout header=new FrameLayout(this);header.setClipChildren(false);header.setClipToPadding(false);
            heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);heading.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams titleParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);titleParams.rightMargin=dp(144);header.addView(heading,titleParams);
            FrameLayout.LayoutParams links=new FrameLayout.LayoutParams(dp(120),-2,Gravity.TOP|Gravity.RIGHT);links.topMargin=dp(28);links.rightMargin=0;header.addView(supportCorner(),links);page.addView(header,new LinearLayout.LayoutParams(-1,-2));
        }
        TextView title = text(pageTitle(pageId), 36, mText, false);
        title.setPadding(dp(8), dp(32), dp(8), dp(8));
        if(pageId==null||"home".equals(pageId))title.setGravity(Gravity.CENTER_HORIZONTAL);
        heading.addView(title);

        TextView subtitle = text(pageSubtitle(pageId), 17, mTextSecondary, false);
        subtitle.setPadding(dp(8), 0, dp(8), dp(12));
        if(pageId==null||"home".equals(pageId))subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        if(subtitle.length()>0)heading.addView(subtitle);
        if("changelog".equals(pageId)){
            TextView version = text("  " + versionName() + "  \u00b7  RovianDev  ", 12, mAccent, true);
            GradientDrawable chip = AppSurfaces.card(this,18);
            version.setBackground(chip); version.setPadding(dp(12),dp(8),dp(12),dp(8));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2,-2); chipParams.leftMargin=dp(8);chipParams.bottomMargin=dp(20);
            heading.addView(version,chipParams);
        }

        mBanner = banner();
        mBanner.setVisibility(View.GONE);
        page.addView(mBanner);

        buildPage(page);

        final ScrollView scroll = new SmoothScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        mRestart = new RestartPill(this, root, mAccent, mOnAccent);
        if("privacy".equals(pageId)){
            mPrivacyGate=new PrivacyPageGate();mPrivacyContent=root;
            root.setVisibility(View.INVISIBLE);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(root);mAppRoot=root;root.setBackgroundColor(mBg);
        mPaletteObserver=new android.database.ContentObserver(mMain){@Override public void onChange(boolean self){refreshAppSurfaces();}};
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(SuperDark.KEY),false,mPaletteObserver);
        scroll.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();refreshAppSurfaces();startDeveloperGear();
        if(mPrivacyGate!=null){mPrivacyGate.resume();if(!mPrivacyGate.isOpen()){mPrivacyContent.setVisibility(View.INVISIBLE);authenticatePrivacy();return;}}
        resumeSettingsContent();
    }

    private void resumeSettingsContent(){
        if (mRestart != null) mRestart.refresh();
        load();
        ReleaseUpdates.schedule(SettingsActivity.this); ReleaseUpdates.check(SettingsActivity.this,false,null);
        for(FeaturePreview preview:mPreviews)preview.setHostActive(true);
        // Super Dark switched on before preview91 has no boot property yet; write it once so the next boot is dark from the start.
        if(!sPropChecked&&Feature.on(this,SuperDark.KEY)){sPropChecked=true;
            IO.execute(new Runnable(){public void run(){Root.run("[ \"$(getprop "+SuperDark.PROP+")\" = 1 ] || setprop "+SuperDark.PROP+" 1");}});}

    }

    @Override protected void onPause(){
        stopDeveloperGear();
        if(mPrivacyGate!=null){mPrivacyGate.pause();mPrivacyContent.setVisibility(View.INVISIBLE);}
        for(FeaturePreview preview:mPreviews)preview.setHostActive(false);super.onPause();
    }
    @Override protected void onStop(){
        if(mPrivacyGate!=null){mPrivacyGate.stop();if(mPrivacyAuthSignal!=null)mPrivacyAuthSignal.cancel();}
        super.onStop();
    }
    private void authenticatePrivacy(){
        final int attempt=mPrivacyGate.begin();if(attempt==0)return;
        android.app.KeyguardManager lock=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(lock==null||!lock.isDeviceSecure()){
            Toast.makeText(this,"Set a phone screen lock first",Toast.LENGTH_LONG).show();finish();return;
        }
        mPrivacyFailures=0;
        try{
            android.hardware.biometrics.BiometricPrompt.Builder b=privacyPrompt();
            b.setAllowedAuthenticators(0x00ff);b.setConfirmationRequired(false);
            b.setNegativeButton("Pin",privacyExecutor(),new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int which){privacyCredentialLater(attempt);}});
            mPrivacyAuthSignal=new android.os.CancellationSignal();
            b.build().authenticate(mPrivacyAuthSignal,privacyExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){privacyAuthenticated(attempt);}
                @Override public void onAuthenticationFailed(){if(mPrivacyGate.isPending(attempt)&&++mPrivacyFailures>=3)privacyCredentialLater(attempt);}
                @Override public void onAuthenticationError(int code,CharSequence message){
                    if(code==13||code==7||code==9||code==11||code==12||code==1)privacyCredentialLater(attempt);
                    else privacyAuthFailed(attempt);
                }
            });
        }catch(Throwable error){privacyCredentialLater(attempt);}
    }
    private android.hardware.biometrics.BiometricPrompt.Builder privacyPrompt(){
        android.hardware.biometrics.BiometricPrompt.Builder b=new android.hardware.biometrics.BiometricPrompt.Builder(this);
        b.setTitle("Privacy & Security");b.setDescription("Confirm it's you to change privacy settings");return b;
    }
    private java.util.concurrent.Executor privacyExecutor(){return new java.util.concurrent.Executor(){public void execute(Runnable r){mMain.post(r);}};}
    private void privacyCredentialLater(int attempt){
        final int next=mPrivacyGate.replace(attempt);
        if(next==0){mPrivacyGate.error(attempt);return;}
        if(mPrivacyAuthSignal!=null)mPrivacyAuthSignal.cancel();
        mMain.postDelayed(new Runnable(){public void run(){
            if(isFinishing()||isDestroyed()||!mPrivacyGate.isPending(next)){mPrivacyGate.error(next);return;}
            try{
                android.hardware.biometrics.BiometricPrompt.Builder b=privacyPrompt();b.setAllowedAuthenticators(0x8000);
                mPrivacyAuthSignal=new android.os.CancellationSignal();
                b.build().authenticate(mPrivacyAuthSignal,privacyExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){privacyAuthenticated(next);}
                    @Override public void onAuthenticationError(int code,CharSequence message){privacyAuthFailed(next);}
                });
            }catch(Throwable error){privacyAuthFailed(next);}
        }},250);
    }
    private void privacyAuthenticated(int attempt){
        if(isFinishing()||isDestroyed()||!mPrivacyGate.success(attempt))return;
        mPrivacyContent.setVisibility(View.VISIBLE);resumeSettingsContent();
    }
    private void privacyAuthFailed(int attempt){if(mPrivacyGate.error(attempt)&&!isFinishing())finish();}
    @Override protected void onDestroy(){stopDeveloperGear();if(mPaletteObserver!=null)getContentResolver().unregisterContentObserver(mPaletteObserver);if(mPrivacyGate!=null){mPrivacyGate.stop();if(mPrivacyAuthSignal!=null)mPrivacyAuthSignal.cancel();}if(mRestart!=null)mRestart.dispose();for(AlertDialog dialog:new java.util.ArrayList<AlertDialog>(mSettingsDialogs))dialog.dismiss();for(FeaturePreview preview:mPreviews)preview.dispose();super.onDestroy();}

    private static boolean sPropChecked;

    private static final class FeaturePage {
        final String group,key,icon,title,summary;
        FeaturePage(String group,String key,String icon,String title,String summary){this.group=group;this.key=key;this.icon=icon;this.title=title;this.summary=summary;}
    }
    private static final FeaturePage[] PAGES={
        new FeaturePage("buttons",Feature.FLASHLIGHT,"ic_ps_flashlight","Power button flashlight","Double-press the power button to turn the flashlight on or off."),
        new FeaturePage("buttons",Feature.VOLDOWN_CAMERA,"ic_ps_camera","Volume-down camera","Double-press volume down while the screen is off to open the camera. Doesn't work during calls or while media is playing."),
        new FeaturePage("gestures",Feature.THREE_FINGER,"ic_ps_threefinger","Three-finger screenshot","Swipe down with three fingers to take a screenshot."),
        new FeaturePage("gestures",ScreenRecordTidy.KEY,"ic_ps_screen_record","Screen recording sheet","Moves X and Start below the recording options for a cleaner screen. Works with System UI in English."),
        new FeaturePage("gestures",Feature.SCREENSHOT_FREE_CROP,"ic_ps_crop_free","Free screenshot crop","Crop screenshots to any size in Pixel Studio, even a thin strip or a single line of text."),
        new FeaturePage("doodles",Feature.SCREENSHOT_DOODLES,"ic_ps_doodles","Pixel Studio","Draw on screenshots with shapes, arrows, snapping guides, and Undo and Redo."),
        new FeaturePage("doodles",Feature.PHOTOS_DOODLES,"ic_ps_photos_doodles","Google Photos","Draw from the Doodles tab next to Crop. Your shapes are saved, so you can edit them again later."),
        new FeaturePage("gestures",Feature.DOUBLE_TAP_SLEEP,"ic_ps_sleep","Double tap to sleep","Double-tap an empty spot on the home screen to lock your phone."),
        new FeaturePage("gestures",Feature.TAP_CHECK,"ic_ps_tapwake","Tap to wake","Wake your phone with a single or double tap, with pocket protection. Double-tap the lock screen to turn it off again."),
        new FeaturePage("launcher",LauncherFeatures.GRID,"ic_ps_folder_grid","Folder grid","Folders grow with the number of apps inside. Pick a grid from each folder's three-dot menu."),
        new FeaturePage("drawer",LauncherFeatures.DRAWER,"ic_ps_drawer_folder","App drawer folders","Make folders in the app drawer, with their own grids and sorting."),
        new FeaturePage("drawer",LauncherFeatures.DRAG_PILL,"ic_ps_home_drop","Drag to home screen","Hold an app in the drawer and drag it onto Add to home screen. Dragging sideways or down keeps you in the drawer."),
        new FeaturePage("launcher",LauncherFeatures.ICONS,"ic_ps_change_icon","Change icons","Hold an app and choose Change icon. Move and zoom your image, then confirm. It keeps your icon shape."),
        new FeaturePage("launcher",FolderDrag.TWO_FINGER,"ic_ps_two_finger","Two-finger drag","Hold an app with one finger, and swipe with another to turn home or folder pages, or to scroll the app drawer."),
        new FeaturePage("launcher",MultiDrag.KEY,"ic_ps_multi_drag","Move several apps","Start dragging an app, then tap more apps with another finger. Drop them all on the home screen or into a folder."),
        new FeaturePage("launcher",LauncherFeatures.RENAME,"ic_ps_rename","Rename apps","Hold an app and choose Rename. The new name shows right away on the home screen, in folders, in the drawer and in search."),
        new FeaturePage("launcher",LauncherFeatures.LABEL_SIZE,"ic_ps_label_size","Icon label size","Five text sizes for app names on the home screen, in the drawer and on closed folders. Smaller text fits longer names. Icons stay the same size. Off by default."),
        new FeaturePage("recents",Feature.CLEAR_ALL,"ic_ps_clearall","Clear all in recents","Clear all is always next to Select and Screenshot in recents, so you don't have to scroll to the far left."),
        new FeaturePage("recents",RecentsLock.KEY,"ic_ps_recents_lock","Lock apps in recents","Choose Lock from an app's recents menu: its card stays when you swipe it or use Clear all. Tap its lock badge to unlock it."),
        new FeaturePage("drawer",LauncherFeatures.HIDE_SHORTCUTS,"ic_ps_shortcuts","Hide app shortcuts","Hides shortcuts that apps add to their drawer menu, such as View orders. The launcher's own options and Pixel Suite's stay."),
        new FeaturePage("themes",SuperDark.KEY,"ic_ps_super_dark","Super Dark-Mode","Pure black backgrounds and darker menus in dark theme. Off by default."),
        new FeaturePage("files",Feature.FILES_SORT,"ic_ps_files","Newest first","Shows folders first and the newest files at the top, in Files by Google and Android's file picker.")
    };
    private final Map<String,TextView> mChoiceLabels=new LinkedHashMap<String,TextView>();
    private final Map<String,Integer> mChoiceOffsets=new LinkedHashMap<String,Integer>();
    private final Map<String,ImageView> mChoiceIcons=new LinkedHashMap<String,ImageView>();
    private final Map<String,String[]> mChoiceItems=new LinkedHashMap<String,String[]>();
    private FeaturePage feature(String id){for(FeaturePage f:PAGES)if(f.key.equals(id))return f;return null;}
    private String pageTitle(String id){
        FeaturePage f=feature(id);if(f!=null)return f.title;
        if("buttons".equals(id))return "Buttons";if("gestures".equals(id))return "Gestures";
        if("doodles".equals(id))return "Doodles";
        if("drawer".equals(id))return "App drawer";if("launcher".equals(id))return "Pixel Launcher";if("files".equals(id))return "Files";if("themes".equals(id))return "Themes";
        if("privacy".equals(id))return "Privacy & Security";
        if("recents".equals(id))return "Recent Apps";
        if("miscellaneous".equals(id))return "Miscellaneous";
        if("changelog".equals(id))return "Changelog";
        if("updates".equals(id))return "Updates";if("about".equals(id))return "About and support";return "Pixel Suite";
    }
    private String pageSubtitle(String id){
        if(feature(id)!=null)return "Make it work your way.";
        if("buttons".equals(id))return "Quick actions from your phone's buttons.";
        if("gestures".equals(id))return "Capture, sleep and wake.";
        if("doodles".equals(id))return "Draw on screenshots and photos.";
        if("drawer".equals(id))return "Folders, dragging and app shortcuts.";if("launcher".equals(id))return "Folders, names, icons and recents.";
        if("files".equals(id))return "A better starting view for your files.";if("themes".equals(id))return "System colors, your way.";
        if("privacy".equals(id))return "Protected with your fingerprint or PIN.";
        if("recents".equals(id))return "Keep what matters. Clear the rest.";
        if("miscellaneous".equals(id))return "Small fixes, simple controls.";
        if("changelog".equals(id))return "What's new";
        if("updates".equals(id))return "";if("about".equals(id))return "Made by RovianDev";return "Your Pixel. Your way.";
    }
    private void openPage(String id){startActivity(new Intent(this,SettingsActivity.class).putExtra("page",id));}
    private void buildPage(LinearLayout page){
        String id=getIntent().getStringExtra("page");FeaturePage f=feature(id);
        if(f!=null){
            LinearLayout controls=group(page);row(controls,f.key,f.icon,mAccent,(Feature.SCREENSHOT_DOODLES.equals(f.key)||Feature.PHOTOS_DOODLES.equals(f.key)?"Use Doodles":LauncherFeatures.HIDE_SHORTCUTS.equals(f.key)?"Hide app shortcuts":LauncherFeatures.ICONS.equals(f.key)?"Use custom app icons":LauncherFeatures.LABEL_SIZE.equals(f.key)?"Customize icon labels":SuperDark.KEY.equals(f.key)?"Use Super Dark-Mode":"Use "+f.title.toLowerCase(java.util.Locale.ROOT)),"Turn this feature on or off.");finishGroup(controls);
            section(page,"How it works");
            if(LauncherFeatures.DRAWER.equals(f.key)){
                explanation(page,"Drag an app onto another to make a folder. Use Add apps to pick more. Drag folders to move them.");
                featurePreview(page,f);
                section(page,"Sort from the three-dot menu");
                explanation(page,"Three-dot menu → Sort: Auto sorts apps A to Z; Custom lets you arrange them yourself.");
                extraPreview(page,"folder_sort","Sorting and custom order");
                explanation(page,"A folder can hold a single app and follows your icon shape. Hold a folder to rename, sort or remove it; removing a folder doesn't uninstall its apps.");
            }else{
                explanation(page,f.summary);
                if(!LauncherFeatures.LABEL_SIZE.equals(f.key))featurePreview(page,f);
            }
            if(SuperDark.KEY.equals(f.key)){
                section(page,"Dark theme and schedules");
                explanation(page,"Follows your dark theme schedule. In light theme, normal colors and blur come back.");
                section(page,"Where it applies");
                explanation(page,"LSPosed scopes: System UI, Pixel Launcher, Google, Gboard, Settings and Package installer. Also select Pixel Studio and Google Photos for their editors. Reboot to apply.");
            }
            if(LauncherFeatures.GRID.equals(f.key))extraPreview(page,"folder_sort","Folder sorting");
            if(LauncherFeatures.LABEL_SIZE.equals(f.key)){
                section(page,"Text size");LinearLayout options=group(page);labelSize(options);finishGroup(options);
                explanation(page,"Updates app names right away. Uses your system font and Bold text setting. Names inside open folders and in search results keep their usual size.");
            }
            if(Feature.TAP_CHECK.equals(f.key)){
                section(page,"Wake the screen");LinearLayout wake=group(page);
                choice(wake,Feature.DOUBLE_TAP_WAKE,"Wake gesture",new String[]{"Single tap","Double tap"});finishGroup(wake);
                explanation(page,"If a double tap doesn't register, tap once more within three seconds.");
                section(page,"Lock-screen taps");LinearLayout lock=group(page);
                choice(lock,Feature.LOCK_SHORTCUTS,"Lock-screen behavior",new String[]{"Pixel Suite","Stock"});finishGroup(lock);
                explanation(page,"Pixel Suite: double-tapping a lock-screen card turns the screen off without opening the card or vibrating. Stock: keeps Google's normal behavior.");
            }
            if(LauncherFeatures.GRID.equals(f.key))explanation(page,"Grid choices appear once a folder has 3 or more apps: 3×3, 4×4 from 10 apps, and 5×5 from 17 apps. With fewer apps, the folder uses a smaller grid; your choice comes back as the folder fills up.");
            if(Feature.SCREENSHOT_FREE_CROP.equals(f.key)||Feature.SCREENSHOT_DOODLES.equals(f.key)){
                section(page,"Pixel Studio setup");
                explanation(page,"Enable Pixel Studio in LSPosed, then restart it. Changing this switch restarts Pixel Studio, so save your edits first.");
            }
            if(Feature.PHOTOS_DOODLES.equals(f.key)){
                section(page,"Google Photos setup");
                explanation(page,"Enable Google Photos in LSPosed, then restart it. Open a photo → Edit → Doodles. After tapping Done, tap Doodles again to bring the toolbar back. Changing this switch restarts Photos, so save your edits first.");
            }
            if(Feature.PHOTOS_DOODLES.equals(f.key))explanation(page,"Reopen a saved photo to adjust its shapes. Revert removes the Doodles. Everything stays on your phone, inside Photos, and your backup settings aren't changed.");
            if(Feature.SCREENSHOT_DOODLES.equals(f.key))explanation(page,"Tap the shape or rainbow icon for more choices, and tap the arrow to collapse them. Drag or flick the menu up or down.");
            if(Feature.SCREENSHOT_DOODLES.equals(f.key))explanation(page,"Drag the handles to move or resize a shape. Guides and snapping help you line things up.");
            if(Feature.SCREENSHOT_DOODLES.equals(f.key))explanation(page,"Undo and Redo work with Doodles on supported Pixel Studio versions.");
            if(LauncherFeatures.RENAME.equals(f.key))explanation(page,"Reset brings back the original name. Enable Google in LSPosed to see new names in the Google search bar.");
            if(LauncherFeatures.RENAME.equals(f.key))explanation(page,"Changes show right away on the home screen, in the drawer, in folders and in the Google search bar. Apps in your work profile and Private Space keep their names.");
            if(LauncherFeatures.ICONS.equals(f.key))explanation(page,"Enable Google in LSPosed to show custom icons in the Google search bar too.");
            if(Feature.CLEAR_ALL.equals(f.key))explanation(page,"Changing this option restarts Pixel Launcher.");
            if(RecentsLock.KEY.equals(f.key))explanation(page,"LSPosed scope: Pixel Launcher. Locks stay after a reboot. A locked card stays in recents, but Android can still close the app in the background.");
            if(Feature.FILES_SORT.equals(f.key))explanation(page,"Enable Files by Google and Files (system) in LSPosed. Turn this off to go back to their normal sorting.");
            if(FolderDrag.TWO_FINGER.equals(f.key))explanation(page,"Touch the drawer to stop it scrolling while you drag.");
            if(MultiDrag.KEY.equals(f.key))explanation(page,"Pick apps from the same folder or screen. Removing them from the home screen doesn't uninstall them.");
            if(LauncherFeatures.ICONS.equals(f.key))explanation(page,"Changes show right away on the home screen, in the drawer, in folders and in the Google search bar. Tap the reset arrow to bring back the original icon. Apps in Private Space aren't included.");
            return;
        }
        if("miscellaneous".equals(id)){
            LinearLayout controls=group(page);
            row(controls,ScreenshotWarm.KEY,"ic_ps_fast_screenshot",mAccent,"Reduce screenshot delay","Keeps the screenshot service ready, so screenshots appear instantly. Takes effect from the second screenshot after a restart. Uses a little extra memory.");
            row(controls,MiscSystem.SECURE_KEY,"ic_ps_secure_capture",mAccent,"Allow screenshots of secure screens","Take screenshots and recordings in apps that normally block them. Apps aren't told.\nIf you use the DisableFlagSecure module, remove it before turning this on.\nBased on DisableFlagSecure by LSPosed (GPL-3.0).");
            row(controls,MiscSystem.A11Y_KEY,"ic_ps_accessibility",mAccent,"Hide accessibility from apps","Apps you choose can't see your accessibility services.\nIf you use the DoNotTryAccessibility module, remove it before turning this on.\nBased on DoNotTryAccessibility by Nitsuya (GPL-3.0).");
            navigation(controls,"Choose apps","Choose which apps can't see your accessibility services","ic_ps_drawer",new Runnable(){public void run(){
                MiscAppPicker.show(SettingsActivity.this,IO,mText,mTextSecondary,mAccent,new MiscAppPicker.Presenter(){public void show(AlertDialog dialog){showSettingsDialog(dialog);}});
            }});
            mMiscChooseApps=controls.getChildAt(controls.getChildCount()-1);updateMiscPicker();
            row(controls,KeyboardBarTweak.KEY,"ic_ps_keyboard",mAccent,"Keyboard bar tweak","Hides the globe and moves the dismiss arrow to the right, in the bar under your keyboard. Restarts your keyboard.");
            row(controls,MiscSystem.DEV_KEY,"ic_ps_developer",mAccent,"Hide Developer Options","Apps can't tell developer options or USB debugging are on, so apps that refuse to open with them on (like some banking apps) work normally.\nTap the gear to open Developer options.");
            finishGroup(controls);
            explanation(page,"LSPosed: System Framework for screenshots, accessibility and developer options; select your keyboard for the keyboard bar. The keyboard bar needs gesture navigation.");
            return;
        }
        if("recents".equals(id)){
            LinearLayout controls=group(page);
            row(controls,Feature.CLEAR_ALL,"ic_ps_clearall",mAccent,"Always show Clear all","Keeps Clear all next to Screenshot and Select.");
            row(controls,RecentsLock.KEY,"ic_ps_recents_lock",mAccent,"Lock apps in recents","Choose Lock from an app's recents menu to keep its card when you clear apps.");finishGroup(controls);
            explanation(page,"In split screen, each app locks on its own. A recents lock only keeps the card; to require your fingerprint or PIN to open an app, use Privacy & Security.");
            extraPreview(page,RecentsLock.KEY,"Locking recent apps");
            section(page,"Landscape view");
            explanation(page,"Turn your phone sideways: app names stay centered above their cards and the status bar fades out. It comes back when you turn the phone back or leave recents.");
            extraPreview(page,"recents_landscape","Landscape recent apps");
            return;
        }
        if("privacy".equals(id)){
            LinearLayout protection=group(page);
            row(protection,PrivacyLock.ENABLED_KEY,"ic_ps_app_folder_lock",mAccent,"App & folder lock","Asks for your fingerprint or PIN before locked apps and folders open. Turning it off keeps your locks for later.");
            row(protection,PrivacyLock.PROTECT_KEY,"ic_ps_protect_apps",mAccent,"Protect locked apps","Asks before a locked app is uninstalled, disabled or paused, or a locked folder is removed.");
            row(protection,PrivacyLock.GRACE_KEY,"ic_ps_unlock_timer",mAccent,"Keep unlocked for 1 minute","On: an app or folder you just unlocked stays open for 1 minute after you leave it. Off: apps lock as soon as you leave them, and folders as soon as you close them.");finishGroup(protection);
            section(page,"How it works");
            explanation(page,"Hold an app or folder and choose Lock. Open it with your fingerprint or PIN. Choose Unlock from the same menu to remove the lock.");
            extraPreview(page,"privacy_lock","App lock");
            section(page,"Folder locks");
            explanation(page,"Locking a folder protects opening the folder. Its apps still open from the drawer or search, unless you lock them too.");
            extraPreview(page,"privacy_folder","Folder lock and padlock badge");
            section(page,"Setup");
            explanation(page,"LSPosed scopes: System Framework, Pixel Launcher and System UI. Reboot after installing. Set up a screen lock on your phone first.");
            return;
        }
        if("updates".equals(id)){buildUpdates(page);return;}
        if("about".equals(id)){
            explanation(page,"All of Pixel Suite's settings in one place. To get back here, long-press the home screen → Pixel Suite settings. Needs root and LSPosed.");
            section(page,"Community and support");communityActions(page);
            return;
        }
        if("changelog".equals(id)){
            explanation(page,"The latest changes, available offline.");
            LinearLayout history=group(page);navigation(history,"Full changelog on GitHub","Every feature, fix and release","ic_ps_changelog",new Runnable(){public void run(){startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/RovianDev/PixelSuite/releases")));}});finishGroup(history);
            String latest=Changelog.read(this);int next=latest.indexOf("\n## ");if(next>=0)latest=latest.substring(0,next);String[] entries=latest.split("(?m)^## ");
            for(String entry:entries){if(entry.trim().isEmpty())continue;int end=entry.indexOf('\n');String heading=end<0?entry.trim():entry.substring(0,end).trim();String body=end<0?"":entry.substring(end+1).trim();section(page,heading);TextView notes=text(body,15,mText,false);notes.setText(ChangelogText.format(body));notes.setTextIsSelectable(true);notes.setLineSpacing(dp(4),1);notes.setPadding(dp(18),dp(16),dp(18),dp(18));notes.setBackground(AppSurfaces.card(this,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);page.addView(notes,lp);}
            return;
        }
        boolean category="buttons".equals(id)||"gestures".equals(id)||"doodles".equals(id)||"launcher".equals(id)||"drawer".equals(id)||"files".equals(id)||"themes".equals(id);
        if(category){
            LinearLayout features=group(page);if("launcher".equals(id)){
                navigation(features,"App drawer","Folders, dragging and app shortcuts","ic_ps_drawer",new Runnable(){public void run(){openPage("drawer");}});
                navigation(features,"Recent Apps","Clear all, locked cards and landscape view","ic_ps_recent_apps",new Runnable(){public void run(){openPage("recents");}});
            }for(final FeaturePage item:PAGES)if(item.group.equals(id))navigation(features,item.title,item.summary,item.icon,new Runnable(){public void run(){openPage(item.key);}});finishGroup(features);
            if("themes".equals(id)){
                // build 190 (owner): one slider for every surface Pixel Suite draws, with a live preview
                section(page,"Surfaces and blur");LinearLayout style=group(page);surfaceStyle(style);finishGroup(style);
                section(page,"The three styles");
                explanation(page,"Blur Off\nTurns off blur everywhere, including Android's window blur. Surfaces are white in light theme and tinted black in dark theme.\n\nStock\nGoogle's own surfaces. Pixel Suite's other changes stay.\n\nFrosted Glass (Default)\nBlurs what's behind folders, menus and messages, with Pixel Suite's tint.");
                explanation(page,"Tap a style or drag the slider to save it. Google Photos restarts on its own; use the restart button for Pixel Launcher.");
            }
            return;
        }
        section(page,"Personalize");LinearLayout categories=group(page);
        navigation(categories,"Buttons","Flashlight and camera shortcuts","ic_ps_buttons",new Runnable(){public void run(){openPage("buttons");}});
        navigation(categories,"Gestures","Screenshots, sleep and wake","ic_ps_gestures",new Runnable(){public void run(){openPage("gestures");}});
        navigation(categories,"Doodles","Shapes and colors in Pixel Studio and Google Photos","ic_ps_doodles",new Runnable(){public void run(){openPage("doodles");}});
        navigation(categories,"Pixel Launcher","Folders, drawer, icons, names and recents","ic_ps_launcher",new Runnable(){public void run(){openPage("launcher");}});
        navigation(categories,"Themes","Super Dark-Mode, blur and surface styles","ic_ps_themes",new Runnable(){public void run(){openPage("themes");}});
        navigation(categories,"Files","Newest files first, in Files and the file picker","ic_ps_files_category",new Runnable(){public void run(){openPage("files");}});navigation(categories,"Privacy & Security","Lock apps and folders","ic_ps_privacy",new Runnable(){public void run(){openPage("privacy");}});
        navigation(categories,"Miscellaneous","Screenshots, accessibility, keyboard and developer options","ic_ps_miscellaneous",new Runnable(){public void run(){openPage("miscellaneous");}});finishGroup(categories);
        section(page,"Pixel Suite");LinearLayout about=group(page);navigation(about,"Changelog","What's new, available offline","ic_ps_changelog",new Runnable(){public void run(){openPage("changelog");}});navigation(about,"Updates","Check for updates and manage notifications","ic_ps_update",new Runnable(){public void run(){openPage("updates");}});navigation(about,"About and support","Setup, community and support","ic_ps_info",new Runnable(){public void run(){openPage("about");}});finishGroup(about);
    }
    private LinearLayout supportCorner(){
        LinearLayout stack=new LinearLayout(this);stack.setOrientation(LinearLayout.VERTICAL);stack.setGravity(Gravity.RIGHT);
        int[] order={0,2,1};
        for(int position=0;position<order.length;position++){
            final int index=order[position];LinearLayout button=new LinearLayout(this);button.setOrientation(LinearLayout.HORIZONTAL);button.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);button.setPadding(dp(10),dp(6),dp(10),dp(6));button.setMinimumHeight(dp(48));
            ImageView icon=new ImageView(this);Drawable mark=drawable(index==0?"brand_telegram_glyph":CommunityLinks.ICONS[index]);
            if(mark!=null){mark=mark.mutate();if(index==2)mark.setTint(mDark?Color.WHITE:Color.BLACK);icon.setImageDrawable(mark);}
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(20),dp(20));imageParams.rightMargin=dp(7);button.addView(icon,imageParams);
            TextView label=text(CommunityLinks.NAMES[index],12,mText,true);label.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setMaxWidth(dp(100));label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);button.addView(label,new LinearLayout.LayoutParams(dp(68),-2));
            supportSurface(button);button.setElevation(dp(1));
            button.setClickable(true);button.setFocusable(true);button.setContentDescription(CommunityLinks.NAMES[index]+". "+CommunityLinks.CAPTIONS[index]+". Opens an external app.");
            button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){CommunityLinks.open(SettingsActivity.this,index);}});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));params.gravity=Gravity.RIGHT;if(position>0)params.topMargin=dp(6);stack.addView(button,params);
        }return stack;
    }
    /** A local rounded blur region: no full-window blur and no background animation. */
    private void supportSurface(final View button){
        supportBackground(button,null);
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewDetachedFromWindow(View v){}
            public void onViewAttachedToWindow(final View v){v.post(new Runnable(){public void run(){
                if(!v.isAttachedToWindow())return;
                try{
                    if(!FrostedSurface.blurEnabled(SettingsActivity.this))return;
                    Object root=LauncherFeatures.call(v,"getViewRootImpl");
                    Drawable blur=(Drawable)LauncherFeatures.call(root,"createBackgroundBlurDrawable");
                    LauncherFeatures.call(blur,"setBlurRadius",FrostedSurface.depthBlurRadius(SettingsActivity.this));
                    LauncherFeatures.call(blur,"setCornerRadius",(float)dp(24));
                    LauncherFeatures.call(blur,"setColor",Color.TRANSPARENT);supportBackground(v,blur);
                }catch(Throwable unavailable){supportBackground(v,null);}
            }});}
        });
    }
    private void supportBackground(View button,Drawable blur){
        GradientDrawable tint=new GradientDrawable();tint.setColor((AppSurfaces.normalCard(this) & 0x00ffffff)|(blur==null?0xff000000:0xcc000000));tint.setCornerRadius(dp(24));
        Drawable fill=blur==null?tint:new android.graphics.drawable.LayerDrawable(new Drawable[]{blur,tint});fill=AppSurfaces.support(this,fill,24);
        GradientDrawable mask=new GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadius(dp(24));
        button.setBackground(new android.graphics.drawable.InsetDrawable(new RippleDrawable(ColorStateList.valueOf(mDark?0x33FFFFFF:0x22000000),fill,mask),0,dp(2),0,dp(2)));
    }
    private ImageView brandIcon(int index){
        ImageView icon=new ImageView(this);icon.setImageDrawable(drawable(CommunityLinks.ICONS[index]));icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setPadding(dp(10),dp(10),dp(10),dp(10));GradientDrawable bg=new GradientDrawable();bg.setColor(CommunityLinks.BADGES[index]);bg.setCornerRadius(dp(16));icon.setBackground(bg);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return icon;
    }
    private void communityActions(LinearLayout page){
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,-2);outer.bottomMargin=dp(12);page.addView(actions,outer);
        int[] order={0,2,1};for(int position=0;position<order.length;position++){
            final int index=order[position];LinearLayout button=new LinearLayout(this);button.setOrientation(LinearLayout.HORIZONTAL);button.setGravity(Gravity.CENTER_VERTICAL);button.setPadding(dp(18),dp(16),dp(18),dp(16));button.setMinimumHeight(dp(80));button.setBackground(rowBackground(dp(24),dp(24)));
            LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(48),dp(48));iconParams.rightMargin=dp(16);button.addView(brandIcon(index),iconParams);
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);labels.addView(text(CommunityLinks.NAMES[index],18,mText,false));
            TextView caption=text(CommunityLinks.CAPTIONS[index],14,mTextSecondary,false);caption.setPadding(0,dp(4),0,0);labels.addView(caption);button.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            button.setClickable(true);button.setFocusable(true);button.setContentDescription(CommunityLinks.NAMES[index]+". "+CommunityLinks.CAPTIONS[index]+". Opens an external app.");button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){CommunityLinks.open(SettingsActivity.this,index);}});
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2,0);if(position>0)bp.topMargin=dp(8);actions.addView(button,bp);
        }
    }
    private void featurePreview(LinearLayout page,FeaturePage feature){
        section(page,"Feature preview");LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(AppSurfaces.card(this,24));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(16);page.addView(card,cp);
        mFeaturePreview=new FeaturePreview(this,feature.key,mAccent,mOnAccent,mText,mTextSecondary,mCard,mDark);mFeaturePreview.setContentDescription(feature.title+". A looping demo. It doesn't change your settings.");card.addView(mFeaturePreview,new LinearLayout.LayoutParams(-1,dp((SuperDark.KEY.equals(feature.key)||LauncherFeatures.DRAWER.equals(feature.key)||LauncherFeatures.GRID.equals(feature.key)||"folder_sort".equals(feature.key)||FolderDrag.TWO_FINGER.equals(feature.key)||MultiDrag.KEY.equals(feature.key))?330:300)));mPreviews.add(mFeaturePreview);
    }
    private void extraPreview(LinearLayout page,String key,String title){featurePreview(page,new FeaturePage("launcher",key,"ic_ps_folder_grid",title,""));}
    private TextView mUpdateStatus;
    private android.widget.ProgressBar mUpdateProgress;
    private void beginUpdateInstall(){
        UpdateInstaller.start(this,new UpdateInstaller.Progress(){public void show(String message,int percent){
            if(isDestroyed()||isFinishing())return;
            if(mUpdateStatus!=null)mUpdateStatus.setText(message);
            if(mUpdateProgress!=null){mUpdateProgress.setVisibility(View.VISIBLE);mUpdateProgress.setIndeterminate(percent<0&&message.contains("Verifying"));if(percent>=0){mUpdateProgress.setIndeterminate(false);mUpdateProgress.setProgress(percent);}else if(!message.contains("Verifying"))mUpdateProgress.setVisibility(View.GONE);}
        }});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==202&&getIntent().getBooleanExtra("resume_update_install",false)){getIntent().removeExtra("resume_update_install");try{if((Boolean)getPackageManager().getClass().getMethod("canRequestPackageInstalls").invoke(getPackageManager()))beginUpdateInstall();else if(mUpdateStatus!=null)mUpdateStatus.setText("Installation permission was not enabled. Tap Download and install when ready.");}catch(Exception unavailable){}}}
    private void buildUpdates(LinearLayout page){
        explanation(page,"Pixel Suite checks for official updates from time to time. Download an update here, follow its progress, then confirm the install.");
        final android.widget.Switch automatic=new android.widget.Switch(this);automatic.setText("Check for updates automatically");android.graphics.drawable.Drawable updateIcon=drawable("ic_ps_update");if(updateIcon!=null){updateIcon=updateIcon.mutate();updateIcon.setTint(mAccent);updateIcon.setBounds(0,0,dp(24),dp(24));automatic.setCompoundDrawables(updateIcon,null,null,null);automatic.setCompoundDrawablePadding(dp(12));}automatic.setTextColor(mText);automatic.setChecked(ReleaseUpdates.enabled(this));automatic.setPadding(dp(18),dp(16),dp(18),dp(16));page.addView(automatic);
        automatic.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(android.widget.CompoundButton v,boolean checked){ReleaseUpdates.prefs(SettingsActivity.this).edit().putBoolean("enabled",checked).apply();ReleaseUpdates.schedule(SettingsActivity.this);if(checked)ReleaseUpdates.check(SettingsActivity.this,false,null);}});
        final TextView status=text(ReleaseUpdates.status(this),15,mTextSecondary,false);mUpdateStatus=status;status.setPadding(dp(18),dp(12),dp(18),dp(16));page.addView(status);mUpdateProgress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);mUpdateProgress.setMax(100);mUpdateProgress.setVisibility(View.GONE);page.addView(mUpdateProgress,new LinearLayout.LayoutParams(-1,dp(8)));
        LinearLayout actions=group(page);
        navigation(actions,"Check now","Check for updates now","ic_ps_update",new Runnable(){public void run(){status.setText("Checking both repositories…");ReleaseUpdates.check(SettingsActivity.this,true,new ReleaseUpdates.Result(){public void done(String message){if(!isDestroyed())status.setText(message);}});}});
        navigation(actions,"Allow update notifications","Turn on notifications in Android settings","ic_ps_update",new Runnable(){public void run(){if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},201);else{Intent settings=new Intent("android.settings.APP_NOTIFICATION_SETTINGS");settings.putExtra("android.provider.extra.APP_PACKAGE",getPackageName());try{startActivity(settings);}catch(Exception unavailable){android.widget.Toast.makeText(SettingsActivity.this,"Open Android app settings to manage notifications.",0).show();}}}});
        navigation(actions,"Download and install","Download and install the update in Pixel Suite","ic_ps_update",new Runnable(){public void run(){beginUpdateInstall();}});
        finishGroup(actions);
    }
    /** All module-owned settings choices use the exact page palette, not launcher folder frost. */
    private final java.util.List<AlertDialog> mSettingsDialogs=new java.util.ArrayList<AlertDialog>();
    private void showSettingsDialog(final AlertDialog dialog){
        dialog.setCancelable(true);dialog.setCanceledOnTouchOutside(true);dialog.show();mSettingsDialogs.add(dialog);dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){mSettingsDialogs.remove(dialog);}});
        Window window=dialog.getWindow();if(window==null)return;
        styleSettingsWindow(window);
        for(String name:new String[]{"parentPanel","topPanel","contentPanel","buttonPanel"}){
            int id=getResources().getIdentifier(name,"id","android");View panel=window.getDecorView().findViewById(id);if(panel!=null)panel.setBackgroundColor(Color.TRANSPARENT);
        }
        colorSettingsDialog(window.getDecorView());
        if(dialog.getListView()!=null)dialog.getListView().setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){public void onChildViewAdded(View parent,View child){colorSettingsDialog(child);}public void onChildViewRemoved(View parent,View child){}});
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL})if(dialog.getButton(which)!=null)dialog.getButton(which).setTextColor(mAccent);
    }
    private void styleSettingsWindow(Window window){
        if(window==null)return;
        if(AppSurfaces.active(this)){window.setBackgroundDrawable(AppSurfaces.dialog(this,28));window.setDimAmount(.72f);}
        else{GradientDrawable fill=new GradientDrawable();fill.setColor(mBg);fill.setCornerRadius(dp(28));window.setBackgroundDrawable(fill);window.setDimAmount(0f);}
        AppSurfaces.invalidateTree(window.getDecorView());
    }
    private void colorSettingsDialog(View view){
        if(view instanceof TextView)((TextView)view).setTextColor(mText);
        if(view instanceof android.widget.CheckedTextView){
            ColorStateList checks=new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{mAccent,mTextSecondary});
            ((android.widget.CheckedTextView)view).setCheckMarkTintList(checks);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)colorSettingsDialog(group.getChildAt(i));}
    }
    private void explanation(LinearLayout page,String value){
        TextView t=text(value,15,mTextSecondary,false);t.setPadding(dp(18),dp(16),dp(18),dp(18));t.setLineSpacing(dp(3),1);
        GradientDrawable bg=AppSurfaces.card(this,20);t.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);page.addView(t,lp);
    }
    private void navigation(LinearLayout group,String title,String summary,String icon,final Runnable action){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(18),dp(18),dp(18),dp(18));row.setMinimumHeight(dp(80));
        ImageView image=new ImageView(this);Drawable drawable=drawable(icon);if(drawable!=null){drawable=drawable.mutate();drawable.setTint(mAccent);image.setImageDrawable(drawable);}
        LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(28),dp(28));iconParams.rightMargin=dp(18);row.addView(image,iconParams);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);labels.addView(text(title,18,mText,false));
        TextView description=text(summary,14,mTextSecondary,false);description.setMaxLines(2);description.setEllipsize(android.text.TextUtils.TruncateAt.END);description.setPadding(0,dp(4),0,0);labels.addView(description);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("\u203a",28,mTextSecondary,false);arrow.setPadding(dp(12),0,0,0);row.addView(arrow);
        row.setClickable(true);row.setFocusable(true);row.setContentDescription(title);row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});group.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void updateChoiceLabel(String key,int value){
        TextView label=mChoiceLabels.get(key);String[] items=mChoiceItems.get(key);Integer offset=mChoiceOffsets.get(key);
        if(label!=null&&items!=null)label.setText(items[Math.max(0,Math.min(items.length-1,value-(offset==null?0:offset)))]);
        if(LauncherFeatures.LABEL_LEVEL.equals(key)&&mLabelSlider!=null){mLabelSlider.setEnabled(true);mLabelSlider.setProgress(IconLabelScale.level(value));showLabelSize(value);}
        if(SurfaceStyle.KEY.equals(key)&&mStyleSlider!=null){mSavedStyle=Math.max(0,Math.min(2,value));mStyleLoaded=true;updateStyleRestriction();}
        ImageView icon=mChoiceIcons.get(key);
        if(icon!=null){
            String name=folderGridKey(key)?gridIcon(key):Feature.LOCK_SHORTCUTS.equals(key)?"ic_ps_lock_options":value==1?"ic_ps_double_tap":"ic_ps_single_tap";
            Drawable d=drawable(name);if(d!=null){d=d.mutate();d.setTint(mAccent);icon.setImageDrawable(d);}
        }
    }
    private void showLabelSize(int value){
        int level=IconLabelScale.level(value);
        TextView meter=mChoiceLabels.get(LauncherFeatures.LABEL_LEVEL);
        if(meter!=null)meter.setText("Level "+(level+1)+" · "+IconLabelScale.name(level)+(level==2?" (Default)":""));
        if(mLabelPreview!=null)mLabelPreview.setTextSize(16*IconLabelScale.factor(level));
        for(int i=0;i<5;i++)if(mLabelStops[i]!=null){mLabelStops[i].setTextColor(i==level?mAccent:mTextSecondary);mLabelStops[i].setTypeface(null,i==level?Typeface.BOLD:Typeface.NORMAL);}
    }
    private void showStyle(int value){
        TextView meter=mChoiceLabels.get(SurfaceStyle.KEY);
        if(meter!=null)meter.setText(STYLE_LABELS[value]);
        if(mStylePreview!=null)mStylePreview.setStyle(value);
        for(int i=0;i<3;i++)if(mStyleStops[i]!=null){mStyleStops[i].setTextColor(i==value?mAccent:mTextSecondary);mStyleStops[i].setTypeface(null,i==value?Typeface.BOLD:Typeface.NORMAL);mStyleStops[i].setAlpha(mStyleLocked?.42f:1f);}
    }
    /** build 190: the Themes surface-style slider, built like the icon label size slider: meter, slider, three stops, live preview. */
    private void surfaceStyle(LinearLayout group){
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(18),dp(12),dp(18),dp(20));
        TextView meter=text("Loading\u2026",18,mAccent,true);meter.setGravity(Gravity.CENTER);box.addView(meter,new LinearLayout.LayoutParams(-1,-2));
        mChoiceLabels.put(SurfaceStyle.KEY,meter);mChoiceItems.put(SurfaceStyle.KEY,SurfaceStyle.NAMES);mChoiceOffsets.put(SurfaceStyle.KEY,0);
        mStyleSlider=new LabelSizeSlider(this,mAccent,mDark?0xff686970:0xff9d9da5,3);mStyleSlider.setEnabled(false);
        mStyleSlider.setProgressTintList(ColorStateList.valueOf(mAccent));mStyleSlider.setThumbTintList(ColorStateList.valueOf(mAccent));
        mStyleSlider.setContentDescription("Surface style, three options");
        box.addView(mStyleSlider,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout stops=new LinearLayout(this);stops.setOrientation(0);
        String[] captions={"(No blur anywhere)","(Google's own look)","(Modern)"};
        for(int i=0;i<3;i++){final int stop=i;TextView label=text(STYLE_LABELS[i]+"\n"+captions[i],13,mTextSecondary,false);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(4),0,dp(8));label.setContentDescription(STYLE_LABELS[i]+", "+captions[i]);label.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(mStyleLocked){rejectStyle();return;}if(mStyleSlider.isEnabled()){mStyleSlider.setProgress(stop);saveStyle(stop);}}});mStyleStops[i]=label;stops.addView(label,new LinearLayout.LayoutParams(0,dp(64),1));}
        box.addView(stops,new LinearLayout.LayoutParams(-1,-2));
        mStylePreview=new SurfacePreview(this,mDark,mAccent,mText);mStylePreview.setContentDescription("Preview of the chosen surface style on a folder over a wallpaper");
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(180));pp.topMargin=dp(12);box.addView(mStylePreview,pp);
        mStyleSlider.setOnTouchListener(new View.OnTouchListener(){public boolean onTouch(View v,android.view.MotionEvent event){
            if(!mStyleLocked)return false;
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)rejectStyle();
            return true;
        }});
        mStyleSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar s){if(mStylePreview!=null){mStylePreview.setInteracting(true);mStylePreview.setStyle(s.getProgress());}}public void onStopTrackingTouch(android.widget.SeekBar s){if(mStylePreview!=null){mStylePreview.setStyle(s.getProgress());mStylePreview.setInteracting(false);}}public void onProgressChanged(android.widget.SeekBar s,int value,boolean user){if(user&&mStyleLocked){s.setProgress(SurfaceStyle.FROSTED);rejectStyle();return;}showStyle(value);if(user&&!mUpdating)saveStyle(value);}});
        group.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private void updateStyleRestriction(){
        if(mStyleSlider==null)return;
        boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        mStyleLocked=dark&&mStyleSuperDark;
        mStyleSlider.setEnabled(mStyleLoaded);mStyleSlider.setAlpha(mStyleLocked?.42f:1f);
        mStyleSlider.setContentDescription(mStyleLocked?"Surface styles locked. Turn off Super Dark-Mode to change them.":"Surface style, three options");
        int shown=mStyleLocked?SurfaceStyle.FROSTED:mSavedStyle;
        mStyleSlider.setProgress(shown);showStyle(shown);
    }
    private void rejectStyle(){
        long now=android.os.SystemClock.uptimeMillis();if(now-mLastStyleRejection<700)return;mLastStyleRejection=now;
        try{
            if(android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED,1)!=0){
                android.os.Vibrator v=(android.os.Vibrator)getSystemService(VIBRATOR_SERVICE);
                boolean amplitude=v!=null&&Boolean.TRUE.equals(v.getClass().getMethod("hasAmplitudeControl").invoke(v));
                if(amplitude){Class<?> effect=Class.forName("android.os.VibrationEffect");Object pulse=effect.getMethod("createWaveform",long[].class,int[].class,int.class).invoke(null,new long[]{0,8,45,8},new int[]{0,28,0,28},-1);v.getClass().getMethod("vibrate",effect).invoke(v,pulse);}
                else mStyleSlider.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }catch(Throwable ignored){mStyleSlider.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);}
        if(mRestart!=null)mRestart.showRestriction();
    }
    private void saveStyle(final int value){
        if(mStyleLocked){rejectStyle();return;}
        mSavedStyle=value;showStyle(value);mSaves++;
        // The phone's own window blur follows: off for Blur Off (and while Super Dark-Mode is in effect), on otherwise.
        final boolean blurOff=value==SurfaceStyle.OFF||SuperDark.inEffect(this);
        final String cmd="settings put secure "+SurfaceStyle.KEY+" "+value+"; settings put global disable_window_blurs "+(blurOff?1:0)+"; am force-stop "+PhotosEditor.PACKAGE+" >/dev/null 2>&1; true";
        IO.execute(new Runnable(){public void run(){final boolean ok=Root.run(cmd)!=null;mMain.post(new Runnable(){public void run(){if(isFinishing()||isDestroyed())return;if(ok){SurfaceStyle.refresh();RestartPill.changed(SurfaceStyle.KEY);if(mRestart!=null)mRestart.refresh();}if(!ok){showRootBanner(true);Toast.makeText(SettingsActivity.this,"Could not save the surface style",Toast.LENGTH_SHORT).show();load();}}});}});
    }
    private void labelSize(LinearLayout group){
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(18),dp(12),dp(18),dp(20));
        TextView meter=text("Loading…",18,mAccent,true);meter.setGravity(Gravity.CENTER);box.addView(meter,new LinearLayout.LayoutParams(-1,-2));
        mChoiceLabels.put(LauncherFeatures.LABEL_LEVEL,meter);
        mChoiceItems.put(LauncherFeatures.LABEL_LEVEL,new String[]{"Smallest","Small","Medium","Large","Largest"});
        mChoiceOffsets.put(LauncherFeatures.LABEL_LEVEL,0);
        mLabelSlider=new LabelSizeSlider(this,mAccent,mDark?0xff686970:0xff9d9da5);mLabelSlider.setEnabled(false);
        mLabelSlider.setProgressTintList(ColorStateList.valueOf(mAccent));mLabelSlider.setThumbTintList(ColorStateList.valueOf(mAccent));
        mLabelSlider.setContentDescription("Icon label size, five levels");
        box.addView(mLabelSlider,new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout stops=new LinearLayout(this);stops.setOrientation(0);
        for(int i=0;i<5;i++){final int stop=i;TextView label=text((i+1)+"\n"+IconLabelScale.name(i),13,mTextSecondary,false);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(4),0,dp(8));label.setContentDescription("Level "+(i+1)+", "+IconLabelScale.name(i));label.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(mLabelSlider.isEnabled()){mLabelSlider.setProgress(stop);saveLabelSize(stop);}}});mLabelStops[i]=label;stops.addView(label,new LinearLayout.LayoutParams(0,dp(56),1));}
        box.addView(stops,new LinearLayout.LayoutParams(-1,-2));
        ImageView icon=new ImageView(this);Drawable image=drawable("ic_ps_label_size");if(image!=null){image=image.mutate();image.setTint(mAccent);icon.setImageDrawable(image);}
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(48),dp(48));ip.gravity=Gravity.CENTER_HORIZONTAL;ip.topMargin=dp(16);box.addView(icon,ip);
        mLabelPreview=text("App name",16*.85f,mText,false);mLabelPreview.setGravity(Gravity.CENTER);mLabelPreview.setPadding(0,dp(8),0,dp(8));box.addView(mLabelPreview,new LinearLayout.LayoutParams(-1,-2));
        TextView note=text("Changes only the text under icons.\nIcon size and shape stay the same.",13,mTextSecondary,false);note.setGravity(Gravity.CENTER);box.addView(note,new LinearLayout.LayoutParams(-1,-2));
        mLabelSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar s){}public void onStopTrackingTouch(android.widget.SeekBar s){}public void onProgressChanged(android.widget.SeekBar s,int value,boolean user){showLabelSize(value);if(user&&!mUpdating)saveLabelSize(value);}});
        group.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private void saveLabelSize(final int value){
        showLabelSize(value);mSaves++;
        IO.execute(new Runnable(){public void run(){final boolean ok=Root.run("settings put secure "+LauncherFeatures.LABEL_LEVEL+" "+IconLabelScale.level(value))!=null;mMain.post(new Runnable(){public void run(){if(isFinishing()||isDestroyed())return;if(ok){RestartPill.changed(LauncherFeatures.LABEL_LEVEL);if(mRestart!=null)mRestart.refresh();}if(!ok){showRootBanner(true);Toast.makeText(SettingsActivity.this,"Could not save text size",Toast.LENGTH_SHORT).show();load();}}});}});
    }
    private void choice(LinearLayout group,String key,String title,String[] items){choice(group,key,title,items,0);}
    private void choice(LinearLayout group,final String key,final String title,final String[] items,final int offset){
        LinearLayout row=new LinearLayout(this);row.setOrientation(0);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(18),dp(22),dp(18),dp(22));row.setMinimumHeight(dp(96));
        ImageView icon=new ImageView(this);Drawable d=drawable(folderGridKey(key)?gridIcon(key):Feature.LOCK_SHORTCUTS.equals(key)?"ic_ps_lock_options":"ic_ps_single_tap");if(d!=null){d=d.mutate();d.setTint(mAccent);icon.setImageDrawable(d);}
        LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(28),dp(28));iconParams.rightMargin=dp(18);row.addView(icon,iconParams);mChoiceIcons.put(key,icon);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);labels.addView(text(title,15,mTextSecondary,true));
        TextView value=text("Loading\u2026",18,mAccent,true);value.setPadding(0,dp(8),0,0);labels.addView(value);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("\u203a",28,mAccent,true);arrow.setPadding(dp(12),0,0,0);row.addView(arrow);
        mChoiceLabels.put(key,value);mChoiceItems.put(key,items);mChoiceOffsets.put(key,offset);
        row.setClickable(true);row.setFocusable(true);row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            IO.execute(new Runnable(){public void run(){final int[] current=Root.readSecure(new String[]{key});mMain.post(new Runnable(){public void run(){
                if(isFinishing()||isDestroyed())return;if(current==null){showRootBanner(true);return;}
                showSettingsDialog(new AlertDialog.Builder(SettingsActivity.this).setTitle(title).setSingleChoiceItems(items,Math.max(0,Math.min(items.length-1,current[0]-offset)),new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,final int which){
                    dialog.dismiss();mSaves++;IO.execute(new Runnable(){public void run(){
                        String command="settings put secure "+key+" "+(which+offset);
                        if(Feature.DOUBLE_TAP_WAKE.equals(key))command+="; settings put secure doze_tap_gesture 1";
                        final boolean ok=Root.run(command)!=null;mMain.post(new Runnable(){public void run(){if(isFinishing()||isDestroyed())return;if(ok){updateChoiceLabel(key,which+offset);RestartPill.changed(key);if(mRestart!=null)mRestart.refresh();}else{showRootBanner(true);Toast.makeText(SettingsActivity.this,"Could not save this option",0).show();}}});
                    }});
                }}).setNegativeButton("Cancel",null).create());
            }});}});
        }});group.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    // ------------------------------------------------------------------ data

    private void load() {
        java.util.LinkedHashSet<String> allKeys=new java.util.LinkedHashSet<String>(mSwitches.keySet());
        allKeys.addAll(mChoiceLabels.keySet());if(mStyleSlider!=null)allKeys.add(SuperDark.KEY);
        final String[] keys = allKeys.toArray(new String[0]);
        if(keys.length==0)return;
        final int savesBefore = mSaves;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final int[] values = Root.readSecure(keys);
                if(values!=null)for(int i=0;i<keys.length;i++)if(PrivacyLock.GRACE_KEY.equals(keys[i])){
                    try{values[i]=PrivacyLock.graceOn(android.provider.Settings.Secure.getString(getContentResolver(),PrivacyLock.GRACE_KEY))?1:0;}catch(Throwable ignored){}
                }else if(MiscOptions.defaultOff(keys[i])){
                    try{values[i]="1".equals(android.provider.Settings.Secure.getString(getContentResolver(),keys[i]))?1:0;}catch(Throwable ignored){}
                }else if(PrivacyLock.ENABLED_KEY.equals(keys[i])||PrivacyLock.PROTECT_KEY.equals(keys[i])){
                    try{values[i]=PrivacyLock.onValue(android.provider.Settings.Secure.getString(getContentResolver(),keys[i]))?1:0;}catch(Throwable ignored){}
                }
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        // A switch was flipped while this read ran: its values are stale, and
                        // the save already queued behind it is the truth. Read again after it.
                        if (mSaves != savesBefore && values != null) {
                            load();
                            return;
                        }
                        if (values == null) {
                            showRootBanner(true);
                            for (Switch sw : mSwitches.values()) sw.setEnabled(false);
                            updateMiscPicker();
                            if(mLabelSlider!=null)mLabelSlider.setEnabled(false);
                            if(mStyleSlider!=null)mStyleSlider.setEnabled(false);
                            return;
                        }
                        showRootBanner(false);
                        mUpdating = true;
                        for(int j=0;j<keys.length;j++)if(SuperDark.KEY.equals(keys[j]))mStyleSuperDark=values[j]==1;
                        for (int i = 0; i < keys.length; i++) {
                            Switch sw = mSwitches.get(keys[i]);
                            if(sw!=null){boolean checked=values[i]==1;sSwitchValues.put(keys[i],checked);if(sw.isChecked()!=checked)sw.setChecked(checked);sw.jumpDrawablesToCurrentState();sw.setVisibility(View.VISIBLE);sw.setEnabled(true);}
                            updateChoiceLabel(keys[i],values[i]);
                        }
                        updateMiscPicker();
                        mUpdating = false;
                    }
                });
            }
        });
    }

    private void save(final String key, final boolean on, final Switch sw) {
        if(mPrivacyGate!=null&&!mPrivacyGate.isOpen())return;
        mSaves++;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Root.run(command(key, on)) != null;
                if(ok&&MiscSystem.A11Y_KEY.equals(key)){
                    java.util.List<String> apps=Root.run("settings get secure "+MiscSystem.A11Y_APPS_KEY);
                    if(apps!=null&&!apps.isEmpty())Root.run("true"+MiscOptions.stopCommands(MiscOptions.parse(apps.get(0))));
                }
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (ok) {updateMiscPicker();if(MiscSystem.DEV_KEY.equals(key))Toast.makeText(SettingsActivity.this,"Reopen apps that were already open.",Toast.LENGTH_LONG).show();if(MiscSystem.A11Y_KEY.equals(key))Toast.makeText(SettingsActivity.this,"Changes apply the next time the chosen apps open.",Toast.LENGTH_LONG).show();RestartPill.changed(key); if (mRestart != null) mRestart.refresh(); return; }
                        mUpdating = true;
                        sSwitchValues.put(key,!on);sw.setChecked(!on);   // put the switch back
                        updateMiscPicker();
                        mUpdating = false;
                        showRootBanner(true);
                        Toast.makeText(SettingsActivity.this, "Couldn't save: root access needed",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** The root command that applies one switch. */
    private static String command(String key, boolean on) {
        if(KeyboardBarTweak.KEY.equals(key))return MiscOptions.keyboardCommand(on);
        String put = "settings put secure " + key + " " + (on ? 1 : 0);
        if (Feature.FLASHLIGHT.equals(key) && on) {
            // Same as picking Toggle flashlight in Settings: double press on, target on Camera
            // (that keeps the gesture armed; the module turns it into the flashlight).
            return put + "; settings put secure " + Flashlight.KEY_TARGET + " 0"
                    + "; settings put secure " + Flashlight.KEY_ENABLED + " 1";
        }
        if (Feature.CLEAR_ALL.equals(key)) {
            return put + "; am force-stop " + LAUNCHER;   // the button is built with recents
        }
        if (SuperDark.KEY.equals(key)) return put + "; " + SuperDark.commands(on) + (on ? "" : "; if [ \"$(settings get secure " + SurfaceStyle.KEY + ")\" = 0 ]; then settings put global disable_window_blurs 1; fi; true");
        if (Feature.PHOTOS_DOODLES.equals(key)) return put + "; am force-stop " + PhotosEditor.PACKAGE;
        if (Feature.TAP_CHECK.equals(key) && on) return put + "; settings put secure doze_tap_gesture 1";
        if (Feature.SCREENSHOT_DOODLES.equals(key) || Feature.SCREENSHOT_FREE_CROP.equals(key)) {
            return put + "; am force-stop " + ScreenshotEditor.PACKAGE;   // the editor builds its toolbar once
        }
        return put;
    }

    // ------------------------------------------------------------------ building blocks

    private void row(LinearLayout group, final String key, String icon, int iconColor,
                     String title, String summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(16), dp(16));
        row.setMinimumHeight(dp(72));

        FrameLayout badge = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(mAccent);
        badge.setBackground(circle);
        ImageView iv = new ImageView(this);
        Drawable d = drawable(icon);
        if (d != null) { d = d.mutate(); d.setTint(mOnAccent); iv.setImageDrawable(d); }
        badge.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.rightMargin = dp(16);
        if(MiscSystem.DEV_KEY.equals(key)){
            // Keep the visible badge exactly where it was; the whole icon column is its own button.
            row.setPadding(dp(10),dp(16),dp(16),dp(16));
            FrameLayout target=new FrameLayout(this);target.setMinimumHeight(dp(48));
            target.addView(badge,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
            LinearLayout.LayoutParams targetParams=new LinearLayout.LayoutParams(dp(56),ViewGroup.LayoutParams.MATCH_PARENT);
            targetParams.rightMargin=dp(8);row.addView(target,targetParams);
            target.setClickable(true);target.setFocusable(true);
            target.setContentDescription("Open Developer options");
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.setOnClickListener(new View.OnClickListener(){public void onClick(View v){openDeveloperOptions();}});
            mDeveloperGear=iv;
        }else row.addView(badge, blp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 17, mText, false));
        TextView s = text(summary, 14, mTextSecondary, false);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{mAccent, mTextSecondary}));
        sw.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{(mAccent & 0x00FFFFFF) | 0x66000000, mAppSuperDark ? 0xFF1A1A1A : mDark ? 0xFF49454F : 0xFFCAC4D0}));
        sw.setContentDescription(title);
        Boolean known=sSwitchValues.get(key);
        if(known!=null){sw.setChecked(known);sw.jumpDrawablesToCurrentState();}
        else sw.setVisibility(View.INVISIBLE);
        sw.setEnabled(false);   // until the stored value is loaded
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!mUpdating) {sSwitchValues.put(key,on);save(key, on, sw);updateMiscPicker();}
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(12);
        row.addView(sw, slp);
        mSwitches.put(key, sw);

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) sw.toggle();
            }
        });
        row.setContentDescription(title);

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void startDeveloperGear(){
        if(mDeveloperGear==null||mGearRotation!=null)return;
        float angle=mDeveloperGear.getRotation();
        mGearRotation=android.animation.ObjectAnimator.ofFloat(mDeveloperGear,"rotation",angle,angle+360f);
        mGearRotation.setDuration(14000L);
        mGearRotation.setInterpolator(new android.view.animation.LinearInterpolator());
        mGearRotation.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mGearRotation.start();
    }
    private void stopDeveloperGear(){
        if(mGearRotation==null)return;
        mGearRotation.cancel();mGearRotation=null;
        if(mDeveloperGear!=null)mDeveloperGear.setRotation(mDeveloperGear.getRotation()%360f);
    }
    private void openDeveloperOptions(){
        // Independent of the hiding switch and Android's Developer options setting.
        try{startActivity(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));}
        catch(android.content.ActivityNotFoundException unavailable){
            Toast.makeText(this,"Developer options aren't available on this device.",Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMiscPicker(){
        if(mMiscChooseApps==null)return;
        Switch toggle=mSwitches.get(MiscSystem.A11Y_KEY);
        boolean enabled=toggle!=null&&toggle.isEnabled()&&toggle.isChecked();
        mMiscChooseApps.setEnabled(enabled);mMiscChooseApps.setClickable(enabled);mMiscChooseApps.setAlpha(enabled?1f:.4f);
    }

    private TextView banner() {
        TextView b = text("Pixel Suite needs root access to change these settings. Allow it in KernelSU, then reopen this screen.", 14, mDark ? 0xFFFFB4AB : 0xFF8C1D18, false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(mDark ? 0xFF5C1F1A : 0xFFFCDAD6);
        b.setBackground(bg);
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private static String gridIcon(String key){return LauncherFeatures.DRAWER_GRID_SIZE.equals(key)?"ic_ps_drawer_grid":"ic_ps_home_grid";}
    private static boolean folderGridKey(String key){return LauncherFeatures.GRID_SIZE.equals(key)||LauncherFeatures.DRAWER_GRID_SIZE.equals(key);}
    private void showRootBanner(boolean show) {
        mBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void section(LinearLayout page, String label) {
        TextView t = text(label, 14, mAccent, true);
        t.setPadding(dp(8), dp(16), dp(8), dp(8));
        page.addView(t);
    }

    private LinearLayout group(LinearLayout page) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.bottomMargin=dp(12);
        page.addView(g,layout);
        return g;
    }

    private void finishGroup(LinearLayout g) {
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            float big = dp(24), small = dp(6);
            float top = i == 0 ? big : small;
            float bottom = i == n - 1 ? big : small;
            View row = g.getChildAt(i);
            row.setBackground(rowBackground(top, bottom));
            if (i > 0) {
                ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = dp(3);
            }
        }
    }

    private Drawable rowBackground(float top, float bottom) {
        GradientDrawable shape = AppSurfaces.card(this,24);
        shape.setColor(mCard);
        shape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        int ripple = mDark ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask);
    }

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private void resolvePalette() {
        AppSurfaces.refresh(this);mAppSuperDark=AppSurfaces.active(this);
        if (mDark) {
            mBg = sys("system_surface_container_dark", 0xFF211F26);
            mCard = sys("system_surface_container_high_dark", 0xFF2B2930);
            mText = sys("system_on_surface_dark", 0xFFE6E1E5);
            mTextSecondary = sys("system_on_surface_variant_dark", 0xFFCAC4D0);
            mAccent = sys("system_primary_dark", 0xFFD0BCFF);
            mOnAccent = sys("system_on_primary_dark", 0xFF381E72);
        } else {
            mBg = sys("system_surface_container_light", 0xFFF3EDF7);
            mCard = sys("system_surface_bright_light", 0xFFFEF7FF);
            mText = sys("system_on_surface_light", 0xFF1D1B20);
            mTextSecondary = sys("system_on_surface_variant_light", 0xFF49454F);
            mAccent = sys("system_primary_light", 0xFF6750A4);
            mOnAccent = sys("system_on_primary_light", 0xFFFFFFFF);
        }
        if(mAppSuperDark){mBg=0xff000000;mCard=0xff0b0b0b;}
    }

    private void refreshAppSurfaces(){
        AppSurfaces.refresh(this);boolean now=AppSurfaces.active(this);if(now==mAppSuperDark)return;
        resolvePalette();styleWindow();if(mAppRoot!=null){mAppRoot.setBackgroundColor(mBg);AppSurfaces.invalidateTree(mAppRoot);}
        for(Switch sw:mSwitches.values())sw.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{(mAccent&0xffffff)|0x66000000,mAppSuperDark?0xff1a1a1a:mDark?0xff49454f:0xffcac4d0}));
        for(AlertDialog dialog:new java.util.ArrayList<AlertDialog>(mSettingsDialogs))if(dialog.isShowing())styleSettingsWindow(dialog.getWindow());
        if(mRestart!=null)mRestart.refreshPalette();
    }

    @SuppressWarnings("deprecation")
    private void styleWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(mBg));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        View decor = w.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!mDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= 0x00000010;   // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 26)
        }
        decor.setSystemUiVisibility(flags);
    }

    private int sys(String name, int fallback) {
        try {
            int id = getResources().getIdentifier(name, "color", "android");
            if (id != 0) return getResources().getColor(id, getTheme());
        } catch (Throwable ignored) { }
        return fallback;
    }

    private Drawable drawable(String name) {
        try {
            int id = getResources().getIdentifier(name, "drawable", getPackageName());
            if (id != 0) return getResources().getDrawable(id, getTheme());
        } catch (Throwable ignored) { }
        return null;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int dp(float v) {
        return Math.round(v * mDp);
    }
}
