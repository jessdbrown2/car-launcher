package com.jessica.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Home screen for the QC4250 stereo: left app rail, big clock and date, CarPlay controls. */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0B0B0B");
    private static final int CARD = Color.parseColor("#1C1C1C");
    private static final int ACCENT = Color.parseColor("#378ADD");
    private static final int TEXT = Color.parseColor("#F1EFE8");
    private static final int MUTED = Color.parseColor("#B4B2A9");

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout rail;
    private ImageView playIcon;
    private boolean showingPause;

    /** Checks once a second whether music is playing, so the middle button shows play or pause. */
    private final Runnable stateLoop = new Runnable() {
        @Override public void run() {
            updatePlayIcon();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        root.addView(rail, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.MATCH_PARENT));
        buildRail();

        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        centerLp.leftMargin = dp(12);
        root.addView(buildCenter(), centerLp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(stateLoop);
        main.post(stateLoop);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(stateLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { /* Home screen: nothing to go back to. */ }

    // ---------- Left rail ----------

    private void buildRail() {
        rail.removeAllViews();
        for (int i = 0; i < TILE_NAMES.length; i++) {
            final int idx = i;
            FrameLayout tile = new FrameLayout(this);
            tile.setBackground(rounded(CARD, dp(14)));
            tile.setContentDescription(TILE_NAMES[i]);

            ImageView iv = new ImageView(this);
            iv.setImageResource(TILE_ICONS[i]);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            tile.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            tile.setOnClickListener(v -> launch(tileComponent(idx), TILE_NAMES[idx]));
            tile.setOnLongClickListener(v -> { pickAppForTile(idx); return true; });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (i > 0) lp.topMargin = dp(10);
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
                        ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
                        prefs.edit().putString("tile_" + idx, cn.flattenToString()).apply();
                    }
                    buildRail();
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

    // ---------- Center: clock, date, CarPlay controls ----------

    private View buildCenter() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, dp(18)));
        card.setPadding(dp(40), dp(24), dp(40), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.CENTER_VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 170, TEXT, true);
        time.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        style(date, 32, MUTED, false);
        date.setIncludeFontPadding(false);
        date.setPadding(0, dp(6), 0, 0);
        clockCol.addView(time);
        clockCol.addView(date);
        // Long-press the clock for the menu (all apps, music access, home app settings).
        clockCol.setOnLongClickListener(v -> { showMenu(); return true; });
        top.addView(clockCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        alignInk(time);
        alignInk(date);


        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#333333"));
        card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.addView(controlButton(R.drawable.ic_prev, "Previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, TEXT));
        View playBtn = controlButton(R.drawable.ic_play, "Play or pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ACCENT);
        playIcon = (ImageView) ((FrameLayout) playBtn).getChildAt(0);
        controls.addView(playBtn);
        controls.addView(controlButton(R.drawable.ic_next, "Next", KeyEvent.KEYCODE_MEDIA_NEXT, TEXT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        clp.topMargin = dp(10);
        card.addView(controls, clp);
        return card;
    }

    private View controlButton(int iconRes, String desc, int keyCode, int tint) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(rounded(Color.parseColor("#262626"), dp(16)));
        b.setContentDescription(desc);
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(tint);
        b.addView(iv, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        b.setOnClickListener(v -> {
            sendMediaKey(keyCode);
            main.postDelayed(this::updatePlayIcon, 400);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(130));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        b.setLayoutParams(lp);
        return b;
    }

    /** Sends the key to CarPlay's session if music access is on, otherwise to whatever app is playing. */
    private void sendMediaKey(int code) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0);
        MediaController c = findController();
        if (c != null) {
            c.dispatchMediaButtonEvent(down);
            c.dispatchMediaButtonEvent(up);
        } else {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(down);
            am.dispatchMediaKeyEvent(up);
        }
    }

    /** Pause icon only while music is playing; play icon otherwise. */
    private void updatePlayIcon() {
        if (playIcon == null) return;
        boolean playing;
        MediaController c = findController();
        PlaybackState st = c != null ? c.getPlaybackState() : null;
        if (st != null && st.getState() != PlaybackState.STATE_NONE) {
            playing = st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING;
        } else {
            // CarPlay often reports no state, so fall back to whether any audio is playing.
            playing = ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive();
        }
        if (playing != showingPause) {
            showingPause = playing;
            playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    /** Shifts text left by its first glyph's side bearing, so the big clock and the date share one left edge. */
    private void alignInk(TextView t) {
        Runnable fix = () -> {
            CharSequence txt = t.getText();
            if (txt.length() == 0) return;
            android.graphics.Rect r = new android.graphics.Rect();
            t.getPaint().getTextBounds(txt.toString(), 0, 1, r);
            t.setTranslationX(-r.left);
        };
        t.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(android.text.Editable e) { t.post(fix); }
        });
        t.post(fix);
    }

    private MediaController findController() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            for (MediaController c : list) if (c.getPackageName().equals("com.zjinnova.zlink")) return c;
            return list.isEmpty() ? null : list.get(0);
        } catch (SecurityException notGranted) {
            return null;
        }
    }

    private void showMenu() {
        String[] items = {"All apps", "Music controls access", "Home app settings", "Android settings"};
        new AlertDialog.Builder(this).setTitle("Car Launcher").setItems(items, (d, which) -> {
            switch (which) {
                case 0: showAllApps(); break;
                case 1: openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); break;
                case 2: openSettings(Settings.ACTION_HOME_SETTINGS); break;
                default: openSettings(Settings.ACTION_SETTINGS);
            }
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

    // ---------- Helpers ----------

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        style(t, sp, color, false);
        return t;
    }

    private void style(TextView t, int sp, int color, boolean bold) {
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
