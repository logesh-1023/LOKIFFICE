# LOKIFFICE: Zero-Cost Cross-Platform Spatial-Temporal Optimization & Shift Compliance Architecture

## 1. System Rationale & Zero-Infrastructure Philosophy

Commercial hybrid-work and commute applications rely on centralized cloud servers, paid geocoding/routing SKUs, and continuous background GPS polling—introducing monthly recurring costs, API rate limits, and battery drain.

**LOKIFFICE** solves this using a **Single-Source-of-Truth Decentralized Architecture** that runs across **Android (Native APK)**, **Apple iOS (Standalone PWA + Shortcuts)**, and **Desktop/Mobile Web** at **$0.00 operational cost**. All UI, business logic, and state live in a single root `index.html` served over HTTPS by GitHub Pages, bridged to native OS hardware APIs where background execution is required.

---

## 2. Distributed System Topology

```text
+---------------------------------------------------------------------------------+
|                        DISTRIBUTED CLIENT ECOSYSTEM                             |
|                                                                                 |
|  +--------------------+    +---------------------+    +----------------------+  |
|  |  Android Native    |    |  iOS Safari (PWA)   |    |  Desktop/Mobile Web  |  |
|  | (Geofence + Alarm) |    |  (Standalone App)   |    |   (HTTPS Browser)    |  |
|  +---------+----------+    +----------+----------+    +----------+-----------+  |
|            |                          |                          |              |
|            +--------------------------+--------------------------+              |
|                                       |                                         |
|                                       v                                         |
|                    +--------------------------------------+                     |
|                    | GitHub Pages Edge CDN (Static Host)  |                     |
|                    +------------------+-------------------+                     |
|                                       |                                         |
+---------------------------------------|-----------------------------------------+
                                        | HTTPS / REST (Zero API Keys)
                                        v
                     +--------------------------------------+
                     | Open-Source APIs:                    |
                     | • Photon Komoot (Geocoding)          |
                     | • OSRM (Road Graph Routing)          |
                     | • Open-Meteo (Live Rain Radar)       |
                     +--------------------------------------+
```

### Client-Side State & Storage Schema
Rather than maintaining a backend database, LOKIFFICE isolates user data locally inside `localStorage` and `SharedPreferences`:
* `lokiffice_in_YYYY-MM-DD`: Stores the immutable Unix epoch timestamp (`ms`) of first arrival for idempotent daily check-in tracking.
* `lokiffice_home` / `lokiffice_office`: Persists JSON coordinate pairs `{ lat, lng, label }` for custom origin/destination pairs.
* `lokiffice_shift_hours`: Stores user-configurable shift durations (`5`, `6`, `8`, or `9` hours).

---

## 3. Open API Orchestration & Code Connections

LOKIFFICE chains three keyless, open-source REST APIs directly inside `index.html` to compute real-time commute windows without billing accounts or quotas.

### 3.1 Street & Landmark Geocoding (`Photon Komoot / OSM`)
* **Endpoint:** `https://photon.komoot.io/api/?q={query}&lat={lat}&lon={lon}&limit=1`
* **How It Connects:** Converts typed street names or landmarks into `[lng, lat]` coordinates, or directly parses raw `lat, lng` strings pasted from Google Maps via regex (`/^(-?\d+\.\d+)\s*,\s*(-?\d+\.\d+)$/`).
* **Zero-Cost Advantage:** Powered by OpenStreetMap vector data with zero API keys or commercial billing gates.

### 3.2 Arterial Road Routing (`OSRM Public Engine`)
* **Endpoint:** `https://router.project-osrm.org/route/v1/driving/{homeLng},{homeLat};{officeLng},{officeLat}?alternatives=true&overview=false`
* **How It Connects:** Uses Contraction Hierarchies over the regional road graph to calculate actual road distance ($d$) and free-flow duration ($T_{\text{base}}$) rather than straight-line distance:

```javascript
const osrmUrl = `https://router.project-osrm.org/route/v1/driving/${homeLng},${homeLat};${officeLng},${officeLat}?alternatives=true&overview=false`;
const response = await fetch(osrmUrl);
const data = await response.json();
const bestRoute = data.routes[0];

state.distanceKm = +(bestRoute.distance / 1000).toFixed(1);
state.baseMins = Math.max(10, Math.round(bestRoute.duration / 60));
state.roadSummary = bestRoute.legs?.[0]?.summary || "Primary Corridor";
```

### 3.3 Live Weather Radar & Friction Modeling (`Open-Meteo API`)
* **Endpoint:** `https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lng}&current=temperature_2m,precipitation,rain&hourly=precipitation_probability&forecast_days=1`
* **Dynamic Travel Time Equation:** LOKIFFICE adjusts $T_{\text{base}}$ using temporal congestion multipliers ($F_{\text{time}}$) and a meteorological wet-road friction penalty ($\Delta_{\text{rain}}$):

$$T_{\text{live}} = \text{round}\left(T_{\text{base}} \times \left(F_{\text{time}}(h) + \Delta_{\text{rain}}\right)\right)$$

Where:
* **Peak Windows** ($08:00\text{--}10:00$ and $17:00\text{--}20:00$): $F_{\text{time}}(h) = 1.50$
* **Midday Windows** ($11:00\text{--}16:00$): $F_{\text{time}}(h) = 1.25$
* **Off-Peak Windows**: $F_{\text{time}}(h) = 1.05$
* **Wet-Road Penalty**: If current rain $> 0.1\text{ mm}$ or hourly precipitation probability $P_{\text{rain}} \ge 55\%$, $\Delta_{\text{rain}} = 0.20$ and the transit engine shifts recommendations from two-wheelers (Rapido) to covered rail (Chennai Metro).

### 3.4 Reverse-Curfew Temporal Solver
Instead of forward routing ($\text{Leave} \to \text{Arrive}$), LOKIFFICE solves backward from the user's personal evening curfew ($T_{\text{target}}$):

$$\begin{aligned}
T_{\text{back\_home}} &= T_{\text{target}} \\
T_{\text{office\_out}} &= T_{\text{back\_home}} - T_{\text{live}} \\
T_{\text{office\_in}}  &= T_{\text{office\_out}} - (\text{ShiftHours} \times 3600\text{s}) \\
T_{\text{leave\_home}} &= T_{\text{office\_in}} - T_{\text{live}}
\end{aligned}$$

---

## 4. Native Android Hardware Bridge (`MainActivity.kt`)

Because mobile browsers suspend background JavaScript when locked, the Android APK wraps `https://logesh-1023.github.io/LOKIFFICE/` inside a native container that delegates background work to low-power OS subsystems.

### 4.1 Zero-Battery Hardware Geofencing (`GeofencingClient`)
Instead of polling GPS every minute, `MainActivity.kt` registers a circular hardware geofence ($R = 150\text{ m}$) with Google Play Services Location:
* The OS uses passive cell/Wi-Fi triangulation until the device crosses the 150m perimeter, waking `GeofenceReceiver` only on `GEOFENCE_TRANSITION_ENTER`.
* `GeofenceReceiver` checks `SharedPreferences` for today's date (`yyyy-MM-dd`). If unlogged, it writes the arrival timestamp and fires a high-priority notification.

### 4.2 Doze-Resilient Exit Alarm (`AlarmManager`)
At check-in, `GeofenceReceiver` schedules an exact hardware wake-up interrupt for $T_{\text{out}} = T_{\text{in}} + 5\text{ hours}$:

```kotlin
val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
val alarmIntent = Intent(context, AlarmReceiver::class.java)
val pi = PendingIntent.getBroadcast(
    context, 2, alarmIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, outTime, pi)
```

`setExactAndAllowWhileIdle` guarantees the alarm rings on time even if the phone has been idle in deep Doze mode for hours.

### 4.3 Native Text-To-Speech Bridge (`@JavascriptInterface`)
Android `WebView` does not support the browser `window.speechSynthesis` API. `MainActivity.kt` exposes Android's native `TextToSpeech` engine directly to JavaScript:

```kotlin
webView.addJavascriptInterface(object {
    @JavascriptInterface
    fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LOKIFFICE_BRIEFING")
        }
    }
}, "AndroidTTS")
```

In `index.html`, `speakBriefing()` detects `window.AndroidTTS` inside the APK and falls back to `window.speechSynthesis` on Safari/Chrome browsers.

---

## 5. iOS PWA & Offline Service Worker Layer

### 5.1 Network-First Offline Caching (`sw.js`)
To ensure instant startup in basement parking or elevators with zero signal, `sw.js` intercepts `GET` requests, caches fresh responses from GitHub Pages, and serves cached assets automatically when offline:

```javascript
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE_NAME).then((c) => c.put(e.request, copy));
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});
```

### 5.2 iOS Shortcuts Automation Hook (`?checkin=1`)
On iPhone/iPad (`manifest.json` + Safari *Add to Home Screen*), background web geofencing is restricted by iOS. Users pair an **Apple Shortcuts Personal Automation** (*Arrive at Office* $\to$ *Open URL `https://logesh-1023.github.io/LOKIFFICE/?checkin=1`* + *Start 5-Hour Timer*). On load, `index.html` parses `?checkin=1`, records the arrival timestamp, and cleans the URL via `history.replaceState`.

---

## 6. Zero-Dollar Cost & Infrastructure Matrix

| Layer | Traditional Paid Stack | LOKIFFICE Zero-Cost Stack | Monthly Cost |
| :--- | :--- | :--- | :--- |
| **Web & PWA Hosting** | AWS / Vercel Pro ($20/mo) | GitHub Pages HTTPS Edge CDN | **$0.00** |
| **Geocoding & Routing** | Google Maps Routes & Places API | Photon Komoot + OSRM Public API | **$0.00** |
| **Weather & Rain Radar** | Commercial Weather APIs | Open-Meteo Forecast API | **$0.00** |
| **Database & Compliance** | Firebase / PostgreSQL | Client `localStorage` + CSV Export | **$0.00** |
| **Android CI/CD Build** | Paid Build Runners | GitHub Actions (`ubuntu-latest` Gradle) | **$0.00** |
| **Total Expenditure** | **$50.00+ / month** | **100% Free & Self-Contained** | **$0.00** |
