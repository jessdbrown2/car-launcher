package com.jessica.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Home screen for the QC4250 stereo: icon rail on the left, large centered clock and date. */
public class MainActivity extends Activity {

    private static final int BG = Color.BLACK;
    private static final int TEXT = Color.parseColor("#F5F5F5");
    private static final int MUTED = Color.parseColor("#9A9A9A");

    /** Rail tiles, top to bottom: CarPlay, Radio, Phone switch, DSP/EQ, Car Settings. */
    private static final String[] TILE_NAMES = {"CarPlay", "Radio", "Phone switch", "DSP / EQ", "Car Settings"};
    private static final String[] TILE_DEFAULTS = {
            "com.zjinnova.zlink/com.zjinnova.android.zlink.features.launcher.CarPlayActivity",
            "com.acloud.stub.extradio/com.acloud.stub.extradio.QtActivity",
            "com.xygala.bluetooth/com.xygala.bluetooth.activity.GroupMainActivity",
            "com.xyauto.dspSetting/com.xyauto.dspSetting.ui.MainActivity",
            "com.xyauto.Settings/com.xyauto.Settings.MainActivity"
    };
    private static final int[] TILE_ICONS = {R.drawable.rail_carplay, R.drawable.rail_radio,
            R.drawable.rail_bluetooth, R.drawable.rail_equalizer, R.drawable.rail_settings};

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        root.addView(rail, new LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT));
        buildRail(rail);

        root.addView(buildClock(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
    }

    @Override
    public void onBackPressed() { /* Home screen: nothing to go back to. */ }

    // ---------- Left rail ----------

    private void buildRail(LinearLayout rail) {
        for (int i = 0; i < TILE_NAMES.length; i++) {
            final int idx = i;
            ImageView tile = new ImageView(this);
            tile.setImageResource(TILE_ICONS[i]);
            tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.setAdjustViewBounds(false);
            tile.setContentDescription(TILE_NAMES[i]);
            // Dim slightly while pressed so taps feel responsive.
            tile.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.6f);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
                return false;
            });
            tile.setOnClickListener(v -> launch(tileComponent(idx), TILE_NAMES[idx]));
            tile.setOnLongClickListener(v -> { pickAppForTile(idx); return true; });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (i > 0) lp.topMargin = dp(8);
            rail.addView(tile, lp);
        }
    }

    private ComponentName tileComponent(int i) {
        String s = prefs.getString("tile_" + i, TILE_DEFAULTS[i]);
        ComponentName cn = ComponentName.unflattenFromString(s);
        return cn != null ? cn : ComponentName.unflattenFromString(TILE_DEFAULTS[i]);
    }

    private void launch(ComponentName cn, String name) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return; } catch (Exception ignored) { }
        Intent fallback = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
        if (fallback != null) {
            try { startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) { }
        }
        Toast.makeText(this, name + " isn't installed. Long-press the tile to choose an app.", Toast.LENGTH_LONG).show();
    }

    private void pickAppForTile(int idx) {
        final List<ResolveInfo> apps = launchableApps();
        List<String> labels = new ArrayList<>();
        labels.add("Reset to default (" + TILE_NAMES[idx] + ")");
        PackageManager pm = getPackageManager();
        for (ResolveInfo r : apps) labels.add(r.loadLabel(pm).toString());
        new AlertDialog.Builder(this)
                .setTitle("Choose an app for this tile")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) prefs.edit().remove("tile_" + idx).apply();
                    else {
                        ResolveInfo r = apps.get(which - 1);
                        prefs.edit().putString("tile_" + idx,
                                new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString()).apply();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<ResolveInfo> launchableApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = new ArrayList<>(pm.queryIntentActivities(q, 0));
        list.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
        Collections.sort(list, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        return list;
    }

    // ---------- Center: clock and date ----------

    private LinearLayout buildClock() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 200);
        time.setTextColor(TEXT);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        date.setTextColor(MUTED);
        date.setIncludeFontPadding(false);
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, dp(8), 0, 0);

        col.addView(time);
        col.addView(date);
        // Long-press the clock for the menu (all apps, home app settings).
        col.setOnLongClickListener(v -> { showMenu(); return true; });
        return col;
    }

    private void showMenu() {
        String[] items = {"All apps", "Home app settings", "Android settings"};
        new AlertDialog.Builder(this).setTitle("Car Launcher").setItems(items, (d, which) -> {
            if (which == 0) showAllApps();
            else openSettings(which == 1 ? Settings.ACTION_HOME_SETTINGS : Settings.ACTION_SETTINGS);
        }).setNegativeButton("Close", null).show();
    }

    private void showAllApps() {
        final List<ResolveInfo> apps = launchableApps();
        PackageManager pm = getPackageManager();
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this).setTitle("All apps").setItems(labels, (d, which) -> {
            ResolveInfo r = apps.get(which);
            launch(new ComponentName(r.activityInfo.packageName, r.activityInfo.name), labels[which]);
        }).setNegativeButton("Close", null).show();
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "This stereo doesn't have that screen.", Toast.LENGTH_SHORT).show(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
