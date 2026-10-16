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
    private float mDp;
    private int mBg, mCard, mText, mTextSecondary, mAccent, mOnAccent;

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
    /** True while switches are set from stored values, so their listeners don't save. */
    private boolean mUpdating;
    /** Counts saves (main thread), so a reload that started before one doesn't undo it. */
    private int mSaves;
    private android.widget.SeekBar mLabelSlider;
    private TextView mLabelPreview;
    private final TextView[] mLabelStops=new TextView[5];
    private FeaturePreview mFeaturePreview;
    private final java.util.List<FeaturePreview> mPreviews=new java.util.ArrayList<FeaturePreview>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(mDark ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mDp = getResources().getDisplayMetrics().density;
        resolvePalette();
        styleWindow();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(32));

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
        TextView version = text("  " + versionName() + "  \u00b7  RovianDev  ", 12, mAccent, true);
        GradientDrawable chip = FrostedSurface.drawable(this,18);
        version.setBackground(chip); version.setPadding(dp(12),dp(8),dp(12),dp(8));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2,-2); chipParams.leftMargin=dp(8);chipParams.bottomMargin=dp(20);
        if("changelog".equals(pageId))heading.addView(version,chipParams);

        mBanner = banner();
        mBanner.setVisibility(View.GONE);
        page.addView(mBanner);

        buildPage(page);

        final ScrollView scroll = new ScrollView(this);
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
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        ReleaseUpdates.schedule(SettingsActivity.this); ReleaseUpdates.check(SettingsActivity.this,false,null);
        for(FeaturePreview preview:mPreviews)preview.setHostActive(true);
    }

    @Override protected void onPause(){for(FeaturePreview preview:mPreviews)preview.setHostActive(false);super.onPause();}
    @Override protected void onDestroy(){for(AlertDialog dialog:new java.util.ArrayList<AlertDialog>(mSettingsDialogs))dialog.dismiss();for(FeaturePreview preview:mPreviews)preview.dispose();super.onDestroy();}

    private static final class FeaturePage {
        final String group,key,icon,title,summary;
        FeaturePage(String group,String key,String icon,String title,String summary){this.group=group;this.key=key;this.icon=icon;this.title=title;this.summary=summary;}
    }
    private static final FeaturePage[] PAGES={
        new FeaturePage("buttons",Feature.FLASHLIGHT,"ic_ps_flashlight","Power button flashlight","Double-press the power button to toggle the flashlight. Pixel Suite keeps the power gesture enabled and handles the flashlight action. Turning this off returns control to the phone's normal power gesture."),
        new FeaturePage("buttons",Feature.VOLDOWN_CAMERA,"ic_ps_camera","Volume-down camera","Double-press volume down while the Always On Display is showing to open the camera. The gesture stays inactive during calls and while media is playing."),
        new FeaturePage("gestures",Feature.THREE_FINGER,"ic_ps_threefinger","Three-finger screenshot","Swipe down with three fingers to capture a screenshot. The gesture works across apps, subject to Android's restrictions on protected content."),
        new FeaturePage("gestures",ScreenRecordTidy.KEY,"ic_ps_screen_record","Screen recording sheet","Places the X and Start controls below the recording options and hides the long screen-content warning. Recording still starts only when you choose Start. Turn this off to keep the stock layout the next time the prompt opens. Currently targets the English System UI recording prompt."),
        new FeaturePage("gestures",Feature.SCREENSHOT_FREE_CROP,"ic_ps_crop_free","Free screenshot crop","Crop a screenshot to any size in the screenshot editor. The crop frame no longer stops at a minimum width or height, so you can cut a thin strip or a single line of text. Works with any screenshot opened in the editor, whether it came from the buttons, the three-finger gesture or recents."),
        new FeaturePage("gestures",Feature.SCREENSHOT_DOODLES,"ic_ps_doodles","Screenshot doodles","Adds a Doodles tool next to the pen in the screenshot editor. Draw arrows, lines, squares, circles and more in your choice of color and thickness. Drag a shape to move it, or use its handles to resize it before saving. Doodles are saved into the screenshot with the rest of your edits."),
        new FeaturePage("gestures",Feature.DOUBLE_TAP_SLEEP,"ic_ps_sleep","Double tap to sleep","Double-tap empty space on the home screen to lock your phone. Apps, folders and normal launcher interactions keep their usual actions."),
        new FeaturePage("gestures",Feature.TAP_CHECK,"ic_ps_tapwake","Tap to wake","Choose a single tap or a double tap to wake the screen. Pocket protection prevents waking while the front sensor is covered. This also enables double-tap to sleep on the lock screen. Choose how lock-screen taps behave below."),
        new FeaturePage("launcher",LauncherFeatures.GRID,"ic_ps_folder_grid","Folder grid","Choose room for 9, 16 or 25 apps per folder page, with Pixel Launcher's own folder animations and frosted background. Use the three-dot menu in each folder to change its grid, sort its apps, or choose Add apps to search and select its contents. Folders fit their contents: a 4×4 folder with 11 apps uses three rows instead of an empty fourth row. Removing a folder includes a brief scatter animation; the apps stay installed. System settings that disable animations are respected."),
        new FeaturePage("drawer",LauncherFeatures.DRAWER,"ic_ps_drawer_folder","App drawer folders","Hold an app and choose Create folder, or drag one app onto the middle of another; a folder-colored highlight shows when the drop will make or fill a folder. Holding a folder opens Rename, Sort and Remove. Sort has two choices with a check mark: Auto (A–Z) asks first, then keeps the folder in alphabetical order; Custom lets you arrange apps freely by dragging them inside the folder, and keeps that order across updates and restarts. Dragging an app against the left or right edge of an open folder switches its page, and an app leaves the folder after staying outside it for 250 ms. A Remove target beside Add to home screen takes selected apps out of a drawer folder without uninstalling them. Holding a folder for 1.5 seconds, or moving your finger, lets you move it. The three-dot menu inside a folder has Add apps (searchable), Sort (Auto A–Z or Custom), and the grid size. In Custom mode, drawer-folder apps make room as you drag, and apps selected together land as one group. Drag above the top edge of the search bar to move to the home screen. Apps outside folders are always sorted A to Z, then Arabic, other languages, and names starting with numbers or symbols. Removing a folder returns its apps to the drawer, and drawer folders close when you leave the drawer or lock the phone. Private Space is excluded."),
        new FeaturePage("drawer",LauncherFeatures.DRAG_PILL,"ic_ps_home_drop","Drag to home screen","Hold an app in the drawer to reveal a frosted Add to home screen target at the top. Drag onto the target to continue to the home screen; sideways and downward movement stays in the drawer, and scrolling the drawer never starts a drag. With App drawer folders enabled, dropping apps together creates folders."),
        new FeaturePage("launcher",LauncherFeatures.ICONS,"ic_ps_change_icon","Change icons","Hold an app and choose Change icon. Select an image through Photos or Files. It is cropped to a square, fitted to the normal launcher icon size and shaped by Pixel Launcher. Changes apply across home, drawer, folders, app search and the Google search app. Choose Change icon again to restore the original. Only the selected image is copied into launcher-private storage; it is not uploaded."),
        new FeaturePage("launcher",FolderDrag.TWO_FINGER,"ic_ps_two_finger","Two-finger drag","While you hold an app with one finger, swipe with a second finger to change pages: on the home screen, inside any folder, or in the app drawer (up and down to scroll, with momentum when you lift the scrolling finger). Keep holding the app while you navigate, then drop it onto a valid destination. The held app stays under your first finger. Works with home-screen folders and app drawer folders. A second-finger touch stops a coasting drawer. When the held apps came from a drawer folder, the drawer behind it stays still; taps can still add apps from that folder."),
        new FeaturePage("launcher",MultiDrag.KEY,"ic_ps_multi_drag","Move several apps","Hold an app and start moving it, then tap other apps with another finger to pick them up too. Each app flies to the one you are holding and joins a stack, with a badge counting how many apps you are moving. Works on the home screen, in folders and in the app drawer. Drop the selected apps together into a folder or onto a home-screen page, where they fill the free spaces nearest your finger. Use Remove to take selected apps out of a drawer folder. Apps from the drawer are added to the home screen as usual; home screen apps move. Picked icons follow your system icon shape. Drop several home-screen apps on Remove to remove their shortcuts together; the apps stay installed. During multi-app dragging, Uninstall is hidden and Remove moves to the center. When a drag starts inside a folder, you can select more apps from that same folder. In the main drawer, the suggested-app row and apps already inside folders are excluded to avoid duplicate selections. If you cancel, they fly back."),
        new FeaturePage("launcher",LauncherFeatures.RENAME,"ic_ps_rename","Rename apps","Hold an app and choose Rename. Saving updates its name immediately across home icons, folders, the drawer, launcher app search and the Google search app. Reset restores its original name. Names in other apps remain unchanged, and work-profile names are kept separate."),
        new FeaturePage("launcher",LauncherFeatures.LABEL_SIZE,"ic_ps_label_size","Icon label size","Choose one of five text sizes for home-screen and app-drawer labels, including closed folder names. Smaller text fits more letters in the same space. Medium is the default, with comfortably smaller text than stock. Icon sizes and labels inside opened folders stay unchanged. Disabled by default. Enable it to apply your chosen size immediately; turning it off restores stock text size."),
        new FeaturePage("launcher",Feature.CLEAR_ALL,"ic_ps_clearall","Clear all in recents","Moves the stock Clear all option from the far left of recents and keeps it always visible alongside Select and Screenshot. You can clear recent apps without scrolling to the end of the list. Changing this option restarts Pixel Launcher to rebuild the recents controls."),
        new FeaturePage("drawer",LauncherFeatures.HIDE_SHORTCUTS,"ic_ps_shortcuts","Hide app shortcuts","Hide app-provided actions such as Amazon Search and View orders when holding an app in the app drawer. The standard launcher actions and Pixel Suite options remain available. Home-screen shortcuts are unchanged. This takes effect the next time you hold an app, without restarting Pixel Launcher."),
        new FeaturePage("files",Feature.FILES_SORT,"ic_ps_files","Newest first","Show folders first, followed by files from newest to oldest, in Files by Google and in Android's file picker (the Files screen other apps open when you attach a file). While enabled, this overrides each app's own Sort by choice. The file picker is a separate system app, also named Files: in LSPosed, open Pixel Suite's scope, show system apps, tick com.google.android.documentsui, then force stop it. Turn this off to use the apps' normal sorting.")
    };
    private final Map<String,TextView> mChoiceLabels=new LinkedHashMap<String,TextView>();
    private final Map<String,Integer> mChoiceOffsets=new LinkedHashMap<String,Integer>();
    private final Map<String,ImageView> mChoiceIcons=new LinkedHashMap<String,ImageView>();
    private final Map<String,String[]> mChoiceItems=new LinkedHashMap<String,String[]>();
    private FeaturePage feature(String id){for(FeaturePage f:PAGES)if(f.key.equals(id))return f;return null;}
    private String pageTitle(String id){
        FeaturePage f=feature(id);if(f!=null)return f.title;
        if("buttons".equals(id))return "Buttons";if("gestures".equals(id))return "Gestures";
        if("drawer".equals(id))return "App drawer";if("launcher".equals(id))return "Pixel Launcher";if("files".equals(id))return "Files";
        if("changelog".equals(id))return "Changelog";
        if("updates".equals(id))return "Updates";if("about".equals(id))return "About and support";return "Pixel Suite";
    }
    private String pageSubtitle(String id){
        if(feature(id)!=null)return "Make it work your way.";
        if("buttons".equals(id))return "Quick actions from your phone's buttons.";
        if("gestures".equals(id))return "Capture, mark up, lock and wake.";
        if("drawer".equals(id))return "Folders, dragging and app shortcuts.";if("launcher".equals(id))return "Folders, names, icons and recents.";
        if("files".equals(id))return "A better starting view for your files.";
        if("changelog".equals(id))return "What's new:";
        if("updates".equals(id))return "";if("about".equals(id))return "Made by RovianDev";return "Your Pixel. Your Way.";
    }
    private void openPage(String id){startActivity(new Intent(this,SettingsActivity.class).putExtra("page",id));}
    private void buildPage(LinearLayout page){
        String id=getIntent().getStringExtra("page");FeaturePage f=feature(id);
        if(f!=null){
            LinearLayout controls=group(page);row(controls,f.key,f.icon,mAccent,(LauncherFeatures.HIDE_SHORTCUTS.equals(f.key)?"Hide app shortcuts":LauncherFeatures.ICONS.equals(f.key)?"Use custom app icons":LauncherFeatures.LABEL_SIZE.equals(f.key)?"Customize icon labels":"Use "+f.title.toLowerCase(java.util.Locale.ROOT)),"Enable this feature.");finishGroup(controls);
            section(page,"How it works");explanation(page,f.summary);
            if(!LauncherFeatures.LABEL_SIZE.equals(f.key))featurePreview(page,f);
            if(LauncherFeatures.DRAWER.equals(f.key))extraPreview(page,"folder_reorder","Live folder reordering");
            if(LauncherFeatures.GRID.equals(f.key))extraPreview(page,"folder_sort","Folder sorting");
            if(LauncherFeatures.LABEL_SIZE.equals(f.key)){
                section(page,"Text size");LinearLayout options=group(page);labelSize(options);finishGroup(options);
                section(page,"Choose your size");
                explanation(page,"1 · Smallest\nFits the most letters. Best when you prefer compact labels.\n\n2 · Small\nMore room for longer app names while keeping text easy to read.\n\n3 · Medium (Default)\nThe recommended everyday balance, with smaller text than stock.\n\n4 · Large\nEasier to read, with less space for long names.\n\n5 · Largest\nThe biggest text. Long names may be shortened sooner.");
                explanation(page,"Drag the slider or tap a numbered level. The preview and size readout update as you move, and the selected size is saved automatically. Home-screen, drawer and closed-folder labels update without restarting the launcher.\n\nOnly text size changes; app icons keep their size and shape. Opened-folder labels, app names in searches and menu text keep their own styling. Smaller letters fit more of a name into the same space; especially long names can still end with an ellipsis. This changes how names are displayed, not the names themselves.\n\nTurn Customize icon labels off to restore stock text size and spacing. Your chosen level is remembered for next time. The existing system font, Bold text setting and display preferences are preserved. This feature adjusts size only and does not replace the font or force regular weight. The preview follows the current light or dark theme.");
                section(page,"Different from Pixel settings");
                explanation(page,"Pixel Suite changes only the font size of labels beneath launcher icons, without resizing the icons themselves or changing text throughout your phone.\n\nFor the original system-wide font control, open Settings > Display & touch > Display size and text, then adjust Font size. The separate Display size control on that screen makes interface items larger or smaller. Pixel Suite leaves both system controls unchanged and applies your chosen label size relative to the launcher’s current text size.");
            }
            if(Feature.TAP_CHECK.equals(f.key)){
                section(page,"Wake the screen");LinearLayout wake=group(page);
                choice(wake,Feature.DOUBLE_TAP_WAKE,"Wake gesture",new String[]{"Single tap","Double tap"});finishGroup(wake);
                explanation(page,"Double tap uses the touchscreen while the phone is held, and the motion sensor while it is still. If a double tap is missed, one more tap within three seconds is a backup. Touchscreen use is limited to avoid running continuously overnight. Battery use and reliability depend on movement and the device.");
                section(page,"Lock-screen taps");LinearLayout lock=group(page);
                choice(lock,Feature.LOCK_SHORTCUTS,"Lock-screen behavior",new String[]{"Modded behavior","Stock behavior"});finishGroup(lock);
                explanation(page,"Modded behavior lets you double-tap the alarm, weather or date area to sleep without opening those cards, and removes tap vibration. Stock behavior keeps those cards tappable and keeps vibration; a double tap on a card can open it instead of sleeping.");
            }
            if(LauncherFeatures.DRAWER.equals(f.key))explanation(page,"Folders need at least two available apps, and each app can be in only one drawer folder. Turning this feature off shows the ordinary drawer and keeps your folder definitions for when you enable it again.");
            if(LauncherFeatures.GRID.equals(f.key))explanation(page,"Choose 3x3, 4x4 or 5x5. Home-screen and drawer folders keep separate grid choices. The 4×4 layout uses one line for labels; long names may end with an ellipsis. The 5×5 layout fits up to 25 apps per page with spacing adjusted for the available screen area. Folder blur and tint keep their existing appearance.");
            if(Feature.SCREENSHOT_FREE_CROP.equals(f.key)||Feature.SCREENSHOT_DOODLES.equals(f.key)){
                section(page,"Pixel Studio setup");
                explanation(page,"These tools work in Pixel Studio when it opens a screenshot for editing. In LSPosed > Pixel Suite > Scope, select Pixel Studio (com.google.android.apps.pixel.creativeassistant), then restart Pixel Studio. They do not modify other image editors. The hooks target Pixel Studio 2.3.001 (version code 192285); another version may not support these tools. Each feature starts enabled, and you can turn it off separately here.\n\nSave or discard your current edits before changing either toggle: changing it closes Pixel Studio, so its toolbar can rebuild when you open the editor again.");
            }
            if(Feature.SCREENSHOT_FREE_CROP.equals(f.key))explanation(page,"The crop frame can shrink down to a single pixel. Its edges keep a larger touch target so thin crops remain adjustable. Final output still follows the editor's image and save limits.");
            if(Feature.SCREENSHOT_DOODLES.equals(f.key))explanation(page,"Choose an arrow, double arrow, line, square, circle, triangle, star or speech bubble. Tap to place a shape, or drag to draw it. Drag a shape to move it, drag its handles to resize it, or pinch to scale it. Choose a color and thickness; closed shapes also support fill. Your shapes follow zoom, pan and crop, and are included when you save, share or make a copy.\n\nSwitching shape buttons converts the selected shape, including between arrows and closed shapes. Doodles starts on the arrow each time you open it. Drag the top handle to reposition the menu; it hides while you draw, move, resize or pinch a shape, then returns when you release.\n\nThe editor's Undo and Redo buttons also handle Doodles changes on supported Pixel Studio versions, keeping up to 100 steps. Doodles changes are undone before native editor edits; they do not share one chronological history. If these hooks are unavailable, the editor keeps its normal Undo and Redo behavior. Use the Doodles delete control to remove a shape; the editor's eraser cannot erase it. Save before leaving the editing session to keep your shapes.");
            if(LauncherFeatures.ICONS.equals(f.key))explanation(page,"Turning this off restores the original icons while keeping your selected images for later. Private Space is excluded.");
            return;
        }
        if("updates".equals(id)){buildUpdates(page);return;}
        if("about".equals(id)){
            explanation(page,"All Pixel Suite controls live in this app. New launcher options update immediately. To get back here from the home screen, long-press an empty spot and choose Pixel Suite settings. Android's own Settings keeps its stock menus.\n\nRoot access is required to save the module's settings. Enable Pixel Suite in LSPosed with its recommended scope.");
            section(page,"Community and support");communityActions(page,false);
            return;
        }
        if("changelog".equals(id)){
            explanation(page,"The latest release notes are included with this build and work offline.");
            LinearLayout history=group(page);navigation(history,"Full changelog on GitHub","Complete feature lists, fixes and release history","ic_ps_changelog",new Runnable(){public void run(){startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/RovianDev/PixelSuite/releases")));}});finishGroup(history);
            String latest=Changelog.read(this);int next=latest.indexOf("\n## ");if(next>=0)latest=latest.substring(0,next);String[] entries=latest.split("(?m)^## ");
            for(String entry:entries){if(entry.trim().isEmpty())continue;int end=entry.indexOf('\n');String heading=end<0?entry.trim():entry.substring(0,end).trim();String body=end<0?"":entry.substring(end+1).trim();section(page,heading);TextView notes=text(body,15,mText,false);notes.setTextIsSelectable(true);notes.setLineSpacing(dp(4),1);notes.setPadding(dp(18),dp(16),dp(18),dp(18));notes.setBackground(FrostedSurface.drawable(this,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);page.addView(notes,lp);}
            return;
        }
        boolean category="buttons".equals(id)||"gestures".equals(id)||"launcher".equals(id)||"drawer".equals(id)||"files".equals(id);
        if(category){
            LinearLayout features=group(page);if("launcher".equals(id))navigation(features,"App drawer","Folders, drag target and app shortcuts","ic_ps_drawer",new Runnable(){public void run(){openPage("drawer");}});for(final FeaturePage item:PAGES)if(item.group.equals(id))navigation(features,item.title,item.summary,item.icon,new Runnable(){public void run(){openPage(item.key);}});finishGroup(features);return;
        }
        section(page,"Personalize");LinearLayout categories=group(page);
        navigation(categories,"Buttons","Flashlight and camera shortcuts","ic_ps_buttons",new Runnable(){public void run(){openPage("buttons");}});
        navigation(categories,"Gestures","Screenshots, doodles, sleep and wake","ic_ps_gestures",new Runnable(){public void run(){openPage("gestures");}});
        navigation(categories,"Pixel Launcher","Folder grids, drawer folders, icons and names","ic_ps_launcher",new Runnable(){public void run(){openPage("launcher");}});
        navigation(categories,"Files","Files and the file picker, newest first","ic_ps_files_category",new Runnable(){public void run(){openPage("files");}});finishGroup(categories);
        section(page,"Pixel Suite");LinearLayout about=group(page);navigation(about,"Changelog","Release notes, available offline","ic_ps_changelog",new Runnable(){public void run(){openPage("changelog");}});navigation(about,"Updates","Check official releases and manage notifications","ic_ps_update",new Runnable(){public void run(){openPage("updates");}});navigation(about,"About and support","Setup, community and support links","ic_ps_info",new Runnable(){public void run(){openPage("about");}});finishGroup(about);
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
            TextView label=text(CommunityLinks.NAMES[index],12,mText,true);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setMaxWidth(dp(100));label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);button.addView(label,new LinearLayout.LayoutParams(dp(68),-2));
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
        GradientDrawable tint=new GradientDrawable();tint.setColor((mCard & 0x00ffffff)|(blur==null?0xff000000:0xcc000000));tint.setCornerRadius(dp(24));
        Drawable fill=blur==null?tint:new android.graphics.drawable.LayerDrawable(new Drawable[]{blur,tint});
        GradientDrawable mask=new GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadius(dp(24));
        button.setBackground(new android.graphics.drawable.InsetDrawable(new RippleDrawable(ColorStateList.valueOf(mDark?0x33FFFFFF:0x22000000),fill,mask),0,dp(2),0,dp(2)));
    }
    private ImageView brandIcon(int index){
        ImageView icon=new ImageView(this);icon.setImageDrawable(drawable(CommunityLinks.ICONS[index]));icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setPadding(dp(10),dp(10),dp(10),dp(10));GradientDrawable bg=new GradientDrawable();bg.setColor(CommunityLinks.BADGES[index]);bg.setCornerRadius(dp(16));icon.setBackground(bg);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return icon;
    }
    private void communityActions(LinearLayout page,boolean compact){
        compact=compact&&getResources().getConfiguration().fontScale<=1.3f&&getResources().getConfiguration().screenWidthDp>=340;
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(-1,-2);outer.bottomMargin=dp(12);page.addView(actions,outer);
        int[] order={0,2,1};for(int position=0;position<order.length;position++){
            final int index=order[position];int i=index;LinearLayout button=new LinearLayout(this);button.setOrientation(compact?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);button.setGravity(compact?Gravity.CENTER:Gravity.CENTER_VERTICAL);button.setPadding(dp(compact?8:18),dp(16),dp(compact?8:18),dp(16));button.setMinimumHeight(dp(compact?104:80));button.setBackground(rowBackground(dp(24),dp(24)));
            LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(48),dp(48));if(compact)iconParams.bottomMargin=dp(10);else iconParams.rightMargin=dp(16);button.addView(brandIcon(i),iconParams);
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);TextView name=text(CommunityLinks.NAMES[i],compact?13:18,mText,false);if(compact){name.setGravity(Gravity.CENTER);name.setMinLines(2);}labels.addView(name);
            if(!compact){TextView caption=text(CommunityLinks.CAPTIONS[i],14,mTextSecondary,false);caption.setPadding(0,dp(4),0,0);labels.addView(caption);}button.addView(labels,new LinearLayout.LayoutParams(compact?-1:0,-2,compact?0:1));
            button.setClickable(true);button.setFocusable(true);button.setContentDescription(CommunityLinks.NAMES[i]+". "+CommunityLinks.CAPTIONS[i]+". Opens an external app.");button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){CommunityLinks.open(SettingsActivity.this,index);}});
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(compact?0:-1,-2,compact?1:0);if(compact){if(position>0)bp.leftMargin=dp(4);if(position<2)bp.rightMargin=dp(4);}else if(position>0)bp.topMargin=dp(8);actions.addView(button,bp);
        }
    }
    private void featurePreview(LinearLayout page,FeaturePage feature){
        section(page,"Feature preview");LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(FrostedSurface.drawable(this,24));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(16);page.addView(card,cp);
        mFeaturePreview=new FeaturePreview(this,feature.key,mAccent,mOnAccent,mText,mTextSecondary,mCard,mDark);mFeaturePreview.setContentDescription(feature.title+". Looping demonstration with a pause between actions. Does not change your settings.");card.addView(mFeaturePreview,new LinearLayout.LayoutParams(-1,dp(300)));mPreviews.add(mFeaturePreview);
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
        explanation(page,"Automatic checks run about once a day when Android allows network work, and when you open Pixel Suite (at most once every six hours). They are not instant push messages. No account, GitHub token, root access or device information is sent; GitHub receives the normal network request.");
        final android.widget.Switch automatic=new android.widget.Switch(this);automatic.setText("Automatic release checks");android.graphics.drawable.Drawable updateIcon=drawable("ic_ps_update");if(updateIcon!=null){updateIcon=updateIcon.mutate();updateIcon.setTint(mAccent);updateIcon.setBounds(0,0,dp(24),dp(24));automatic.setCompoundDrawables(updateIcon,null,null,null);automatic.setCompoundDrawablePadding(dp(12));}automatic.setTextColor(mText);automatic.setChecked(ReleaseUpdates.enabled(this));automatic.setPadding(dp(18),dp(16),dp(18),dp(16));page.addView(automatic);
        automatic.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(android.widget.CompoundButton v,boolean checked){ReleaseUpdates.prefs(SettingsActivity.this).edit().putBoolean("enabled",checked).apply();ReleaseUpdates.schedule(SettingsActivity.this);if(checked)ReleaseUpdates.check(SettingsActivity.this,false,null);}});
        final TextView status=text(ReleaseUpdates.status(this),15,mTextSecondary,false);mUpdateStatus=status;status.setPadding(dp(18),dp(12),dp(18),dp(16));page.addView(status);mUpdateProgress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);mUpdateProgress.setMax(100);mUpdateProgress.setVisibility(View.GONE);page.addView(mUpdateProgress,new LinearLayout.LayoutParams(-1,dp(8)));
        LinearLayout actions=group(page);
        navigation(actions,"Check now","Verify both repositories","ic_ps_update",new Runnable(){public void run(){status.setText("Checking both repositories…");ReleaseUpdates.check(SettingsActivity.this,true,new ReleaseUpdates.Result(){public void done(String message){if(!isDestroyed())status.setText(message);}});}});
        navigation(actions,"Allow update notifications","Enable notifications in Android","ic_ps_update",new Runnable(){public void run(){if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},201);else{Intent settings=new Intent("android.settings.APP_NOTIFICATION_SETTINGS");settings.putExtra("android.provider.extra.APP_PACKAGE",getPackageName());try{startActivity(settings);}catch(Exception unavailable){android.widget.Toast.makeText(SettingsActivity.this,"Open Android app settings to manage notifications.",0).show();}}}});
        navigation(actions,"Download and install","Get the verified update directly in Pixel Suite","ic_ps_update",new Runnable(){public void run(){beginUpdateInstall();}});
        finishGroup(actions);
    }
    /** All module-owned settings choices use the exact page palette, not launcher folder frost. */
    private final java.util.List<AlertDialog> mSettingsDialogs=new java.util.ArrayList<AlertDialog>();
    private void showSettingsDialog(final AlertDialog dialog){
        dialog.setCancelable(true);dialog.setCanceledOnTouchOutside(true);dialog.show();mSettingsDialogs.add(dialog);dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){mSettingsDialogs.remove(dialog);}});
        Window window=dialog.getWindow();if(window==null)return;
        Drawable background=window.getDecorView().getBackground();
        if(background!=null){background=background.mutate();background.setColorFilter(mBg,android.graphics.PorterDuff.Mode.SRC_IN);window.setBackgroundDrawable(background);}
        else{GradientDrawable fill=new GradientDrawable();fill.setColor(mBg);fill.setCornerRadius(dp(28));window.setBackgroundDrawable(fill);}
        // Keep the page itself at its existing color while the equal-colored popup is open.
        window.setDimAmount(0f);
        for(String name:new String[]{"parentPanel","topPanel","contentPanel","buttonPanel"}){
            int id=getResources().getIdentifier(name,"id","android");View panel=window.getDecorView().findViewById(id);if(panel!=null)panel.setBackgroundColor(Color.TRANSPARENT);
        }
        colorSettingsDialog(window.getDecorView());
        if(dialog.getListView()!=null)dialog.getListView().setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){public void onChildViewAdded(View parent,View child){colorSettingsDialog(child);}public void onChildViewRemoved(View parent,View child){}});
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL})if(dialog.getButton(which)!=null)dialog.getButton(which).setTextColor(mAccent);
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
        GradientDrawable bg=FrostedSurface.drawable(this,20);t.setBackground(bg);
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
        ImageView icon=mChoiceIcons.get(key);
        if(icon!=null){
            String name=folderGridKey(key)?gridIcon(key):Feature.LOCK_SHORTCUTS.equals(key)?"ic_ps_lock_options":value==1?"ic_ps_double_tap":"ic_ps_single_tap";
            Drawable d=drawable(name);if(d!=null){d=d.mutate();d.setTint(mAccent);icon.setImageDrawable(d);}
        }
    }
    private void showLabelSize(int value){
        int level=IconLabelScale.level(value);
        TextView meter=mChoiceLabels.get(LauncherFeatures.LABEL_LEVEL);
        if(meter!=null)meter.setText("Level "+(level+1)+"  ·  "+IconLabelScale.name(level)+(level==2?" (Default)":""));
        if(mLabelPreview!=null)mLabelPreview.setTextSize(16*IconLabelScale.factor(level));
        for(int i=0;i<5;i++)if(mLabelStops[i]!=null){mLabelStops[i].setTextColor(i==level?mAccent:mTextSecondary);mLabelStops[i].setTypeface(null,i==level?Typeface.BOLD:Typeface.NORMAL);}
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
        ImageView icon=new ImageView(this);Drawable image=drawable("ic_ps_launcher");if(image!=null){image=image.mutate();image.setTint(mAccent);icon.setImageDrawable(image);}
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(48),dp(48));ip.gravity=Gravity.CENTER_HORIZONTAL;ip.topMargin=dp(16);box.addView(icon,ip);
        mLabelPreview=text("Play Store",16*.85f,mText,false);mLabelPreview.setGravity(Gravity.CENTER);mLabelPreview.setPadding(0,dp(8),0,dp(8));box.addView(mLabelPreview,new LinearLayout.LayoutParams(-1,-2));
        TextView note=text("Changes only the text beneath icons.\nIcon size and shape stay unchanged.",13,mTextSecondary,false);note.setGravity(Gravity.CENTER);box.addView(note,new LinearLayout.LayoutParams(-1,-2));
        mLabelSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar s){}public void onStopTrackingTouch(android.widget.SeekBar s){}public void onProgressChanged(android.widget.SeekBar s,int value,boolean user){showLabelSize(value);if(user&&!mUpdating)saveLabelSize(value);}});
        group.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private void saveLabelSize(final int value){
        showLabelSize(value);mSaves++;
        IO.execute(new Runnable(){public void run(){final boolean ok=Root.run("settings put secure "+LauncherFeatures.LABEL_LEVEL+" "+IconLabelScale.level(value))!=null;mMain.post(new Runnable(){public void run(){if(isFinishing()||isDestroyed())return;if(!ok){showRootBanner(true);Toast.makeText(SettingsActivity.this,"Could not save text size",Toast.LENGTH_SHORT).show();load();}}});}});
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
                        final boolean ok=Root.run(command)!=null;mMain.post(new Runnable(){public void run(){if(isFinishing()||isDestroyed())return;if(ok)updateChoiceLabel(key,which+offset);else{showRootBanner(true);Toast.makeText(SettingsActivity.this,"Could not save this option",0).show();}}});
                    }});
                }}).setNegativeButton("Cancel",null).create());
            }});}});
        }});group.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    // ------------------------------------------------------------------ data

    private void load() {
        java.util.LinkedHashSet<String> allKeys=new java.util.LinkedHashSet<String>(mSwitches.keySet());
        allKeys.addAll(mChoiceLabels.keySet());
        final String[] keys = allKeys.toArray(new String[0]);
        if(keys.length==0)return;
        final int savesBefore = mSaves;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final int[] values = Root.readSecure(keys);
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
                            if(mLabelSlider!=null)mLabelSlider.setEnabled(false);
                            return;
                        }
                        showRootBanner(false);
                        mUpdating = true;
                        for (int i = 0; i < keys.length; i++) {
                            Switch sw = mSwitches.get(keys[i]);
                            if(sw!=null){sw.setChecked(values[i] == 1);sw.setEnabled(true);}
                            updateChoiceLabel(keys[i],values[i]);
                        }
                        mUpdating = false;
                    }
                });
            }
        });
    }

    private void save(final String key, final boolean on, final Switch sw) {
        mSaves++;
        IO.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Root.run(command(key, on)) != null;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok || isFinishing() || isDestroyed()) return;
                        mUpdating = true;
                        sw.setChecked(!on);   // put the switch back
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
        row.addView(badge, blp);

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
                new int[]{(mAccent & 0x00FFFFFF) | 0x66000000, mDark ? 0xFF49454F : 0xFFCAC4D0}));
        sw.setContentDescription(title);
        sw.setEnabled(false);   // until the stored value is loaded
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!mUpdating) save(key, on, sw);
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

    private TextView banner() {
        TextView b = text("Pixel Suite needs root access to change these switches. Allow it "
                + "in KernelSU, then reopen this screen.", 14, mDark ? 0xFFFFB4AB : 0xFF8C1D18, false);
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
        GradientDrawable shape = FrostedSurface.drawable(this,24);
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
