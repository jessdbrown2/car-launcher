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
    private static final int DEFAULT_NIGHT_PERCENT = 35;
    /** The stereo sets this to 1 when the headlight wire is live. */
    private static final String HEADLAMP = "Headlamp";

    /** Where GitHub publishes each new build. */
    private static final String UPDATE_URL =
            "https://github.com/jessdbrown2/car-launcher/releases/download/launcher/launcher.apk";

    /** Every rail tile, including All apps, is this size. */
    private static final int RAIL_TILE = 100;
    /** Volume bar hides this long after the last touch or volume change. */
    private static final long VOLUME_HIDE_MS = 5000;

    private SharedPreferences prefs;
    private LinearLayout volumeRow;
    private boolean volumeShown;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideVolumeLater = this::hideVolumeBar;
    private FrameLayout appsOverlay;
    private final ContentObserver headlampObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean self) { applyHeadlightBrightness(); }
    };
    private AudioManager audio;
    private SeekBar volumeBar;
    private ImageView muteIcon;
    private TextView volumeNumber;

    /** Keeps the slider in step when volume changes elsewhere (steering wheel, knob, CarPlay). */
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshVolume(); showVolumeBar(); }
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
        root.addView(rail, new LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.MATCH_PARENT));
        buildRail(rail);

        root.addView(buildClock(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        FrameLayout screen = new FrameLayout(this);
        screen.addView(root);
        appsOverlay = buildAppsOverlay();
        appsOverlay.setVisibility(android.view.View.GONE);
        screen.addView(appsOverlay);
        setContentView(screen);

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
                    lightsOn ? nightLevel() : DAY_BRIGHTNESS);
        } catch (Exception ignored) { }
    }

    private int nightPercent() { return prefs.getInt("night_percent", DEFAULT_NIGHT_PERCENT); }

    private int nightLevel() { return Math.max(5, Math.round(nightPercent() * 255f / 100f)); }

    /** Slider for headlight brightness. Previews on screen while dragging, saves on Done. */
    private void showNightBrightness() {
        if (!Settings.System.canWrite(this)) { openBrightnessAccess(); return; }
        final int before = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, DAY_BRIGHTNESS);
        final TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        value.setGravity(Gravity.CENTER);
        value.setText(nightPercent() + "%");
        final SeekBar bar = new SeekBar(this);
        bar.setMax(95);
        bar.setProgress(nightPercent() - 5);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                int pct = p + 5;
                value.setText(pct + "%");
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                            Math.round(pct * 255f / 100f));
                } catch (Exception ignored) { }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(32), dp(16), dp(32), dp(8));
        box.addView(value);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        new AlertDialog.Builder(this)
                .setTitle("Brightness with headlights on")
                .setView(box)
                .setPositiveButton("Done", (d, w) -> {
                    prefs.edit().putInt("night_percent", bar.getProgress() + 5).apply();
                    applyHeadlightBrightness();
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    try {
                        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, before);
                    } catch (Exception ignored) { }
                    applyHeadlightBrightness();
                })
                .show();
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
        hideVolumeBar();
        super.onPause();
    }

    @Override
    public void onBackPressed() { hideApps(); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideApps(); // Home button closes the app grid
    }

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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(RAIL_TILE), dp(RAIL_TILE));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            rail.addView(tile, lp);
        }
        rail.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
        ImageView apps = new ImageView(this);
        apps.setImageResource(R.drawable.rail_apps);
        apps.setScaleType(ImageView.ScaleType.FIT_CENTER);
        apps.setContentDescription("All apps");
        apps.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.6f);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        apps.setOnClickListener(v -> showApps());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(RAIL_TILE), dp(RAIL_TILE));
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        rail.addView(apps, alp);
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
        area.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Tap the clock area to show or hide the volume bar; long-press for the menu.
        area.setOnClickListener(v -> { if (volumeShown) hideVolumeBar(); else showVolumeBar(); });
        area.setOnLongClickListener(v -> { showMenu(); return true; });

        volumeRow = buildVolumeRow();
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(dp(860), dp(90),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        vlp.bottomMargin = dp(18);
        area.addView(volumeRow, vlp);
        volumeRow.setVisibility(android.view.View.GONE);
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
        ui.postDelayed(hideVolumeLater, VOLUME_HIDE_MS);
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
                if (fromUser) { audio.setStreamVolume(STREAM, value, 0); showVolume(value); keepVolumeBar(); }
            }
            @Override public void onStartTrackingTouch(SeekBar b) { ui.removeCallbacks(hideVolumeLater); }
            @Override public void onStopTrackingTouch(SeekBar b) { keepVolumeBar(); }
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
        muteIcon.setImageResource(v == 0 ? R.drawable.vol_mute : R.drawable.vol_speaker);
    }

    private void showMenu() {
        String[] items = {"All apps", "Update launcher", "Night brightness", "Home app settings", "Android settings"};
        new AlertDialog.Builder(this).setTitle("Car Launcher " + versionName()).setItems(items, (d, which) -> {
            if (which == 0) showApps();
            else if (which == 1) openUpdate();
            else if (which == 2) showNightBrightness();
            else openSettings(which == 3 ? Settings.ACTION_HOME_SETTINGS : Settings.ACTION_SETTINGS);
        }).setNegativeButton("Close", null).show();
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

    // ---------- All apps grid ----------

    private FrameLayout buildAppsOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing(dp(18));
        grid.setPadding(dp(24), dp(24), dp(110), dp(24));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setTag("grid");
        overlay.addView(grid);
        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        close.setTextColor(TEXT);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> hideApps());
        overlay.addView(close, new FrameLayout.LayoutParams(dp(90), dp(90), Gravity.TOP | Gravity.END));
        return overlay;
    }

    private void showApps() {
        final List<ResolveInfo> apps = launchableApps();
        final PackageManager pm = getPackageManager();
        GridView grid = appsOverlay.findViewWithTag("grid");
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public android.view.View getView(int i, android.view.View old, ViewGroup parent) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, dp(8), 0, dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                iv.setImageDrawable(apps.get(i).loadIcon(pm));
                cell.addView(iv, new LinearLayout.LayoutParams(dp(80), dp(80)));
                TextView tv = new TextView(MainActivity.this);
                tv.setText(apps.get(i).loadLabel(pm));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                tv.setTextColor(MUTED);
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setPadding(dp(6), dp(8), dp(6), 0);
                cell.addView(tv);
                return cell;
            }
        });
        grid.setOnItemClickListener((p, v, i, id) -> {
            ResolveInfo r = apps.get(i);
            hideApps();
            launch(new ComponentName(r.activityInfo.packageName, r.activityInfo.name), r.loadLabel(pm).toString());
        });
        appsOverlay.setVisibility(android.view.View.VISIBLE);
    }

    private void hideApps() {
        if (appsOverlay != null) appsOverlay.setVisibility(android.view.View.GONE);
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "This stereo doesn't have that screen.", Toast.LENGTH_SHORT).show(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
