# Car Launcher — Project Handoff

A custom Android home screen (launcher) for Jessica's aftermarket car stereo. It is built by GitHub Actions and installed by downloading an APK on the stereo. Current version: **2.0** (versionCode 11).

---

## 1. Opening message for a new assistant (copy and paste)

> I'm continuing a project from another AI assistant. It's a custom Android launcher (home screen) for my aftermarket car stereo. The full source code and a handoff document (HANDOFF.md) are attached. Please read HANDOFF.md first.
>
> About me: I don't have a coding background, but I know what I want the screen to look like and do. I install updates by opening a download link in the stereo's browser.
>
> How I like to work:
> - Before building anything, plan with me: agree on the structure, the sections, and any assumptions. Then build exactly what we agreed.
> - Ask me questions whenever you need more information. Multiple-choice questions are easiest.
> - Keep answers short; I'm usually on my phone.
> - Don't use irony or understatement.
> - When you change code, give me complete files (not fragments) and tell me exactly which files to replace in the GitHub repo.
>
> The repo is github.com/jessdbrown2/car-launcher. Every push to `main` that touches `launcher/` rebuilds the app and replaces the download link automatically.
>
> First, summarize what you understand about the project and the current screen, then wait for my first request.

---

## 2. The stereo (confirmed with the Car Inspector app)

| Item | Value |
|---|---|
| Model | QC4250 head unit (Qualcomm SM4250 "Bengal"), firmware SM4250_00034_V001 |
| Android | **11 (API 30)**. The seller listing says Android 15; the device reports 11. |
| Screen | 1280×720, **160 dpi (1 dp = 1 px)**, landscape. The stereo draws its own system bar of buttons (power, home, back, volume) at the left edge. |
| RAM | 4 GB |
| Car | 2008 Honda Pilot |
| Phones | iPhones, using **wireless CarPlay** |

**Apps on the stereo used by the launcher** (package / activity):

| Tile | Package | Activity |
|---|---|---|
| CarPlay | `com.zjinnova.zlink` | `com.zjinnova.android.zlink.features.launcher.CarPlayActivity` |
| Radio | `com.acloud.stub.extradio` | `.QtActivity` |
| Phone switch (Bluetooth app) | `com.xygala.bluetooth` | `.activity.GroupMainActivity` |
| DSP / EQ | `com.xyauto.dspSetting` | `.ui.MainActivity` |
| Car Settings | `com.xyauto.Settings` | `.MainActivity` |

Other useful facts:
- ZLink (CarPlay app, v5.4.97) also exposes `AutoActivity` (Android Auto) and `MirrorActivity`.
- **CarPlay shares no song title, artist, artwork or playback state** with other apps. Its media session exists but is empty.
- Music volume uses `STREAM_MUSIC`, **0–63 steps**, and apps may change it.
- **No light sensor.** Brightness is manual and was at 255/255.
- Android's own Bluetooth is off (`bluetooth_on = 0`). The stereo uses its own Bluetooth chip through the `com.xygala.bluetooth` app, so Android Bluetooth APIs see nothing.
- **Wi-Fi turns off when wireless CarPlay connects**, because the iPhone connects to the stereo over Wi-Fi. The stereo has no internet while CarPlay is in use.
- The stereo exposes a `Headlamp` system setting (0 or 1) and has its own headlight dimming (`lightOffBrightness`, `Lamplet_state`).

---

## 3. What the screen looks like now (v2.0)

**Palette:** pure black background, white text, light grey (`#D0D0D0`) secondary text. Black, white and grey only; no accent color.

**Left rail** (column 116 px wide, 6 tiles of exactly **100×100 px** with equal gaps), top to bottom:
1. CarPlay
2. Radio
3. Phone switch (opens the stereo's Bluetooth app)
4. DSP / EQ
5. Car Settings
6. All apps (grid icon)

- The icons are Jessica's own glossy dark-charcoal tiles with silver artwork, plus a thin medium-grey rim (`#707076`) so they stand out from the black.
- Tap a tile to open its app. Tiles dim slightly while pressed.
- **Long-press a tile** to choose a different installed app for it, or reset it to the default. Choices are saved in SharedPreferences (`tile_0` … `tile_4`).

**Center:**
- A large clock (200 sp, sans-serif-medium, `h:mm`) with the date below it ("Thursday, October 29", 34 sp, grey), centered.
- **Tap the clock area** to show or hide the volume bar. **Long-press it** to open the menu.

**Volume bar** (hidden by default, slides up from the bottom, 860×90 px):
- Mute, −, slider, +, and the level number, all in a metallic style that matches the rail icons (PNG assets drawn with code).
- − and + move 2 steps each. Mute remembers the previous level (`volume_before_mute`).
- Hides **5 seconds** after the last touch. Stays up while you drag the slider.
- **Pops up automatically** when volume changes from the steering wheel or the stereo's knob or buttons (it listens for `android.media.VOLUME_CHANGED_ACTION`).

**All apps grid:** full-screen black overlay with 6 columns of app icons and names. Tap an app to open it. The ✕, Back, or Home closes the grid.

**Menu** (long-press the clock): the title shows the version ("Car Launcher 2.0"), then:
- All apps
- Update launcher (opens the APK download link in Chrome)
- Home app settings
- Android settings

**Back button** on the home screen closes the app grid and otherwise does nothing.

---

## 4. Repo layout

```
car-launcher/
├── .github/workflows/
│   ├── launcher.yml      # builds launcher, publishes release "launcher"
│   └── inspector.yml     # builds inspector, publishes release "inspector"
├── settings.gradle       # includes :inspector and :launcher
├── build.gradle          # Android Gradle Plugin 8.7.3
├── gradle.properties
├── launcher/
│   ├── build.gradle      # minSdk 30, targetSdk/compileSdk 35, Java 17, fixed signing key
│   ├── signing.keystore  # fixed key, password "carlauncher", alias "launcher"
│   └── src/main/
│       ├── AndroidManifest.xml   # HOME intent filter; INTERNET is not used
│       ├── java/com/jessica/carlauncher/MainActivity.java   # the whole app, layout built in code
│       └── res/drawable-nodpi/   # rail_*.png icons, vol_*.png volume art
└── inspector/            # read-only diagnostic app (Car Inspector), no longer needed day to day
```

- The whole launcher is **one Java file** with no XML layouts and no libraries. Views are created in code.
- Package / applicationId: `com.jessica.carlauncher`.
- Permissions: `QUERY_ALL_PACKAGES` only.

---

## 5. How building and updating works

1. Push to `main` on GitHub with changes under `launcher/` (or the gradle files).
2. GitHub Actions (`launcher.yml`) runs `gradle :launcher:assembleDebug` with Gradle 8.10.2 and Java 17.
3. The workflow deletes the old **release tagged `launcher`** and creates a new one with `launcher.apk`.
4. The download link never changes:
   `https://github.com/jessdbrown2/car-launcher/releases/download/launcher/launcher.apk`
5. On the stereo: long-press the clock → **Update launcher**, then tap the downloaded file → **Install**.

Rules to keep:
- **Always bump `versionCode` and `versionName`** in `launcher/build.gradle` for each release.
- **Keep `signing.keystore`.** Debug builds otherwise get a new random key on each CI run, and Android refuses to install an update signed with a different key. (The key is committed because the build has no access to GitHub secrets. It is only used for this personal sideloaded app.)
- The repo is public so the stereo can download without signing in.

---

## 6. Decisions and the reasons for them

| Decision | Reason |
|---|---|
| Rail holds only things CarPlay can't do | Jessica uses Maps, Amazon Music and Phone inside CarPlay |
| No now-playing card or play/pause/skip buttons | CarPlay shares no song info or playback state; controls were removed in 1.2 |
| Weather removed | Wi-Fi turns off when CarPlay connects, so there's no internet in the car |
| Night (headlight) dimming removed in 2.0 | With headlights on, the launcher's dimming stacked with the stereo's own dimming and the screen went black until the car restarted. Use the stereo's built-in dimming in Car Settings instead. |
| Black/white/grey palette, no card behind clock | Matches the second icon set; Jessica's choice |
| Visible All apps tile | Easier than the hidden long-press to reach Chrome, Play Store, etc. |
| "Phone switch" opens the Bluetooth app | Android can't switch CarPlay phones directly |

---

## 7. Version history

| Version | Changes |
|---|---|
| 1.0 | First launcher: blue CarPlay-style dashboard, app rail, now-playing card, clock, Valdosta weather |
| 1.1 | Jessica's first (SVG) icon set, aligned clock, live play/pause icon, weather removed |
| 1.2 | Second (PNG) icon set as full tiles, black/grey palette, centered clock, media controls removed |
| 1.3 | Volume row added under the clock; rail tiles made smaller |
| 1.4 | Higher-contrast text; headlight dimming added |
| 1.5 | Metallic volume controls to match the icons; volume bar moved near the bottom |
| 1.6 | Grey rim on rail tiles; tiles enlarged to 110 px |
| 1.7 | Build with only a version bump (skip) |
| 1.8 | All apps grid tile, one-tap update in the menu, night brightness slider |
| 1.9 | All six rail tiles exactly 100 px; volume bar hides and pops up on tap or volume change |
| 2.0 | Headlight dimming removed entirely (screen stayed black with headlights on) |

---

## 8. Known limits and open items

- **Not yet confirmed on the stereo:** whether the volume bar pops up from steering-wheel buttons (some stereos don't broadcast volume changes to apps).
- **Dropped phone calls / losing service with wireless CarPlay** are a ZLink or wireless issue, not the launcher. Suggested tests: wired CarPlay over USB, turning off Wi-Fi Calling, checking whether it happens in the same spots, and trying a second phone.
- After an update, the browser may save `launcher (1).apk` and so on. Install the newest file and delete old ones in the Files app.
- Android won't let the launcher delete browser downloads or install updates silently.

**Ideas discussed but not built:**
- Open CarPlay automatically a few seconds after the car starts
- Safe starting volume (lower a loud volume at startup)
- Optional extra rail tiles: Android Auto, Music (USB), YouTube

---

## 9. Tips for editing the code

- Rail order, names, default apps and icons are the arrays `TILE_NAMES`, `TILE_DEFAULTS` and `TILE_ICONS` near the top of `MainActivity.java`. Keep them the same length and order.
- Tile size is `RAIL_TILE` (100). The rail column width is set in `onCreate` (116).
- The volume hide delay is `VOLUME_HIDE_MS` (5000).
- New images go in `res/drawable-nodpi/` (PNG, square, transparent background) so Android doesn't rescale them.
- Because 1 dp = 1 px on this screen, design mockups at 1280×720 px.
