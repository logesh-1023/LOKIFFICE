# LOKIFFCE AI — Personal Commute & 5-Hour Shift OS

**LOKIFFCE** is a zero-cost, privacy-first, 24/7 automated office shift and commute command center built specifically for commuting to **Comcast India Engineering Center (Perungudi, OMR, Chennai)** and tracking a strict **5-hour office shift**.

It combines a **native Android background engine (Kotlin)** with a **dynamic HTML5/JS dashboard (`WebView`)**, compiled automatically in the cloud via **GitHub Actions**.

---

## 1. End-to-End System Architecture

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                        ANDROID OS HARDWARE LAYER                        │
│                                                                         │
│  [GeofencingClient] ──(150m Comcast Perimeter)──▶ [GeofenceReceiver]    │
│                                                          │              │
│                                         (Locks IN Time + │ Once/Day)    │
│                                                          ▼              │
│  [AlarmReceiver] ◀──(5-Hour Exact Wakeup)─────── [AlarmManager]         │
│       │                                                                 │
│       └──▶ Fires Loud Alarm Ringtone + High-Priority Notification       │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
                        (Opens / Syncs UI State)
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      LOKIFFCE WEBVIEW DASHBOARD                         │
│                                                                         │
│  • Home Location Setup (GPS Pin or Geocoded Address)                    │
│  • Dynamic Commute Buffer Calculator (Leave Home / IN / OUT / Home)     │
│  • Live Traffic & Road Corridor AI Briefing                             │
│  • Smart Transit Advisor (Chennai Metro Rail vs. Rapido Bike Taxi)      │
│  • Live 5H Shift Countdown + IN-Time Nudge Controls (-15m/-5m/+5m)      │
│  • Automated Weekly Attendance Streak (Mon–Sun via localStorage)        │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
                      (Free Routing & Traffic APIs)
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     EXTERNAL ROUTING & GEOCODING APIS                   │
│                                                                         │
│  1. OpenStreetMap Nominatim API ──▶ Converts Home Address to Lat/Lng    │
│  2. OSRM Public Routing API     ──▶ Calculates Road Distance & Base ETA │
│  3. Google Routes API (Optional)──▶ Live TRAFFIC_AWARE Drive Duration   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Core Integrated Features

### A. 24/7 Native Android Background Engine (`MainActivity.kt`)
* **Zero-Drain OS Geofencing (`GeofencingClient`):** Registers a `150m` circular geofence around Comcast India Engineering Center (`12.947238, 80.229203`) directly with Android's location coprocessor. The app consumes **0% CPU** in the background until you physically enter the office zone.
* **Hands-Free Once-Per-Day Check-In (`GeofenceReceiver`):** The moment you cross into the Comcast perimeter—even if the app is swiped closed—Android wakes the receiver, locks in your arrival timestamp in `SharedPreferences` (keyed by `YYYY-MM-DD` so lunch breaks don't overwrite your morning IN time), and fires an instant check-in notification.
* **Deep-Sleep 5-Hour Hardware Alarm (`AlarmManager`):** Uses `setExactAndAllowWhileIdle(RTC_WAKEUP)` to schedule a system-level alarm for `IN + 5 hours`. Even if your phone is locked in Doze mode in your pocket, Android wakes the device, plays your default alarm ringtone, and displays a **"5 Hours Complete! Time to Leave"** alert.
* **Adaptive Vector Launcher Icon:** Custom dark-mode cyan/purple clock-and-arc vector icon (`ic_launcher_background.xml` + `ic_launcher_foreground.xml`) that scales crisply across all Android launchers.

### B. Dynamic Route & Commute Planner (`index.html`)
* **Configurable Home Pin:** Users can either lock their exact front-door coordinates using **Use My Current GPS Location as Home** or type any Chennai neighborhood/address.
* **Dynamic Travel Buffers:** Replaces static `1h` travel assumptions with real road distance (`km`) and traffic-adjusted travel duration (`mins`) for both the **Home → Office** and **Office → Home** legs.
* **Backward Schedule Solver:** Given your target **"Back Home By"** time (or 1-tap presets: `5 PM`, `6 PM`, `7 PM`, `8 PM`), it computes:
  * **Leave Home At:** `Target Home − (Dynamic Return Travel + 5h Shift + Dynamic Morning Travel)`
  * **Office IN:** `Leave Home + Dynamic Morning Travel`
  * **Office OUT:** `Office IN + 5 Hours`
  * **Back Home:** `Office OUT + Dynamic Return Travel`

### C. AI Route Copilot & Smart Transit Advisor
* **Live Corridor Traffic Classification:** Categorizes your route into `CLEAR FLOW`, `MODERATE`, or `HEAVY` based on the ratio between live traffic duration and baseline free-flow duration, displaying exact delay minutes (`+Xm delay`).
* **Smart Transit Recommendation (`Rapido` vs. `Chennai Metro`):** Evaluates actual road distance (`km`) alongside live congestion severity:
  * **Short hops (`≤ 6 km`):** Recommends **Rapido Bike Taxi** to avoid station walk/transfer overhead.
  * **Long routes (`> 12 km`) + Heavy Traffic:** Recommends **Chennai Metro Rail (Little Mount / Alandur hubs) + Last-Mile Rapido feeder** to bypass arterial choke points.
  * **Mid-range + Heavy Traffic:** Recommends **Rapido Bike Taxi** for two-wheeler lane-filtering time savings.

### D. Automated Weekly Streak & Shift Controls
* **Client-Side Persistence (`localStorage`):** Automatically logs daily check-in timestamps and lights up the **M–T–W–T–F–S–S** weekly attendance pills without needing a backend database.
* **Manual Overrides:** Includes **`Log IN Now`**, **`-15m IN`**, **`-5m IN`**, **`+5m IN`**, and **`Reset`** buttons for instant manual adjustments.

---

## 3. APIs & Services Used (100% Free Tier)

| Service / API | Endpoint / SDK | Purpose | Cost & Auth |
| :--- | :--- | :--- | :--- |
| **Android Play Services Location** | `com.google.android.gms:play-services-location:21.2.0` | OS-level `GeofencingClient` for 24/7 background arrival detection. | **Free** (On-device OS API, no key required) |
| **Android AlarmManager & RingtoneManager** | Native Android SDK (`API 26–34`) | Exact Doze-bypassing 5-hour leave alarm & native sound playback. | **Free** (Native OS API) |
| **HTML5 Geolocation API** | `navigator.geolocation.watchPosition` | Live distance readout (`m` / `km` from Comcast) inside the UI and setting GPS Home pin. | **Free** (Browser/WebView API) |
| **OpenStreetMap Nominatim API** | `https://nominatim.openstreetmap.org/search` | Forward geocoding: converts typed home neighborhood/street names into Latitude & Longitude. | **Free** (No API key required) |
| **OSRM Public Routing Engine** | `https://router.project-osrm.org/route/v1/driving/...` | Calculates exact turn-by-turn road distance (`km`), road corridor name, and baseline drive time, paired with a Chennai peak-hour congestion model. | **Free** (No API key required) |
| **Google Routes API *(Optional)*** | `https://routes.googleapis.com/directions/v2:computeRoutes` | Optional upgrade if a user supplies their own restricted Google Cloud key for real-time `TRAFFIC_AWARE` Google Maps durations. | **Free** up to 10,000 requests/month |

---

## 4. Repository File Structure

```text
LOKIFFCE/
├── .github/
│   └── workflows/
│       └── build.yml                          # GitHub Actions CI/CD workflow to compile APK
├── app/
│   ├── build.gradle                           # App dependencies, SDK 34 config, package ID
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml            # Background location, exact alarm & icon declarations
│           ├── assets/
│           │   └── index.html                 # Complete LOKIFFCE AI WebView UI & routing logic
│           ├── java/com/lokiffce/app/
│           │   └── MainActivity.kt            # Kotlin WebView host, GeofenceReceiver & AlarmReceiver
│           └── res/
│               ├── drawable/
│               │   ├── ic_launcher_background.xml  # Midnight navy circular adaptive icon backdrop
│               │   └── ic_launcher_foreground.xml  # Cyan 5h progress arc & clock hand vector
│               └── mipmap-anydpi-v26/
│                   └── ic_launcher.xml        # Adaptive icon definition for Android 8+
├── build.gradle                               # Root Gradle plugin versions (AGP 8.3, Kotlin 1.9.22)
├── gradle.properties                          # AndroidX & JVM memory flags
└── settings.gradle                            # Project module configuration
```

---

## 5. Cloud Build & Deployment Pipeline (GitHub Actions)

1. **Trigger:** Any `git push` or file commit to `main` (or manual dispatch from the **Actions** tab) spins up an `ubuntu-latest` runner on GitHub.
2. **Environment Setup:** Installs **JDK 17 (Temurin)** and **Gradle 8.7**.
3. **Compilation:** Executes `gradle assembleDebug` to package the Kotlin receivers, vector icon resources, and `index.html` asset into `app/build/outputs/apk/debug/app-debug.apk`.
4. **Artifact Delivery:** Uploads the compiled APK as a downloadable **`LOKIFFCE-app`** artifact directly on the workflow summary page.

---

## 6. Required Android Permissions Checklist

After installing `app-debug.apk` on your Android device, verify these three settings once for uninterrupted 24/7 operation:
1. **Location Permission:** `Settings > Apps > LOKIFFCE > Permissions > Location` → **Allow all the time** (with *Use precise location* enabled).
2. **Battery Optimization:** `Settings > Apps > LOKIFFCE > Battery` → **Unrestricted** (prevents OEM battery savers from delaying the 5-hour hardware alarm).
3. **Notifications & Alarms:** Allow **Notifications** and **Alarms & reminders** when prompted on first launch.
