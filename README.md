# LightweightBaby

**Lightweight Baby** is a personal fitness and nutrition tracker. It logs calories from a photo or a spoken description, tracks steps from Samsung Health, logs workouts set by set, and suggests daily push/pull/legs workouts.

The repository holds three versions of the app:

| Folder | What it is | AI | Steps |
|---|---|---|---|
| [`lightweight-baby-android/`](lightweight-baby-android) | Full Android app (**Lightweight Baby**) | NVIDIA NIM, your own API key | Automatic, via Health Connect |
| [`lightweight-baby.html`](lightweight-baby.html) | Web version that runs as a claude.ai artifact | Claude, on your Claude plan | Entered by hand |
| [`lightweight-baby-launcher/`](lightweight-baby-launcher) | Tiny Android app that opens the claude.ai version from a home-screen icon | Same as the web version | Same as the web version |

## Features (Android app)

- **Food:** photo or text calorie estimates, voice input, an editable review before saving, protein/carbs/fat with AI fill-in for hand-entered foods, and a 7-day chart.
- **Steps:** synced from Samsung Health (or any app) through Health Connect, with a source breakdown and a fix-it guide.
- **Workouts:**
  - AI plans built around your split (Push / Pull / Legs, Upper / Lower, Full body), recent training and injuries
  - per-set kg × reps logging, with weights prefilled from your last session and an estimated 1-rep max
  - edit any plan, and save your own reusable workouts
- **Music:** now playing with controls and a mini player on every screen, plus Spotify playlist browsing and playback (Spotify Web API with PKCE).
- **Profile:** targets from the Mifflin–St Jeor formula, recomp and fat-loss pacing, macros, water and a goal date; Light/Dark/System appearance; an ⓘ guide on every card.

## Building the Android app

You need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

1. Create `lightweight-baby-android/local.properties` containing `sdk.dir=C:\\path\\to\\Android\\Sdk`. Git ignores this file on purpose.
2. Run:

```bash
cd lightweight-baby-android
gradle assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the debug key, so they're fine for sideloading but not for the Play Store.

### First run

1. **Profile → NVIDIA NIM API key:** get a free key at build.nvidia.com. The app uses `nvidia/nemotron-3.5-super-vl-120b-a12b`.
2. **Move → Connect Samsung Health:** allow Steps in Samsung Health → Settings → Health Connect.
3. **Music** (optional):
   - Create a Spotify developer app with the redirect URI `fuelstride://spotify` and paste its Client ID into the Music tab. Spotify requires Premium for developer apps.
   - Allow notification access so the app can show what's playing.

No API keys, tokens or personal data are stored in this repository. Keys are entered in the app and kept in its private storage on the phone.

## Project layout

```
lightweight-baby-android/app/src/main/
  assets/index.html        UI (HTML/CSS/JS) rendered in a WebView
  java/com/fuelstride/app/
    MainActivity.kt        WebView host, JS bridge, Health Connect, camera, voice, theme
    Nim.kt                 NVIDIA NIM chat completions (streaming)
    Spotify.kt             Spotify Web API + PKCE sign-in
    MediaHub.kt            Now-playing reader and media controls
    PrivacyActivity.kt     Health Connect permission rationale
```
