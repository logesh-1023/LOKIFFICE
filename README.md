# LOKIFFICE — Automated Office Shift & Smart Commute Copilot

**LOKIFFICE** is a zero-cost, 24/7 native Android application and progressive web dashboard built for a **5-hour on-site office shift** at **Comcast India Engineering Center (Perungudi, OMR, Chennai)** with dynamic door-to-door commute routing.

---

## Core Capabilities

### 1. 24/7 Native Android Background Engine (Zero Battery Drain)
- **Hardware Geofencing (`GeofencingClient`):** Registers a 150-meter circular perimeter around Comcast Perungudi (`12.947238, 80.229203`) directly with Android OS location services. The app remains asleep until you cross the office boundary.
- **Once-Per-Day Auto Check-In:** Automatically logs your exact arrival timestamp, sends an instant Android notification, and ignores subsequent entry/exit events (such as stepping out for lunch) so your morning check-in stays locked.
- **Exact Deep-Sleep Hardware Alarm (`AlarmManager`):** Schedules `setExactAndAllowWhileIdle` for `IN + 5 hours`, waking your phone from Doze mode to trigger a loud alarm and notification when your shift ends.

### 2. Dynamic Route & Live Weather Copilot (100% Free, Zero API Keys)
- **Door-to-Door Home Pinning (`Photon Komoot` + GPS):** Set your exact home location via street/landmark search, Google Maps `lat, lng` coordinates, or 1-tap current GPS lock.
- **Arterial Road Routing (`OSRM`):** Computes actual road distance and baseline travel duration between your home and Comcast Perungudi, dynamically adjusting your `Leave Home By`, `Office IN`, `Office OUT`, and `Back Home` schedule.
- **Live Rain & Weather Radar (`Open-Meteo`):** Monitors real-time temperature and hourly precipitation probability at Perungudi. Automatically adds a +20% wet-road buffer during rain and shifts transit recommendations from **Rapido Bike Taxi** to **Chennai Metro Rail**.
- **Native Voice Briefing (`Web SpeechSynthesis`):** Reads your departure time, travel duration, and optimal transit mode aloud with a single tap.

### 3. Apple HIG-Inspired OLED Interface
- Pure `#000000` OLED background with `#1C1C1E` grouped cards, hairline `0.5px` separators, and `Inter` + `-apple-system` typography with `tabular-nums` alignment so digits never jitter during live countdowns.
- Includes a custom adaptive vector launcher icon (`@mipmap/ic_launcher`).

---

## Project Structure

```text
LOKIFFCE/
├── .github/workflows/build.yml                        # Automated cloud APK builder
├── build.gradle                                       # Top-level Gradle config
├── settings.gradle                                    # Module & repository definitions
├── gradle.properties                                  # AndroidX & JVM settings
├── README.md                                          # End-to-end documentation
└── app/
    ├── build.gradle                                   # Android SDK 34 & Play Services Location
    └── src/main/
        ├── AndroidManifest.xml                        # Background location, alarm & icon config
        ├── assets/index.html                          # LOKIFFICE Apple-styled UI & routing engine
        ├── java/com/lokiffce/app/MainActivity.kt      # Native GeofenceReceiver & AlarmReceiver
        └── res/
            ├── drawable/ic_launcher_background.xml    # Vector icon background
            ├── drawable/ic_launcher_foreground.xml    # Vector icon foreground
            └── mipmap-anydpi-v26/ic_launcher.xml      # Adaptive icon definition
```

---

## Cloud APK Build & Installation (No Software Required)

1. Upload all files in this archive (including `.github/workflows/build.yml`) to your GitHub repository.
2. Open the **Actions** tab on GitHub and wait ~90 seconds for **Build LOKIFFICE APK** to complete.
3. Download **`LOKIFFCE-app`** under **Artifacts**, extract `app-debug.apk`, and install it on your Android device.
4. On first launch, grant **Location → Allow all the time**, allow **Notifications**, and set **Settings → Apps → LOKIFFICE → Battery** to **Unrestricted**.
