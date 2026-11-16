# LOKIFFICE — Cross-Platform Hybrid Shift & Reverse-Curfew Copilot

**LOKIFFICE** is a unified **iPhone (PWA + Apple Shortcuts), Web, and Native Android** application built to protect your evening curfew around mandatory hybrid office hours.

Instead of asking *"When do I leave?"* like Google Maps, **LOKIFFICE** works **backward from your personal life**: set when you want to be back home, and it automatically calculates your departure window using live arterial routing, rain radar, and your mandatory shift duration (5h, 6h, 8h, or 9h).

---

## Core Advantages & Features

1. **Reverse-Curfew Commute Engine:** Enter your target home arrival time (e.g., `6:00 PM`) and LOKIFFICE dynamically shifts your `Leave Home By` time based on live road travel duration and wet-road buffers.
2. **Custom Home, Office & Shift Hours:** Pre-configured for **Comcast Perungudi (OMR, Chennai)** with a **5-hour shift**, while allowing any user to customize their Home pin, Office location, and shift duration (`5h / 6h / 8h / 9h`).
3. **1-Tap Navigation Launchers:** Launches pre-configured two-wheeler/route directions directly in Google Maps for both **Route to Office** and **Route Home**.
4. **Live Rain & Weather Radar (`Open-Meteo`):** Monitors real-time temperature and hourly precipitation probability, adding a +20% wet-road buffer during rain and shifting transit advice from **Rapido Bike Taxi** to **Chennai Metro Rail / Covered Cab**.
5. **Hybrid Attendance & CSV Compliance Export:** Tracks weekly check-ins and exports a clean CSV log (`LOKIFFICE-Attendance-Log.csv`) of your in-office timestamps.

---

## Cross-Platform Setup (Single Source of Truth)

### 1. Web & Desktop
- Hosted directly via **GitHub Pages** from the root `index.html` at:
  `https://logesh-1023.github.io/LOKIFFICE/`

### 2. iPhone (iOS PWA + Hands-Free Apple Shortcuts)
1. Open `https://logesh-1023.github.io/LOKIFFICE/` in **Safari** on your iPhone.
2. Tap the **Share** icon → **Add to Home Screen** to install LOKIFFICE as a full-screen standalone app.
3. *(Optional 24/7 Auto Check-In)*: Open the iOS **Shortcuts** app → **Automation** → **+** → **Arrive** (select your Office) → **Run Immediately** → **Open URLs** → `https://logesh-1023.github.io/LOKIFFICE/?checkin=1`.

### 3. Native Android APK (24/7 Hardware Geofence + Deep-Sleep Alarm)
- `MainActivity.kt` loads the live GitHub Pages URL (with Service Worker offline caching) while running native `GeofencingClient` (150m perimeter) and `AlarmManager.setExactAndAllowWhileIdle` in the background.
- Pushing to `main` automatically builds `app-debug.apk` via `.github/workflows/build.yml`.

---

## Repository Structure

```text
LOKIFFICE/
├── index.html                                         # Unified Web, iPhone PWA & Android UI
├── manifest.json                                      # iOS / Android PWA install manifest
├── icon.svg                                           # High-DPI vector app icon
├── sw.js                                              # Offline Service Worker cache
├── README.md                                          # End-to-end documentation
├── .github/workflows/build.yml                        # Automated cloud APK builder
├── build.gradle                                       # Top-level Gradle config
├── settings.gradle                                    # Module & repository definitions
├── gradle.properties                                  # AndroidX & JVM settings
└── app/
    ├── build.gradle                                   # Android SDK 34 & Play Services Location
    └── src/main/
        ├── AndroidManifest.xml                        # Permissions, LOKIFFICE label & icon
        ├── java/com/lokiffce/app/MainActivity.kt      # Native 24/7 Geofence, Alarm & WebView + TTS Bridge
        └── res/
            ├── drawable/ic_launcher_background.xml    # Android adaptive icon background
            ├── drawable/ic_launcher_foreground.xml    # Android adaptive icon foreground
            └── mipmap-anydpi-v26/ic_launcher.xml      # Adaptive icon definition
```
