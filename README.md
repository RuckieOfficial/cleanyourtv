# CleanYourTV — Cinema & OLED Edition 🎬✨

[![Android TV](https://img.shields.io/badge/Android%20TV-8.0%2B-blue.svg)](https://developer.android.com/tv)
[![Google TV](https://img.shields.io/badge/Google%20TV-Compatible-green.svg)](https://tv.google)
[![OLED Optimized](https://img.shields.io/badge/OLED-True%20Black%20%280%25%20Bleed%29-black.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> A cinematic, OLED-tailored fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv). Features dynamic TMDb 4K backdrops with live metadata, an Apple-inspired Liquid Glass screensaver, adaptive distraction-free app browsing, and instant screen-off audio mode.

---

## 🌟 Highlights & Features

### 🎬 TMDb Movie Hero Panel
- **Unclipped 2-Line Dynamic Title:** Displays the full movie title without awkward cutoff or ellipses.
- **Gold Star Rating:** Visual 5-star rating (`★` and `☆`) combined with precise numeric score.
- **Rich Meta Pill:** Displays release year, up to 3 genre tags, and formatted runtime (e.g. `2h 14m`).
- **Clean Overview:** Dynamically fetched plot synopsis in your preferred language with automatic length budgeting.

### 🔄 Autonomous 4K Backdrop Fetcher
- **Zero-Maintenance Background Service:** Automatically updates wallpapers on the TV once every 24 hours without requiring any external server or script.
- **Curated Queue:** Downloads 12 trending popular releases + 8 Disney/Pixar family favorites in native 4K resolution.
- **Overview Quality Check:** Filters out any releases without rich overviews to guarantee pristine visuals.
- **Multi-Language Support:** Defaults to English (`en-US`), with one-line config support for Czech (`cs-CZ`), Spanish, German, French, etc.

### 🛹 Parallax Hero Panel Offscreen Glide
- When scrolling down from the dock into the app grid, the movie hero metadata smoothly glides upward and fades offscreen (`translationY` + `alpha`).
- Returns seamlessly when moving back to the dock or top navigation pills.

### 🕶️ Adaptive 75% Distraction-Free Grid Dimming
- When exploring apps below the main dock, the wallpaper smoothly transitions to an adaptive 75% dark scrim.
- Ensures 100% legibility and eliminates background visual fatigue while searching for apps.

### 🚫 Ambient Glare Elimination
- Removes aggressive full-room color floodlights that tint the entire TV orange or red when hovering over apps like Netflix or YouTube.
- Replaced with a subtle, focused glass card glow that keeps the living room ambiance serene.

### 🛡️ Protective Gradient Scrims
- Custom left and top soft-gradient vignettes protect the clock, movie descriptions, and header pills against bright white backdrops.

### 💎 Apple Liquid Glass OLED Screensaver
- **True Pitch-Black Background:** Pure `0xFF000000` background shuts off OLED self-lit pixels completely, preventing burn-in and saving energy.
- **Glowing Typography:** Large 140sp font with soft, diffused white luminescence inspired by Apple's Liquid Glass interface.

### ➕ "+ Add" Dock Quick Tile
- Dedicated interactive `+ Add` tile directly in the favorites dock row.
- One click on the remote brings you straight to your app library to pin favorites.

### 🎨 32-bit True Color (ARGB_8888)
- Native 32-bit color rendering mode eliminates color banding on OLED & high-end HDR panels.

### ⏸️ Instant Screen-Off (Audio-Only Music Mode)
- **Long-press the Pause button** on your remote to turn off the OLED screen instantly while Spotify, Apple Music, or radio continues playing in the background.
- Press any button (or adjust volume) to immediately wake the display with zero latency.

---

## 🇨🇿 Přehled funkcí (Česky)

| Funkce | Popis |
|---|---|
| **Filmový panel z TMDb** | Velký 2řádkový název bez ořezu, zlaté hvězdičky s hodnocením (`★ 8.2`), rok, žánry, stopáž a popis. |
| **Auto-rotace tapet** | Plynulý crossfade každou minutu s automatickým přepsáním filmových metadat. |
| **Animace panelu mimo view** | Při sjezdu dolů do aplikací filmový text plynule odpluje nahoru a uvolní prostor. |
| **Adaptivní 75% ztmavení** | Při hledání aplikací pod dockem se tapeta ztmaví o 75 % pro nerušené a čisté čtení ikon. |
| **Vypnutí ambientní záplavy** | Žádné přebarvování celé TV do křiklavé červené či oranžové podle ikony – pouze jemný glow rámečku. |
| **Ochranné gradienty (scrims)** | Levý i horní přechod pro 100% čitelnost hodin a textů i na nejsvětlejším podkladu. |
| **Apple Liquid Glass spořič** | Čistě černé OLED pozadí (vypnuté pixely) s jemně zářivým velkým fontem (140sp). |
| **Dlaždice „+ Přidat“ v docku** | Rychlé přidání aplikací do oblíbených přímo z plochy. |
| **Automatický 4K TMDb fetcher** | Denně sám stahuje 12 populárních + 8 Disney filmů v originální 4K kvalitě s kontrolou popisků. |
| **32bit True Color (ARGB_8888)** | Nulový color banding (pruhování přechodů) na OLED panelu. |
| **Vypnutí obrazovky (Pauza)** | Podržením tlačítka Pauza na ovladači se okamžitě vypne obraz pro poslech hudby. |

---

## ⚙️ Configuration & Setup

### 1. TMDb API Key Setup
You can configure your free [TMDb API Key](https://www.themoviedb.org/settings/api) in any of the following ways:

#### Option A: In Theme `config.json` (Recommended, No Recompilation)
Add your key and preferred language to your theme's `config.json`:
```json
{
  "tmdb_api_key": "YOUR_TMDB_API_KEY_HERE",
  "tmdb_language": "en-US",
  "wallpaper_captions": true,
  "wallpaper_true_color": true
}
```
*(For Czech language descriptions, set `"tmdb_language": "cs-CZ"`)*.

#### Option B: In `launcher/local.properties` (Build Time)
Add to `launcher/local.properties`:
```properties
tmdb.api.key=YOUR_TMDB_API_KEY_HERE
```

#### Option C: Environment Variable
```bash
export TMDB_API_KEY="YOUR_TMDB_API_KEY_HERE"
```

### 2. Custom Dock Apps Setup (Optional)
By default, the launcher displays standard international media apps (Netflix, YouTube, Prime Video, Disney+, etc.). You can override the dock row with your own favorite packages without modifying git-tracked files:

#### In `launcher/local.properties`:
```properties
dock.apps=org.smarttube.stable,com.netflix.ninja,net.cme.voyo.cz.tvapp,to.prehraj.app,com.goodtoolapps.zeus
```

#### Or via Environment Variable:
```bash
export DOCK_APPS="org.smarttube.stable,com.netflix.ninja,net.cme.voyo.cz.tvapp,to.prehraj.app,com.goodtoolapps.zeus"
```

---

## 🛠️ Building & Installing

### Prerequisites
- JDK 17
- Android SDK Platform 34 & Build-Tools 34.0.0
- ADB enabled on your TV

### Build Release APK
```bash
cd launcher
./gradlew assembleRelease
```
The optimized APK will be generated at:
`launcher/app/build/outputs/apk/release/app-release.apk` (under 480 KB).

### Install on TV over ADB
```bash
TV_HOST=<your-tv-ip>
adb connect $TV_HOST:5555
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
```

### Apply Cinematic Theme
```bash
TV_HOST=<your-tv-ip> tools/theme.sh themes/cinematic
```

---

## 📜 Credits & License
- Based on the minimalist, ad-free [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin23.
- Movie data & backdrops provided by [TMDb](https://www.themoviedb.org/).
- Released under the [MIT License](LICENSE).
