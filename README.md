# LOKIFFICE v5.0 — Campus Geofence (400m), 3D Parking Radar & Auto Ride-Mode DND

**LOKIFFICE** is a zero-cost (`$0.00`), cross-platform (**Android Native + iPhone PWA + Web**) utility engineered around a **Suzuki Access 125 Ride Connect** + **iQOO Neo 7 (5G)** daily commute to **Chennai One IT SEZ (Comcast North Block)**.

---

## Key Upgrades in v5.0

1. **Chennai One IT SEZ Campus Geofence (`400m` Radius + 1-Tap Desk Lock):**
   - Centered on **Chennai One IT SEZ North Block (`12.946960, 80.231400`)** with a **`400m` campus radius** (`RADIUS_M = 400f`) across both `index.html` and `MainActivity.kt` so entry triggers anywhere across the 20-acre campus.
   - Includes a **Lock Current Desk as Office** button in the setup sheet that syncs your exact desk coordinates and chosen radius (`250m / 400m / 500m / 650m`) directly into the native Android `GeofencingClient` via `AndroidBridge.updateOfficeGeofence(...)`.
2. **Use Case 1 — Zero-Photo "No-Touch" Floor & Bay Finder:**
   - Uses your iQOO Neo 7's **Gyroscope (`TYPE_GYROSCOPE`) + Accelerometer (`TYPE_ACCELEROMETER`) + E-Compass (`TYPE_MAGNETIC_FIELD`)** (zero barometer and zero photos needed).
   - Counts helical/switchback ramp turns during your ride up (`G` -> `L1` -> `L2` -> `L3` -> `L4`), freezes the floor the instant your **Access 125 Ride Connect** disconnects Bluetooth (`ACTION_ACL_DISCONNECTED`), and records your 45-second walk vector to the elevator lobby so you get a clear return distance + cardinal direction at the end of your shift.
3. **Use Case 2 — "Skip-the-Ramp" 3D Multi-Floor Crowd Capacity Radar:**
   - Interactive **3D Isometric 4-Floor Stack** (`L4` through `G`) with real-time congestion guidance (`Go to L2`, `Go to L1`, `All Open`) before you climb the spiral ramp.
4. **Use Case 3 — Automatic "Ride Mode" (Auto-DND):**
   - Listens to `BluetoothDevice.ACTION_ACL_CONNECTED` (when your Access 125 ignition turns on) and Google Play Services `ActivityRecognitionClient` (`IN_VEHICLE` / `ON_BICYCLE`), automatically switching Android into Priority Do Not Disturb (`INTERRUPTION_FILTER_PRIORITY`) while riding and restoring normal ringing (`INTERRUPTION_FILTER_ALL`) the moment you park.
