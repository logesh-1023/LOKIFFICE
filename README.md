# LOKIFFICE v7.0 — J.A.R.V.I.S. Edition

## Every Failure Fixed & Full Redesign
1. **Headphones Never Trigger DND (`isDefinitelyHeadphoneOrSpeaker` + Vehicle BT Picker + Motion Gate):**
   - Explicitly rejects headphones, TWS earbuds, speakers, and watches.
   - Added a **Paired Vehicle Bluetooth Picker** in Setup so you pick your exact bike/car Bluetooth device.
   - DND activates **only** when your selected Vehicle Bluetooth is connected **AND** you are actually moving (`>= 8 km/h` or `IN_VEHICLE` / `ON_BICYCLE`).
2. **Human-English Parking Locator (Zero Math Jargon in UI):**
   - Kept all sensor fusion and vector calculations strictly in Kotlin; UI displays plain English (*"Parked on 2nd Floor · About 18 steps ahead to the right from the lift lobby"*) on a clean interactive floor tower.
3. **Background Foreground Parking Sensor Service (`CommuteSensorService` — No Bluetooth Required):**
   - Automatically wakes up when you approach campus (`OFFICE_APPROACH` geofence) or start riding (`ActivityRecognition`), fusing Gyroscope ramp turns + Accelerometer sustained uphill ramp pitch + GPS altitude delta even when the phone is locked in your pocket.
4. **Bank-Grade ₹ SMS Parser & Hybrid J.A.R.V.I.S. Intelligence Engine:**
   - Truncates `Avl Bal / Available Balance / Limit` before extracting amounts so account balances (like `₹80,000`) are never counted as debits.
   - Excludes loan offers, promos, collect requests, failed transactions, personal phone numbers, and duplicates. All amounts strictly in **`₹`**.
   - Tap any ledger item to inspect the raw SMS or tap **Ignore** to exclude it instantly.
   - Unified **Today · Spends · Jarvis** 3-tab floating dock with instant exact local analytics, inline spend charts, parking queries, and optional `SmolLM2-360M` offline neural chat.
