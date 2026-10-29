package com.jessica.carinspector;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only report of what this head unit exposes. Changes nothing. */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int C_BG = Color.rgb(14, 14, 16);
    private static final int C_TEXT = Color.rgb(230, 230, 230);
    private static final int C_DIM = Color.rgb(150, 150, 155);
    private static final int C_HI = Color.rgb(90, 200, 250);
    private static final int C_WARN = Color.rgb(255, 180, 80);

    private static final String[] APP_WORDS = {"carplay", "car play", "bluetooth", "bt", "music", "media",
            "player", "settings", "setting", "calendar", "phone", "dial", "radio", "zlink", "autokit",
            "carlink", "link", "map", "navi", "volume", "audio", "equalizer", "launcher", "home"};
    private static final String[] CARPLAY_WORDS = {"carplay", "car play", "zlink", "autokit", "carlink"};
    private static final String[] SETTING_WORDS = {"illum", "night", "light", "lamp", "dim", "day",
            "headlight", "backlight", "bright", "dark", "theme", "carplay", "bluetooth", "bt_"};

    // Bluetooth profile id -> plain description. Several are hidden ids used by car units.
    private static final Map<Integer, String> PROFILES = new LinkedHashMap<>();
    static {
        PROFILES.put(11, "Phone audio into stereo (A2DP sink)");
        PROFILES.put(16, "Phone calls into stereo (HFP client)");
        PROFILES.put(12, "Track controls (AVRCP controller)");
        PROFILES.put(17, "Contacts (PBAP client)");
        PROFILES.put(18, "Messages (MAP client)");
        PROFILES.put(2, "Audio out to headphones (A2DP)");
        PROFILES.put(1, "Headset (HFP)");
    }

    private final Map<Integer, BluetoothProfile> proxies = new LinkedHashMap<>();
    private final Map<Integer, String> proxyState = new LinkedHashMap<>();
    private boolean profilesRequested = false;

    private LinearLayout content;
    private TextView luxView;
    private float lux = -1f;
    private SensorManager sensorManager;
    private StringBuilder report = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(16), dp(10), dp(16), 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("Refresh", () -> render()));
        bar.addView(button("1. Allow Bluetooth", () ->
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1)));
        bar.addView(button("2. Music access", this::openNotificationAccess));
        bar.addView(button("3. Brightness access", () -> safeStart(new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())))));
        bar.addView(button("Share report", this::share));
        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.addView(bar);
        root.addView(barScroll);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(40));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestProfilesIfAllowed();
        render();
        Sensor light = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light != null) sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        requestProfilesIfAllowed();
        render();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        lux = e.values[0];
        if (luxView != null) luxView.setText("  Light sensor reading now: " + Math.round(lux) + " lux");
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    // ------------------------------------------------------------------ report

    private void render() {
        content.removeAllViews();
        report = new StringBuilder();
        try { deviceSection(); } catch (Throwable t) { line("Device check failed: " + t, C_WARN); }
        try { appsSection(); } catch (Throwable t) { line("App check failed: " + t, C_WARN); }
        try { bluetoothSection(); } catch (Throwable t) { line("Bluetooth check failed: " + t, C_WARN); }
        try { musicSection(); } catch (Throwable t) { line("Music check failed: " + t, C_WARN); }
        try { lightingSection(); } catch (Throwable t) { line("Lighting check failed: " + t, C_WARN); }
    }

    private void deviceSection() {
        header("1  Device");
        line("Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")", C_TEXT);
        line("Maker/model: " + android.os.Build.MANUFACTURER + " / " + android.os.Build.MODEL
                + " / board " + android.os.Build.BOARD + " / hw " + android.os.Build.HARDWARE, C_TEXT);
        line("Firmware: " + android.os.Build.DISPLAY, C_DIM);
        Rect r = getWindowManager().getMaximumWindowMetrics().getBounds();
        line("Screen: " + r.width() + " x " + r.height() + " px, density "
                + getResources().getDisplayMetrics().densityDpi + " dpi", C_TEXT);
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        line(String.format(Locale.US, "RAM: %.1f GB total, %.1f GB free",
                mi.totalMem / 1073741824.0, mi.availMem / 1073741824.0), C_TEXT);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        line("Music volume: " + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + " of "
                + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + (audio.isVolumeFixed() ? "  (FIXED - apps cannot change it)" : "  (apps can change it)"), C_TEXT);

        PackageManager pm = getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo cur = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        line("Current home app: " + (cur == null ? "none" : cur.activityInfo.packageName), C_TEXT);
        StringBuilder homes = new StringBuilder();
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            homes.append(ri.loadLabel(pm)).append(" [").append(ri.activityInfo.packageName).append("]  ");
        }
        line("Home apps available: " + homes, C_DIM);
    }

    private void appsSection() {
        header("2  Apps");
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<String> hi = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        List<String> carplayPkgs = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm));
            String pkg = ri.activityInfo.packageName;
            String row = label + "  —  " + pkg + " / " + shortName(pkg, ri.activityInfo.name);
            String hay = (label + " " + pkg).toLowerCase(Locale.US);
            if (matches(hay, APP_WORDS)) hi.add(row); else rest.add(row);
            if (matches(hay, CARPLAY_WORDS) && !carplayPkgs.contains(pkg)) carplayPkgs.add(pkg);
        }
        Collections.sort(hi, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(rest, String.CASE_INSENSITIVE_ORDER);
        line(apps.size() + " apps with icons. Likely relevant ones first:", C_DIM);
        for (String s : hi) line("★ " + s, C_HI);
        for (String s : rest) line("   " + s, C_TEXT);

        sub("System screens the launcher could open");
        resolveLine("Bluetooth settings", Settings.ACTION_BLUETOOTH_SETTINGS);
        resolveLine("Display settings", Settings.ACTION_DISPLAY_SETTINGS);
        resolveLine("Sound settings", Settings.ACTION_SOUND_SETTINGS);
        resolveLine("Home app settings", Settings.ACTION_HOME_SETTINGS);
        resolveLine("Main settings", Settings.ACTION_SETTINGS);

        for (String pkg : carplayPkgs) {
            sub("Screens inside " + pkg);
            try {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                line("Version " + info.versionName, C_DIM);
                if (info.activities != null) {
                    for (ActivityInfo a : info.activities) {
                        line((a.exported ? "open: " : "private: ") + shortName(pkg, a.name), a.exported ? C_TEXT : C_DIM);
                    }
                }
            } catch (Exception e) {
                line("Could not read: " + e.getMessage(), C_WARN);
            }
        }
    }

    private void bluetoothSection() {
        header("3  Bluetooth");
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            line("Not allowed yet. Tap '1. Allow Bluetooth' at the top.", C_WARN);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) {
            line("No standard Android Bluetooth. This stereo likely uses its own Bluetooth system.", C_WARN);
            return;
        }
        line("Bluetooth is " + (ad.isEnabled() ? "ON" : "OFF") + ", stereo name: " + ad.getName(), C_TEXT);
        sub("Paired devices");
        java.util.Set<BluetoothDevice> bonded = ad.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) line("None reported to Android.", C_WARN);
        else for (BluetoothDevice d : bonded) {
            BluetoothClass bc = d.getBluetoothClass();
            boolean phone = bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
            line((phone ? "Phone: " : "Device: ") + d.getName() + "  (" + mask(d.getAddress()) + ")", C_TEXT);
        }
        sub("Connections by type");
        for (Map.Entry<Integer, String> p : PROFILES.entrySet()) {
            int id = p.getKey();
            BluetoothProfile proxy = proxies.get(id);
            String state;
            int color = C_DIM;
            if (proxy != null) {
                List<BluetoothDevice> conn = proxy.getConnectedDevices();
                if (conn.isEmpty()) state = "available, nothing connected";
                else {
                    StringBuilder sb = new StringBuilder("CONNECTED: ");
                    for (BluetoothDevice d : conn) sb.append(d.getName()).append("  ");
                    state = sb.toString();
                    color = C_HI;
                }
            } else {
                String s = proxyState.get(id);
                state = s == null ? "checking… tap Refresh" : s;
            }
            line(p.getValue() + ": " + state, color);
        }
    }

    private void musicSection() {
        header("4  Music info");
        ComponentName cn = new ComponentName(this, MediaListener.class);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!nm.isNotificationListenerAccessGranted(cn)) {
            line("Not allowed yet. Tap '2. Music access' and turn on Car Inspector.", C_WARN);
            line("If the switch is greyed out: Settings → Apps → Car Inspector → ⋮ (top right) → "
                    + "'Allow restricted settings', then try again.", C_DIM);
            return;
        }
        MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        List<MediaController> list = msm.getActiveSessions(cn);
        if (list.isEmpty()) {
            line("No app is sharing music info right now. Play a song in CarPlay, then tap Refresh.", C_WARN);
            return;
        }
        for (MediaController c : list) {
            MediaMetadata m = c.getMetadata();
            PlaybackState ps = c.getPlaybackState();
            String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            boolean art = m != null && (m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null
                    || m.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
                    || m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) != null
                    || m.getString(MediaMetadata.METADATA_KEY_ART_URI) != null);
            line("App: " + c.getPackageName(), C_HI);
            line("   Track: " + nz(title) + "   Artist: " + nz(artist) + "   Artwork: " + (art ? "YES" : "no"), C_TEXT);
            line("   Playing state: " + stateName(ps) + "   Play/pause control: "
                    + (ps != null && (ps.getActions() & PlaybackState.ACTION_PLAY_PAUSE | ps.getActions() & PlaybackState.ACTION_PAUSE) != 0 ? "YES" : "unknown"), C_TEXT);
        }
    }

    private void lightingSection() {
        header("5  Lighting");
        Sensor light = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        line("Light sensor: " + (light == null ? "none" : "YES (" + light.getName() + ")"), light == null ? C_WARN : C_HI);
        if (light != null) {
            luxView = line("  Light sensor reading now: " + (lux < 0 ? "waiting…" : Math.round(lux) + " lux"), C_TEXT);
        }
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        int nmode = um.getNightMode();
        String nmName = nmode == UiModeManager.MODE_NIGHT_AUTO ? "automatic" : nmode == UiModeManager.MODE_NIGHT_YES
                ? "always dark" : nmode == UiModeManager.MODE_NIGHT_NO ? "always light" : "custom/other (" + nmode + ")";
        boolean nightNow = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        line("System dark theme setting: " + nmName + ", dark right now: " + (nightNow ? "YES" : "no"), C_TEXT);
        line("Car mode: " + (um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR ? "YES" : "no"), C_DIM);
        try {
            int br = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            line("Brightness: " + br + " of 255, auto-brightness " + (mode == 1 ? "ON" : "off"), C_TEXT);
        } catch (Exception e) {
            line("Brightness: could not read (" + e.getMessage() + ")", C_WARN);
        }
        line("Launcher may change brightness: " + (Settings.System.canWrite(this)
                ? "YES" : "not yet (tap '3. Brightness access')"), C_TEXT);

        sub("All sensors");
        if (sensorManager != null) {
            StringBuilder sb = new StringBuilder();
            for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) sb.append(s.getName()).append(";  ");
            line(sb.length() == 0 ? "none" : sb.toString(), C_DIM);
        }
        sub("Lighting / theme / Bluetooth system values");
        dumpSettings("system", Settings.System.CONTENT_URI);
        dumpSettings("global", Settings.Global.CONTENT_URI);
        dumpSettings("secure", Settings.Secure.CONTENT_URI);
    }

    // ------------------------------------------------------------------ helpers

    private void requestProfilesIfAllowed() {
        if (profilesRequested) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) return;
        profilesRequested = true;
        for (int id : PROFILES.keySet()) {
            boolean ok;
            try {
                ok = ad.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        proxies.put(profile, proxy);
                        runOnUiThread(MainActivity.this::render);
                    }
                    @Override public void onServiceDisconnected(int profile) {
                        proxies.remove(profile);
                        proxyState.put(profile, "disconnected");
                    }
                }, id);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) proxyState.put(id, "not offered to apps");
        }
    }

    private void dumpSettings(String table, Uri uri) {
        int shown = 0;
        try (Cursor c = getContentResolver().query(uri, new String[]{"name", "value"}, null, null, null)) {
            if (c == null) { line(table + ": not readable", C_DIM); return; }
            while (c.moveToNext() && shown < 60) {
                String name = c.getString(0);
                if (name != null && matches(name.toLowerCase(Locale.US), SETTING_WORDS)) {
                    line(table + ": " + name + " = " + c.getString(1), C_TEXT);
                    shown++;
                }
            }
            if (shown == 0) line(table + ": no matching values", C_DIM);
        } catch (Exception e) {
            line(table + ": blocked (" + e.getClass().getSimpleName() + ")", C_DIM);
        }
    }

    private void resolveLine(String label, String action) {
        ResolveInfo ri = getPackageManager().resolveActivity(new Intent(action), 0);
        if (ri == null || ri.activityInfo == null) line(label + ": not found", C_WARN);
        else line(label + ": " + ri.activityInfo.packageName + " / "
                + shortName(ri.activityInfo.packageName, ri.activityInfo.name), C_TEXT);
    }

    private void openNotificationAccess() {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
        i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                new ComponentName(this, MediaListener.class).flattenToString());
        if (!safeStart(i)) safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private boolean safeStart(Intent i) {
        try { startActivity(i); return true; }
        catch (Exception e) { Toast.makeText(this, "This stereo has no screen for that.", Toast.LENGTH_LONG).show(); return false; }
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Car Inspector report")
                .putExtra(Intent.EXTRA_TEXT, report.toString());
        safeStart(Intent.createChooser(i, "Share report"));
    }

    private Button button(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        lp.setMargins(0, 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void header(String t) {
        TextView v = text(t, 24, Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(22), 0, dp(6));
        content.addView(v);
        report.append("\n== ").append(t).append(" ==\n");
    }

    private void sub(String t) {
        TextView v = text(t, 18, C_DIM);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(12), 0, dp(2));
        content.addView(v);
        report.append("-- ").append(t).append('\n');
    }

    private TextView line(String t, int color) {
        TextView v = text(t, 17, color);
        content.addView(v);
        report.append(t).append('\n');
        return v;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        v.setPadding(0, dp(2), 0, dp(2));
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean matches(String hay, String[] words) {
        for (String w : words) if (hay.contains(w)) return true;
        return false;
    }

    private static String shortName(String pkg, String cls) {
        return cls != null && cls.startsWith(pkg) ? cls.substring(pkg.length()) : cls;
    }

    private static String mask(String addr) {
        return addr == null || addr.length() < 5 ? "?" : "…" + addr.substring(addr.length() - 5);
    }

    private static String nz(String s) { return s == null || s.isEmpty() ? "(none)" : s; }

    private static String stateName(PlaybackState ps) {
        if (ps == null) return "unknown";
        switch (ps.getState()) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            default: return "state " + ps.getState();
        }
    }
}
