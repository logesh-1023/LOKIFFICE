# LOKIFFICE v5.1 — Live Firebase Crowd-Synced 3D Parking Radar & Real System Auto-DND

Connected directly to `https://lokiffice-default-rtdb.asia-southeast1.firebasedatabase.app/`.

## What's Fixed & Live in v5.1
1. **Real Live Multi-User Firebase Crowd Sync (Zero Dummy Data):**
   - Connected to your Singapore Firebase Realtime Database (`chennai_one/YYYY-MM-DD/floors/G..L4`).
   - Automatically increments a floor's live parked count when your **Access 125 Ride Connect** disconnects Bluetooth (`ACTION_ACL_DISCONNECTED`) and decrements it when you reconnect (`ACTION_ACL_CONNECTED`) to leave.
   - Includes **Flag Selected Full** and **Flag Selected Open** buttons so any rider can immediately broadcast floor congestion to everyone else on campus.
2. **Real System Do Not Disturb (`AndroidBridge.setRideMode`):**
   - Tapping **Test Ride** (as well as real Access 125 Bluetooth connection or vehicle motion) now directly calls `NotificationManager.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)` in `MainActivity.kt`, displaying the system DND crescent moon icon in your iQOO Neo 7 status bar and silencing calls.
