package io.github.pixelsuite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.Executor;

final class MiscAppPicker {
    interface Presenter { void show(AlertDialog dialog); }
    private final Activity activity;
    private final Executor io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int text, secondary, accent;
    private final ArrayList<Entry> all = new ArrayList<Entry>(), visible = new ArrayList<Entry>();
    private final Set<String> selected = new TreeSet<String>();
    private Set<String> original = new TreeSet<String>();
    private final android.util.LruCache<String,Drawable> icons = new android.util.LruCache<String,Drawable>(40);
    private EditText search;
    private TextView status;
    private Spinner filter;
    private ListView list;
    private String newestSelected;
    private AlertDialog dialog;
    private AppsAdapter adapter;
    private boolean loaded, saving;
    private static final class Entry {
        final ApplicationInfo info; final String label; final boolean system;
        Entry(ApplicationInfo info, String label) { this.info=info;this.label=label;system=(info.flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0; }
    }
    private MiscAppPicker(Activity activity, Executor io, int text, int secondary, int accent) {
        this.activity=activity;this.io=io;this.text=text;this.secondary=secondary;this.accent=accent;
    }
    static void show(Activity activity, Executor io, int text, int secondary, int accent, Presenter presenter) {
        new MiscAppPicker(activity,io,text,secondary,accent).open(presenter);
    }
    private int dp(int n) { return Math.round(n*activity.getResources().getDisplayMetrics().density); }
    private boolean live() { return !activity.isFinishing()&&!activity.isDestroyed()&&dialog.isShowing(); }
    private void open(Presenter presenter) {
        LinearLayout box=new LinearLayout(activity);box.setOrientation(1);box.setPadding(dp(18),0,dp(18),0);
        search=new EditText(activity);search.setBackground(AppSurfaces.field(activity,12));search.setSingleLine(true);search.setHint("Search apps");search.setTextColor(text);search.setHintTextColor(secondary);search.setContentDescription("Search installed apps");box.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        filter=new Spinner(activity);
        ArrayAdapter<String> choices=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"User apps","System apps","All apps"}) {
            @Override public View getView(int pos,View view,ViewGroup parent){View v=super.getView(pos,view,parent);if(v instanceof TextView)((TextView)v).setTextColor(text);return v;}
            @Override public View getDropDownView(int pos,View view,ViewGroup parent){View v=super.getDropDownView(pos,view,parent);if(v instanceof TextView)((TextView)v).setTextColor(text);v.setBackground(AppSurfaces.menu(activity,4));return v;}
        };
        filter.setAdapter(choices);filter.setContentDescription("Filter installed apps");box.addView(filter,new LinearLayout.LayoutParams(-1,dp(48)));
        status=new TextView(activity);status.setTextColor(secondary);status.setText("Loading apps…");status.setPadding(0,dp(6),0,dp(6));box.addView(status);
        list=new ListView(activity);list.setDivider(null);adapter=new AppsAdapter();list.setAdapter(adapter);
        int listHeight=Math.min(dp(330),Math.max(dp(140),activity.getResources().getDisplayMetrics().heightPixels-dp(420)));
        box.addView(list,new LinearLayout.LayoutParams(-1,listHeight));
        dialog=new AlertDialog.Builder(activity).setTitle("Choose apps").setView(box).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        presenter.show(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){public void onClick(View v){save();}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){refresh();}public void afterTextChanged(Editable e){}});
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){refresh();}public void onNothingSelected(AdapterView<?> p){}});
        io.execute(new Runnable(){public void run(){
            try {
                final List<String> value=Root.run("settings get secure "+MiscSystem.A11Y_APPS_KEY);
                if(value==null||value.isEmpty())throw new IllegalStateException("Root unavailable");
                final Set<String> saved=MiscOptions.parse(value.get(0));
                final ArrayList<Entry> entries=new ArrayList<Entry>();PackageManager pm=activity.getPackageManager();
                for(ApplicationInfo app:pm.getInstalledApplications(0)){String label;try{label=app.loadLabel(pm).toString();}catch(Throwable ignored){label=app.packageName;}entries.add(new Entry(app,label));}
                final Collator collator=Collator.getInstance();Collections.sort(entries,new Comparator<Entry>(){public int compare(Entry a,Entry b){int n=collator.compare(a.label,b.label);return n!=0?n:a.info.packageName.compareTo(b.info.packageName);}});
                main.post(new Runnable(){public void run(){if(!live())return;all.addAll(entries);original=new TreeSet<String>(saved);selected.addAll(saved);loaded=true;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);refresh();}});
            }catch(Throwable error){main.post(new Runnable(){public void run(){if(live())status.setText("Couldn't load apps. Check root access and reopen.");}});}
        }});
    }
    private void refresh() {
        if(!loaded)return;
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);int kind=filter.getSelectedItemPosition();visible.clear();
        for(Entry e:all)if(e.info.packageName.equals(newestSelected)&&selected.contains(e.info.packageName)&&MiscOptions.matches(e.system,kind,e.label,e.info.packageName,query))visible.add(e);
        for(boolean checked:new boolean[]{true,false})for(Entry e:all)
            if(selected.contains(e.info.packageName)==checked&&!e.info.packageName.equals(newestSelected)
                    &&MiscOptions.matches(e.system,kind,e.label,e.info.packageName,query))visible.add(e);
        adapter.notifyDataSetChanged();status.setText(selected.size()+" selected · "+visible.size()+" shown");
    }
    private void save() {
        if(!loaded||saving)return;saving=true;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false);
        search.setEnabled(false);filter.setEnabled(false);adapter.notifyDataSetChanged();status.setText("Saving…");
        final Set<String> next=new TreeSet<String>(selected),changed=MiscOptions.changed(original,next);
        io.execute(new Runnable(){public void run(){
            boolean saved=Root.run(MiscOptions.listCommand(next))!=null;
            if(saved){List<String> on=Root.run("settings get secure "+MiscSystem.A11Y_KEY);if(on!=null&&!on.isEmpty()&&"1".equals(on.get(0))&&!changed.isEmpty())Root.run("true"+MiscOptions.stopCommands(changed));}
            final boolean ok=saved;
            main.post(new Runnable(){public void run(){if(!live())return;if(ok){dialog.dismiss();Toast.makeText(activity,"Saved. Changes apply the next time these apps open.",Toast.LENGTH_LONG).show();}else{saving=false;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);dialog.setCancelable(true);dialog.setCanceledOnTouchOutside(true);search.setEnabled(true);filter.setEnabled(true);adapter.notifyDataSetChanged();status.setText("Couldn't save. Check root access and try again.");}}});
        }});
    }
    private final class AppsAdapter extends BaseAdapter {
        public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View reuse,ViewGroup parent){
            LinearLayout row;Holder h;
            if(reuse==null){row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(10),0,dp(10));h=new Holder();h.icon=new ImageView(activity);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(36),dp(36));ip.rightMargin=dp(12);row.addView(h.icon,ip);
                LinearLayout names=new LinearLayout(activity);names.setOrientation(1);h.name=new TextView(activity);h.name.setTextColor(text);h.name.setTextSize(15);names.addView(h.name);h.pkg=new TextView(activity);h.pkg.setTextColor(secondary);h.pkg.setTextSize(11);names.addView(h.pkg);row.addView(names,new LinearLayout.LayoutParams(0,-2,1));h.check=new CheckBox(activity);h.check.setButtonTintList(ColorStateList.valueOf(accent));h.check.setClickable(false);h.check.setFocusable(false);row.addView(h.check);row.setTag(h);
            }else{row=(LinearLayout)reuse;h=(Holder)row.getTag();}
            final Entry entry=visible.get(position);h.name.setText(entry.label);h.pkg.setText(entry.info.packageName);h.check.setChecked(selected.contains(entry.info.packageName));
            Drawable icon=icons.get(entry.info.packageName);if(icon==null)try{icon=entry.info.loadIcon(activity.getPackageManager());icons.put(entry.info.packageName,icon);}catch(Throwable ignored){}h.icon.setImageDrawable(icon);
            row.setEnabled(!saving);row.setAlpha(saving?.5f:1f);row.setContentDescription(entry.label+(h.check.isChecked()?", selected":", not selected"));row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(saving)return;boolean checked=selected.add(entry.info.packageName);if(!checked)selected.remove(entry.info.packageName);newestSelected=checked?entry.info.packageName:null;refresh();if(checked)list.setSelection(0);}});return row;
        }
    }
    private static final class Holder {ImageView icon;TextView name,pkg;CheckBox check;}
}
