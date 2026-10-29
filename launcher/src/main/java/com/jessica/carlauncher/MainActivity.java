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
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Home screen for the QC4250 stereo: icon rail on the left, large centered clock and date, volume row. */
public class MainActivity extends Activity {

    private static final int BG = Color.BLACK;
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.parseColor("#D0D0D0");

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

    private static final int STREAM = AudioManager.STREAM_MUSIC;

    /** Screen brightness (0-255) with headlights off and on. */
    private static final int DAY_BRIGHTNESS = 255;
    private static final int NIGHT_BRIGHTNESS = 90;
    /** The stereo sets this to 1 when the headlight wire is live. */
    private static final String HEADLAMP = "Headlamp";

    private SharedPreferences prefs;
    private final ContentObserver headlampObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean self) { applyHeadlightBrightness(); }
    };
    private AudioManager audio;
    private SeekBar volumeBar;
    private ImageView muteIcon;
    private TextView volumeNumber;

    /** Keeps the slider in step when volume changes elsewhere (steering wheel, knob, CarPlay). */
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshVolume(); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        root.addView(rail, new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.MATCH_PARENT));
        buildRail(rail);

        root.addView(buildClock(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);

        getContentResolver().registerContentObserver(Settings.System.getUriFor(HEADLAMP), false, headlampObserver);
        if (!Settings.System.canWrite(this) && !prefs.getBoolean("asked_brightness", false)) {
            prefs.edit().putBoolean("asked_brightness", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Allow night dimming?")
                    .setMessage("Car Launcher can dim the screen when your headlights are on and go back to full brightness when they're off. Turn on \"Modify system settings\" on the next screen.")
                    .setPositiveButton("Open settings", (d, w) -> openBrightnessAccess())
                    .setNegativeButton("Not now", null)
                    .show();
        }
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(headlampObserver);
        super.onDestroy();
    }

    // ---------- Headlight dimming ----------

    private void applyHeadlightBrightness() {
        if (!Settings.System.canWrite(this)) return;
        String lamp = Settings.System.getString(getContentResolver(), HEADLAMP);
        boolean lightsOn = lamp != null && !lamp.trim().equals("0");
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    lightsOn ? NIGHT_BRIGHTNESS : DAY_BRIGHTNESS);
        } catch (Exception ignored) { }
    }

    private void openBrightnessAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "This stereo doesn't have that screen.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        refreshVolume();
        applyHeadlightBrightness();
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) { }
        super.onPause();
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

            // Equal gaps above, between and below the tiles.
            rail.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(86), dp(86));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            rail.addView(tile, lp);
        }
        rail.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
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
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 200);
        time.setTextColor(TEXT);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        date.setTextColor(MUTED);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setIncludeFontPadding(false);
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, dp(8), 0, 0);

        col.addView(time);
        col.addView(date);
        col.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
        // Volume bar sits near the bottom edge.
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(860), dp(90));
        vlp.bottomMargin = dp(18);
        col.addView(buildVolumeRow(), vlp);
        // Long-press the clock for the menu (all apps, home app settings).
        col.setOnLongClickListener(v -> { showMenu(); return true; });
        return col;
    }

    // ---------- Volume ----------

    private LinearLayout buildVolumeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout mute = iconButton(R.drawable.vol_speaker, "Mute");
        muteIcon = (ImageView) mute.getChildAt(0);
        mute.setOnClickListener(v -> toggleMute());
        row.addView(mute);

        FrameLayout minus = iconButton(R.drawable.vol_minus, "Volume down");
        minus.setOnClickListener(v -> stepVolume(-1));
        row.addView(minus);

        volumeBar = new SeekBar(this);
        volumeBar.setMax(audio.getStreamMaxVolume(STREAM));
        volumeBar.setProgressDrawable(trackDrawable());
        volumeBar.setThumb(getDrawable(R.drawable.vol_knob));
        volumeBar.setThumbOffset(dp(23));
        volumeBar.setSplitTrack(false);
        volumeBar.setPadding(dp(26), 0, dp(26), 0);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) {
                if (fromUser) { audio.setStreamVolume(STREAM, value, 0); showVolume(value); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        row.addView(volumeBar, new LinearLayout.LayoutParams(0, dp(90), 1));

        FrameLayout plus = iconButton(R.drawable.vol_plus, "Volume up");
        plus.setOnClickListener(v -> stepVolume(1));
        row.addView(plus);

        volumeNumber = new TextView(this);
        volumeNumber.setBackgroundResource(R.drawable.vol_tile);
        volumeNumber.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        volumeNumber.setTextColor(Color.parseColor("#E4E4E8"));
        volumeNumber.setTypeface(Typeface.DEFAULT_BOLD);
        volumeNumber.setShadowLayer(dp(3), 0, dp(2), Color.BLACK);
        volumeNumber.setGravity(Gravity.CENTER);
        row.addView(volumeNumber, new LinearLayout.LayoutParams(dp(90), dp(90)));
        return row;
    }

    private FrameLayout iconButton(int icon, String desc) {
        FrameLayout b = new FrameLayout(this);
        b.setContentDescription(desc);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(90), dp(90));
        lp.rightMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.6f);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        return b;
    }

    private Drawable trackDrawable() {
        // Dark recessed groove with a brushed-silver fill, like the EQ icon.
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#080809"), Color.parseColor("#2E2E32")});
        bg.setCornerRadius(dp(9));
        bg.setStroke(dp(1), Color.parseColor("#4E4E54"));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#F0F0F3"), Color.parseColor("#7D7D83")});
        fill.setCornerRadius(dp(9));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{bg, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, dp(18));
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL);
        }
        return layers;
    }

    private void stepVolume(int delta) {
        int max = audio.getStreamMaxVolume(STREAM);
        // The stereo has 63 steps, so each tap moves 2 to make a noticeable change.
        int step = max > 30 ? 2 : 1;
        int v = Math.max(0, Math.min(max, audio.getStreamVolume(STREAM) + delta * step));
        audio.setStreamVolume(STREAM, v, 0);
        refreshVolume();
    }

    private void toggleMute() {
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
        muteIcon.setImageResource(v == 0 ? R.drawable.vol_mute : R.drawable.vol_speaker);
    }

    private void showMenu() {
        String[] items = {"All apps", "Night dimming access", "Home app settings", "Android settings"};
        new AlertDialog.Builder(this).setTitle("Car Launcher").setItems(items, (d, which) -> {
            if (which == 0) showAllApps();
            else if (which == 1) openBrightnessAccess();
            else openSettings(which == 2 ? Settings.ACTION_HOME_SETTINGS : Settings.ACTION_SETTINGS);
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
