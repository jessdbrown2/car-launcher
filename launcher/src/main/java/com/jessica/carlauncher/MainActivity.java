package com.jessica.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.widget.ScrollView;
import android.widget.CheckBox;
import java.util.HashSet;
import java.util.Set;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Driver-facing source controls for the QC4250 stereo. */
public class MainActivity extends Activity {

    private static final int BG = Color.BLACK;
    private static final int TEXT = 0xfff3f1ed;
    private static final int MUTED = 0xffbfc0bf;

    /** Order also identifies existing saved shortcut preferences. */
    private static final String[] TILE_NAMES = {"CarPlay", "Radio", "Bluetooth", "Sound", "Car Settings"};
    private static final String[] TILE_DEFAULTS = {
            "com.zjinnova.zlink/com.zjinnova.android.zlink.features.launcher.CarPlayActivity",
            "com.acloud.stub.extradio/com.acloud.stub.extradio.QtActivity",
            "com.xygala.bluetooth/com.xygala.bluetooth.activity.GroupMainActivity",
            "com.xyauto.dspSetting/com.xyauto.dspSetting.ui.MainActivity",
            "com.xyauto.Settings/com.xyauto.Settings.MainActivity"
    };
    private static final int RED = 0xffd44745;
    private static final int PANEL = 0xff191a1b;
    private FrameLayout content;
    private LinearLayout dock;
    private boolean tracking;
    private enum Page { HOME, APPS, SETTINGS, VISIBLE }
    private Page page = Page.HOME;
    private static final int STREAM = AudioManager.STREAM_MUSIC;


    /** Where GitHub publishes each new build. */
    private static final String UPDATE_URL =
            "https://github.com/jessdbrown2/car-launcher/releases/download/launcher/launcher.apk";


    /** Volume bar hides this long after the last touch or volume change. */
    private static final long VOLUME_HIDE_MS = 5000;

    private SharedPreferences prefs;
    private LinearLayout volumeRow;
    private boolean volumeShown;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideVolumeLater = this::hideVolumeBar;

    private AudioManager audio;
    private SeekBar volumeBar;
    private TextView muteIcon;
    private TextView volumeNumber;

    /** Keeps the slider in step when volume changes elsewhere (steering wheel, knob, CarPlay). */
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", STREAM) == STREAM) {
                refreshVolume();
                showVolumeBar();
            } }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        FrameLayout stage = new FrameLayout(this);
        content = new FrameLayout(this);
        stage.addView(content);
        volumeRow = buildVolumeRow();
        volumeRow.setVisibility(View.GONE);
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM);
        panel.setMargins(dp(24), 0, dp(24), dp(10));
        stage.addView(volumeRow, panel);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        dock = new LinearLayout(this);
        dock.setBackgroundColor(PANEL);
        root.addView(dock, new LinearLayout.LayoutParams(-1, dp(82)));
        setContentView(root);
        go(Page.HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        refreshVolume();
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) { }
        tracking = false;
        hideVolumeBar();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (volumeShown) hideVolumeBar();
        else go(page == Page.VISIBLE ? Page.SETTINGS : Page.HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        go(Page.HOME);
    }

    private TextView label(String title, int size) {
        TextView v = new TextView(this);
        v.setText(title);
        v.setTextColor(TEXT);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setIncludeFontPadding(false);
        return v;
    }

    private void action(View v, int color, Runnable task) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66d44745),
                new android.graphics.drawable.ColorDrawable(color), null));
        v.setOnClickListener(clicked -> task.run());
        v.setFocusable(true);
    }

    private LinearLayout command(String title, int icon, int size, boolean centered) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(20), 0, dp(20), 0);
        row.setGravity(centered ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(size+4), dp(size+4)));
        TextView name = label(title, size);
        name.setPadding(dp(16), 0, 0, 0);
        row.addView(name);
        return row;
    }

    private void go(Page next) {
        hideVolumeBar();
        page = next;
        content.removeAllViews();
        if (page == Page.HOME) buildHome();
        else if (page == Page.APPS) buildApps();
        else buildSettings();
        dock.removeAllViews();
        dockItem("Volume", R.drawable.dash_volume, false, () -> {
            if (volumeShown) hideVolumeBar(); else showVolumeBar();
        });
        dockItem("Sound", R.drawable.dash_sound, false, () -> launch(tileComponent(3), "Sound"))
                .setOnLongClickListener(v -> { pickAppForTile(3); return true; });
        dockItem("Settings", R.drawable.dash_settings, page == Page.SETTINGS || page == Page.VISIBLE,
                () -> go(page == Page.SETTINGS ? Page.HOME : Page.SETTINGS));
        dockItem("Apps", R.drawable.dash_apps, page == Page.APPS,
                () -> go(page == Page.APPS ? Page.HOME : Page.APPS));
    }

    private View dockItem(String title, int icon, boolean active, Runnable task) {
        LinearLayout item = command(title, icon, 24, true);
        action(item, active ? 0xff303132 : PANEL, task);
        item.setSelected(active);
        dock.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        return item;
    }

    private void buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setPadding(dp(32), dp(28), dp(24), dp(28));
        home.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        int[] icons = {R.drawable.dash_carplay, R.drawable.dash_radio, R.drawable.dash_bluetooth};
        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            LinearLayout source = command(TILE_NAMES[i], icons[i], 38, false);
            source.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            TextView arrow = label("›", 34);
            arrow.setTextColor(MUTED);
            source.addView(arrow);
            action(source, BG, () -> launch(tileComponent(idx), TILE_NAMES[idx]));
            source.setOnLongClickListener(v -> { pickAppForTile(idx); return true; });
            sources.addView(source, new LinearLayout.LayoutParams(-1, dp(104)));
            if (i < icons.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0xff343536);
                sources.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        home.addView(sources, new LinearLayout.LayoutParams(0, -2, 1.05f));
        home.addView(buildClock(), new LinearLayout.LayoutParams(0, -1, 1));
        content.addView(home);
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

    private FrameLayout buildClock() {
        FrameLayout area = new FrameLayout(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setSingleLine(true);
        time.setAutoSizeTextTypeUniformWithConfiguration(48, 145, 2, TypedValue.COMPLEX_UNIT_SP);
        time.setTextColor(TEXT);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        date.setTextColor(MUTED);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setIncludeFontPadding(false);
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, dp(8), 0, 0);

        col.addView(time, new LinearLayout.LayoutParams(-1, dp(164)));
        col.addView(date);
        area.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Tap the clock area to show or hide the volume bar; long-press for the menu.
        area.setOnClickListener(v -> { if (volumeShown) hideVolumeBar(); else showVolumeBar(); });
        area.setOnLongClickListener(v -> { go(Page.SETTINGS); return true; });


        return area;
    }

    private void showVolumeBar() {
        if (volumeRow == null) return;
        if (!volumeShown) {
            volumeShown = true;
            volumeRow.animate().cancel();
            volumeRow.setAlpha(0f);
            volumeRow.setTranslationY(dp(40));
            volumeRow.setVisibility(android.view.View.VISIBLE);
            volumeRow.animate().alpha(1f).translationY(0).setDuration(200).start();
        }
        keepVolumeBar();
    }

    /** Restarts the 5-second hide timer. Called on every volume touch. */
    private void keepVolumeBar() {
        ui.removeCallbacks(hideVolumeLater);
        if (!tracking) ui.postDelayed(hideVolumeLater, VOLUME_HIDE_MS);
    }

    private void hideVolumeBar() {
        ui.removeCallbacks(hideVolumeLater);
        if (volumeRow == null || !volumeShown) return;
        volumeShown = false;
        volumeRow.animate().cancel();
        volumeRow.animate().alpha(0f).translationY(dp(40)).setDuration(200)
                .withEndAction(() -> { if (!volumeShown) volumeRow.setVisibility(android.view.View.GONE); })
                .start();
    }

    // ---------- Volume ----------

    private LinearLayout buildVolumeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.setBackgroundColor(0xff252628);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setClickable(true);
        muteIcon = volumeButton(row, "Mute", this::toggleMute);
        volumeButton(row, "−", () -> stepVolume(-1)).setContentDescription("Volume down");
        volumeBar = new SeekBar(this);
        volumeBar.setContentDescription("Music volume");
        volumeBar.setMax(audio.getStreamMaxVolume(STREAM));
        volumeBar.setThumbTintList(ColorStateList.valueOf(TEXT));
        volumeBar.setProgressTintList(ColorStateList.valueOf(RED));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) {
                if (fromUser) { audio.setStreamVolume(STREAM, value, 0); showVolume(value); keepVolumeBar(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { tracking = true; ui.removeCallbacks(hideVolumeLater); }
            @Override public void onStopTrackingTouch(SeekBar b) { tracking = false; keepVolumeBar(); }
        });
        row.addView(volumeBar, new LinearLayout.LayoutParams(0, dp(90), 1));

        volumeButton(row, "+", () -> stepVolume(1)).setContentDescription("Volume up");
        volumeNumber = label("", 32);
        volumeNumber.setGravity(Gravity.CENTER);
        row.addView(volumeNumber, new LinearLayout.LayoutParams(dp(72), -1));
        return row;
    }

    private TextView volumeButton(LinearLayout row, String title, Runnable task) {
        TextView button = label(title, 26);
        button.setGravity(Gravity.CENTER);
        action(button, 0xff252628, task);
        row.addView(button, new LinearLayout.LayoutParams(dp(104), -1));
        return button;
    }

    private void stepVolume(int delta) {
        keepVolumeBar();
        int max = audio.getStreamMaxVolume(STREAM);
        // The stereo has 63 steps, so each tap moves 2 to make a noticeable change.
        int step = max > 30 ? 2 : 1;
        int v = Math.max(0, Math.min(max, audio.getStreamVolume(STREAM) + delta * step));
        audio.setStreamVolume(STREAM, v, 0);
        refreshVolume();
    }

    private void toggleMute() {
        keepVolumeBar();
        int now = audio.getStreamVolume(STREAM);
        if (now > 0) {
            prefs.edit().putInt("volume_before_mute", now).apply();
            audio.setStreamVolume(STREAM, 0, 0);
        } else {
            audio.setStreamVolume(STREAM, Math.max(1, prefs.getInt("volume_before_mute", 8)), 0);
        }
        refreshVolume();
    }

    private void refreshVolume() {
        if (volumeBar == null) return;
        int v = audio.getStreamVolume(STREAM);
        volumeBar.setProgress(v);
        showVolume(v);
    }

    private void showVolume(int v) {
        volumeNumber.setText(String.valueOf(v));
        muteIcon.setText(v == 0 ? "Unmute" : "Mute");
    }


    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    /** Opens the latest build's download in Chrome; tap the downloaded file to install it. */
    private void openUpdate() {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(new Intent(view).setPackage("com.android.chrome")); return; } catch (Exception ignored) { }
        try { startActivity(view); }
        catch (Exception e) { Toast.makeText(this, "No browser found.", Toast.LENGTH_SHORT).show(); }
    }

    private LinearLayout pageList(String title) {
        LinearLayout panel = new LinearLayout(this);
        panel.setPadding(dp(32), dp(12), dp(32), dp(12));
        panel.setOrientation(LinearLayout.VERTICAL);
        content.addView(panel);
        TextView back = label("‹   " + title, 32);
        back.setContentDescription("Back from " + title);
        action(back, BG, this::onBackPressed);
        panel.addView(back, new LinearLayout.LayoutParams(-1, dp(70)));
        ScrollView scroll = new ScrollView(this);
        panel.addView(scroll);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        return rows;
    }

    private void item(LinearLayout rows, String title, Runnable task) {
        TextView v = label(title, 24);
        v.setPadding(dp(20), 0, dp(20), 0);
        action(v, PANEL, task);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(64));
        lp.bottomMargin = dp(2);
        rows.addView(v, lp);
    }

    private String key(ResolveInfo app) {
        return new ComponentName(app.activityInfo.packageName, app.activityInfo.name).flattenToString();
    }

    private Set<String> visibleApps(List<ResolveInfo> apps) {
        if (prefs.contains("visible_apps")) {
            return new HashSet<>(prefs.getStringSet("visible_apps", Collections.emptySet()));
        }
        Set<String> result = new HashSet<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            String name = app.loadLabel(getPackageManager()).toString();
            if (pkg.equals("com.android.chrome") || pkg.equals("com.android.vending") ||
                    name.equalsIgnoreCase("Files")) result.add(key(app));
        }
        return result;
    }

    private void buildApps() {
        LinearLayout rows = pageList("Apps");
        List<ResolveInfo> apps = launchableApps();
        Set<String> visible = visibleApps(apps);
        LinearLayout line = null;
        int count = 0;
        for (ResolveInfo app : apps) {
            if (!visible.contains(key(app))) continue;
            if (count % 3 == 0) {
                line = new LinearLayout(this);
                line.setWeightSum(3);
                rows.addView(line);
            }
            String name = app.loadLabel(getPackageManager()).toString();
            TextView tile = label(name, 25);
            tile.setGravity(Gravity.CENTER);
            action(tile, PANEL, () -> launch(ComponentName.unflattenFromString(key(app)), name));
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(110), 1);
            cell.setMargins(dp(3), dp(3), dp(3), dp(3));
            line.addView(tile, cell);
            count++;
        }
        if (count == 0) item(rows, "Choose apps to show", () -> go(Page.VISIBLE));
    }

    private void buildSettings() {
        if (page == Page.VISIBLE) {
            LinearLayout rows = pageList("Visible apps");
            List<ResolveInfo> apps = launchableApps();
            Set<String> selected = visibleApps(apps);
            for (ResolveInfo app : apps) {
                String id = key(app);
                CheckBox check = new CheckBox(this);
                check.setText(app.loadLabel(getPackageManager()));
                check.setTextColor(TEXT);
                check.setTextSize(22);
                check.setButtonTintList(ColorStateList.valueOf(MUTED));
                check.setChecked(selected.contains(id));
                check.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) selected.add(id); else selected.remove(id);
                    prefs.edit().putStringSet("visible_apps", new HashSet<>(selected)).apply();
                });
                rows.addView(check, new LinearLayout.LayoutParams(-1, dp(60)));
            }
            return;
        }
        LinearLayout rows = pageList("Settings");
        item(rows, "Car settings", () -> launch(tileComponent(4), TILE_NAMES[4]));
        rows.getChildAt(0).setOnLongClickListener(v -> { pickAppForTile(4); return true; });
        item(rows, "Manage visible apps", () -> go(Page.VISIBLE));
        item(rows, "Update launcher   ·   " + versionName(), this::openUpdate);
        item(rows, "Diagnostics", () -> {
            Intent inspector = getPackageManager().getLaunchIntentForPackage("com.jessica.carinspector");
            if (inspector == null) Toast.makeText(this, "Car Inspector is unavailable.", Toast.LENGTH_SHORT).show();
            else startActivity(inspector);
        });
        item(rows, "Default home app", () -> openSettings(Settings.ACTION_HOME_SETTINGS));
        item(rows, "Android settings", () -> openSettings(Settings.ACTION_SETTINGS));
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "This stereo doesn't have that screen.", Toast.LENGTH_SHORT).show(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
