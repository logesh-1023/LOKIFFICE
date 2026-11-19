package com.lokiffce.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var activityClient: ActivityRecognitionClient
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val liveAppUrl = "https://logesh-1023.github.io/LOKIFFICE/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannels()
        tts = TextToSpeech(this, this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        activityClient = ActivityRecognition.getClient(this)

        val webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.setBackgroundColor(0xFF05070B.toInt())

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun speak(text: String) {
                if (ttsReady) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LOKIFFICE_JARVIS")
                }
            }

            @JavascriptInterface
            fun updateOfficeGeofence(lat: Double, lng: Double, radiusMeters: Float) {
                getSharedPreferences("lokiffce", Context.MODE_PRIVATE).edit()
                    .putFloat("office_lat", lat.toFloat())
                    .putFloat("office_lng", lng.toFloat())
                    .putFloat("office_radius", radiusMeters)
                    .apply()
                registerOfficeGeofence()
            }

            @JavascriptInterface
            fun scheduleShiftEndAlarm(outTimeMillis: Long) {
                scheduleExactShiftAlarm(this@MainActivity, outTimeMillis)
            }

            @JavascriptInterface
            fun cancelShiftEndAlarm() {
                cancelExactShiftAlarm(this@MainActivity)
            }

            @JavascriptInterface
            fun stopActiveAlarm() {
                AlarmController.stopRingtone(this@MainActivity)
            }

            @JavascriptInterface
            fun isAlarmRinging(): Boolean = AlarmController.isRinging

            @JavascriptInterface
            fun setRideMode(active: Boolean) {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("manual_ride_override", active).apply()
                applyRideModeProfile(this@MainActivity, active)
            }

            @JavascriptInterface
            fun openDndPolicySettings() {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }

            @JavascriptInterface
            fun isDndAccessGranted(): Boolean {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                return nm.isNotificationPolicyAccessGranted
            }

            /**
             * Fix #1: Return list of paired Bluetooth devices so the user can explicitly
             * pick their bike/car Bluetooth device (preventing headphones from ever triggering DND).
             */
            @SuppressLint("MissingPermission")
            @JavascriptInterface
            fun getPairedBluetoothDevicesJson(): String {
                val arr = JSONArray()
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                val savedSet = prefs.getStringSet("vehicle_bt_addresses", emptySet()) ?: emptySet()
                try {
                    val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) return arr.toString()

                    val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter: BluetoothAdapter? = bm.adapter
                    adapter?.bondedDevices?.forEach { dev ->
                        val devName = dev.name ?: "Unknown Device"
                        val addr = dev.address ?: return@forEach
                        val isHeadphone = isDefinitelyHeadphoneOrSpeaker(dev)
                        arr.put(JSONObject().apply {
                            put("name", devName)
                            put("address", addr)
                            put("selected", savedSet.contains(addr))
                            put("isHeadphone", isHeadphone)
                        })
                    }
                } catch (_: Exception) {}
                return arr.toString()
            }

            @JavascriptInterface
            fun toggleVehicleBluetoothAddress(address: String) {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                val current = prefs.getStringSet("vehicle_bt_addresses", emptySet())?.toMutableSet() ?: mutableSetOf()
                if (current.contains(address)) current.remove(address) else current.add(address)
                prefs.edit().putStringSet("vehicle_bt_addresses", current).apply()
            }

            @JavascriptInterface
            fun setManualParkingFloor(floorCode: String) {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("park_floor", floorCode)
                    .putLong("park_ts", System.currentTimeMillis())
                    .putString("park_source", "Manual")
                    .apply()
            }

            @JavascriptInterface
            fun startParkingSensorTracking() {
                startCommuteSensorService(this@MainActivity, "Manual")
            }

            @JavascriptInterface
            fun getAutoParkingJson(): String {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                val floor = prefs.getString("park_floor", "G") ?: "G"
                val dist = prefs.getInt("park_walk_m", 0)
                val dir = prefs.getString("park_walk_dir", "") ?: ""
                val vecX = prefs.getFloat("park_vec_x", 0f)
                val vecY = prefs.getFloat("park_vec_y", 0f)
                val ts = prefs.getLong("park_ts", 0L)
                val source = prefs.getString("park_source", "Auto Sensor") ?: "Auto Sensor"
                val rideActive = prefs.getBoolean("ride_mode_active", false)
                val vehicleBtConnected = prefs.getBoolean("vehicle_bt_connected", false)
                val isMoving = prefs.getBoolean("vehicle_is_moving", false)
                val speedKmh = prefs.getFloat("last_speed_kmh", 0f)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val dndGranted = nm.isNotificationPolicyAccessGranted

                return JSONObject().apply {
                    put("floor", floor)
                    put("walkMeters", dist)
                    put("walkDir", dir)
                    put("vecX", vecX.toDouble())
                    put("vecY", vecY.toDouble())
                    put("ts", ts)
                    put("source", source)
                    put("rideMode", rideActive)
                    put("vehicleBtConnected", vehicleBtConnected)
                    put("isMoving", isMoving)
                    put("speedKmh", speedKmh.toDouble())
                    put("dndGranted", dndGranted)
                    put("alarmRinging", AlarmController.isRinging)
                    put("trackingActive", CommuteSensorService.isRunning)
                }.toString()
            }

            @JavascriptInterface
            fun setFirebaseUrl(url: String) {
                getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                    .edit().putString("firebase_url", url.trimEnd('/')).apply()
            }

            @JavascriptInterface
            fun scanDebitedSmsJson(daysBack: Int): String {
                return SmsSpendAnalyzer.scanCurrentMonthInbox(this@MainActivity)
            }

            @JavascriptInterface
            fun scanCurrentMonthSmsJson(): String {
                return SmsSpendAnalyzer.scanCurrentMonthInbox(this@MainActivity)
            }
        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith(liveAppUrl)) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }

        webView.loadUrl(liveAppUrl)
        requestPermissionsAndStartTrackers()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ttsReady = true
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private fun requestPermissionsAndStartTrackers() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            registerOfficeGeofence()
            registerActivityTransitions()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, p, res)
        if (rc == 100) {
            registerOfficeGeofence()
            registerActivityTransitions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerOfficeGeofence() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("office_lat", 12.946960f).toDouble()
        val lng = prefs.getFloat("office_lng", 80.231400f).toDouble()
        val radius = prefs.getFloat("office_radius", 400f)

        // Two geofences: 750m Approach Zone (wakes up background ramp/floor sensor service even without BT)
        // and the inner Campus Check-In Geofence
        val approachFence = Geofence.Builder()
            .setRequestId("OFFICE_APPROACH")
            .setCircularRegion(lat, lng, (radius + 350f).coerceAtLeast(750f))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val campusFence = Geofence.Builder()
            .setRequestId("OFFICE_CAMPUS")
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(15000)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(approachFence)
            .addGeofence(campusFence)
            .build()

        val intent = Intent(this, GeofenceReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofencingClient.removeGeofences(pendingIntent).addOnCompleteListener {
            try {
                geofencingClient.addGeofences(request, pendingIntent)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityTransitions() {
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        val req = ActivityTransitionRequest(transitions)
        val intent = Intent(this, RideAndParkReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            activityClient.requestActivityTransitionUpdates(req, pi)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val alertCh = NotificationChannel(
                "LOKIFFICE_CH", "LOKIFFICE Shift & Ride Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true) }
            val sensorCh = NotificationChannel(
                "LOKIFFICE_SENSOR_CH", "LOKIFFICE Parking & Motion Telemetry",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(alertCh)
            nm.createNotificationChannel(sensorCh)
        }
    }
}

/**
 * Fix #1: Strict Headphone/Earbud Rejection & Explicit Vehicle Device Matching
 * Prevents headphones, TWS earbuds, speakers, and watches from EVER activating Ride Mode DND.
 */
@SuppressLint("MissingPermission")
fun isDefinitelyHeadphoneOrSpeaker(device: BluetoothDevice?): Boolean {
    if (device == null) return true
    val cls = device.bluetoothClass?.deviceClass ?: 0
    val headphoneClasses = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
    )
    if (cls in headphoneClasses) return true

    val name = (device.name ?: "").uppercase(Locale.US)
    val headphoneKeywords = listOf(
        "BUDS", "PODS", "AIRPOD", "HEADPHONE", "EARPHONE", "EARBUD", "TWS", "NECKBAND",
        "ROCKERZ", "AIRDOPES", "BOAT", "JBL", "SONY WH", "SONY WF", "BOSE", "SENNHEISER",
        "ONEPLUS", "NOTHING EAR", "REALME BUDS", "REDMI BUDS", "GALAXY BUDS", "NOISE",
        "SPEAKER", "WATCH", "BAND", "FIT", "SOUNDLINK", "FLIP", "CHARGE", "MARSHALL", "ENCORE", "ANC"
    )
    return headphoneKeywords.any { name.contains(it) }
}

@SuppressLint("MissingPermission")
fun isAuthorizedVehicleBluetooth(context: Context, device: BluetoothDevice?): Boolean {
    if (device == null) return false
    val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
    val savedAddresses = prefs.getStringSet("vehicle_bt_addresses", emptySet()) ?: emptySet()

    // 1. If user explicitly picked their vehicle Bluetooth device(s) in Setup, ONLY allow those!
    if (savedAddresses.isNotEmpty()) {
        return savedAddresses.contains(device.address)
    }

    // 2. Otherwise, reject all headphones/earbuds/speakers and only accept explicit vehicle classes/names
    if (isDefinitelyHeadphoneOrSpeaker(device)) return false

    val cls = device.bluetoothClass?.deviceClass ?: 0
    if (cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) return true

    val name = (device.name ?: "").uppercase(Locale.US)
    val vehicleKeywords = listOf(
        "RIDE CONNECT", "SUZUKI", "ACCESS", "ATHER", "OLA S1", "TVS", "NTORQ", "HONDA",
        "YAMAHA", "ROYAL ENFIELD", "BAJAJ", "HERO", "CAR AUDIO", "MY CAR", "CARPLAY",
        "ANDROID AUTO", "MARUTI", "HYUNDAI", "TATA", "MAHINDRA", "KIA", "TOYOTA", "BMW", "MERCEDES", "SKODA", "VW"
    )
    return vehicleKeywords.any { name.contains(it) }
}

/**
 * Fix #1 Dual-Condition Enforcer:
 * DND ONLY activates when (Vehicle Bluetooth Connected AND Vehicle Moving >= 8 km/h or IN_VEHICLE),
 * OR when the user explicitly toggles Manual Test Ride.
 */
fun evaluateRideDndState(context: Context) {
    val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
    val manualOverride = prefs.getBoolean("manual_ride_override", false)
    val btConnected = prefs.getBoolean("vehicle_bt_connected", false)
    val isMoving = prefs.getBoolean("vehicle_is_moving", false)
    val currentlyActive = prefs.getBoolean("ride_mode_active", false)

    val shouldBeActive = manualOverride || (btConnected && isMoving)
    if (shouldBeActive != currentlyActive) {
        applyRideModeProfile(context, shouldBeActive)
        if (shouldBeActive && !manualOverride) {
            sendNotification(
                context, 10,
                "LOKIFFICE Drive Shield Active",
                "Vehicle moving & connected. Silent/DND enabled automatically."
            )
        }
    }
}

fun applyRideModeProfile(context: Context, active: Boolean) {
    val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    prefs.edit().putBoolean("ride_mode_active", active).apply()

    if (!nm.isNotificationPolicyAccessGranted) {
        return
    }

    try {
        if (active) {
            prefs.edit().putInt("prev_ringer_mode", am.ringerMode).apply()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            val prev = prefs.getInt("prev_ringer_mode", AudioManager.RINGER_MODE_NORMAL)
            am.ringerMode = prev
        }
    } catch (_: Exception) {}
}

fun startCommuteSensorService(context: Context, reason: String) {
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) return
    val intent = Intent(context, CommuteSensorService::class.java).apply {
        putExtra("reason", reason)
    }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (_: Exception) {}
}

/**
 * Fix #3: Background Foreground Service for Parking Floor Detection WITHOUT Requiring Bluetooth.
 * Works on iQOO Neo 7 (Gyroscope + Accelerometer + Magnetometer + GPS speed/altitude, no barometer).
 * Automatically runs when approaching the office or moving, detects multi-level ramp climbs
 * (G -> 1st -> 2nd -> 3rd -> 4th) using fused spiral yaw turns + sustained upward ramp pitch +
 * GPS altitude delta, then locks the parking floor & walk steps once you dismount & walk to the lift.
 */
class CommuteSensorService : Service(), SensorEventListener, LocationListener {

    companion object {
        @Volatile var isRunning = false
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var lastGyroTs = 0L
    private var cumulativeRampYawDeg = 0.0
    private var rampClimbScore = 0.0
    private var lastPitchTs = 0L
    private var baselineAltitude: Double? = null
    private var currentFloorIndex = 0 // 0=G, 1=L1, 2=L2, 3=L3, 4=L4
    private var lastMovementTs = System.currentTimeMillis()
    private var garageSessionStarted = false

    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)
    private var currentAzimuthDeg = 0.0
    private var isTrackingWalk = false
    private var lastStepTs = 0L
    private var walkVectorX = 0.0
    private var walkVectorY = 0.0

    private val autoStopRunnable = Runnable {
        lockParkingAndStop()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        val notif = NotificationCompat.Builder(this, "LOKIFFICE_SENSOR_CH")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("LOKIFFICE Auto-Parking Active")
            .setContentText("Detecting parking level & motion in background...")
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(42, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(42, notif)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val reason = intent?.getStringExtra("reason") ?: ""
        if (reason == "Approach" || reason == "Manual") {
            resetGarageBaseline()
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2500L, 2f, this)
            }
        } catch (_: Exception) {}

        // Auto-stop after 12 minutes max so battery is never drained
        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, 12 * 60 * 1000L)
        return START_STICKY
    }

    private fun resetGarageBaseline() {
        cumulativeRampYawDeg = 0.0
        rampClimbScore = 0.0
        baselineAltitude = null
        currentFloorIndex = 0
        garageSessionStarted = true
        walkVectorX = 0.0
        walkVectorY = 0.0
        isTrackingWalk = false
    }

    override fun onLocationChanged(location: Location) {
        val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val speedKmh = location.speed * 3.6f
        prefs.edit().putFloat("last_speed_kmh", speedKmh).apply()

        // Fix #1: Update moving state for Vehicle BT + Motion DND gate
        val movingNow = speedKmh >= 8.0f
        if (movingNow) {
            lastMovementTs = System.currentTimeMillis()
            if (!prefs.getBoolean("vehicle_is_moving", false)) {
                prefs.edit().putBoolean("vehicle_is_moving", true).apply()
                evaluateRideDndState(this)
            }
        } else if (System.currentTimeMillis() - lastMovementTs > 75000L) {
            if (prefs.getBoolean("vehicle_is_moving", false)) {
                prefs.edit().putBoolean("vehicle_is_moving", false).apply()
                evaluateRideDndState(this)
            }
        }

        // Check proximity to office campus for garage entry baseline
        val offLat = prefs.getFloat("office_lat", 12.946960f).toDouble()
        val offLng = prefs.getFloat("office_lng", 80.231400f).toDouble()
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, offLat, offLng, results)
        val distToOffice = results[0]

        if (distToOffice < 350f) {
            if (!garageSessionStarted) {
                resetGarageBaseline()
            }
            if (location.hasAltitude()) {
                if (baselineAltitude == null) {
                    baselineAltitude = location.altitude
                } else {
                    val altDelta = location.altitude - (baselineAltitude ?: location.altitude)
                    if (altDelta > 2.2) {
                        val altFloors = (altDelta / 3.4).toInt().coerceIn(0, 4)
                        if (altFloors > currentFloorIndex) {
                            currentFloorIndex = altFloors
                            persistDetectedFloor("GPS + Ramp Sensor")
                        }
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val speedKmh = prefs.getFloat("last_speed_kmh", 0f)
        val isVehicleMoving = prefs.getBoolean("vehicle_is_moving", false) || speedKmh in 2.5f..35f

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroTs != 0L && garageSessionStarted) {
                    val dt = (event.timestamp - lastGyroTs) * 1e-9
                    // Integrate yaw magnitude when navigating spiral or U-turn parking ramps (even indoors when GPS drops)
                    val yawRateRad = event.values[2]
                    val yawDeg = Math.toDegrees(yawRateRad * dt)
                    if (abs(yawDeg) > 0.25 && abs(yawDeg) < 12.0 && !isTrackingWalk) {
                        cumulativeRampYawDeg += yawDeg
                        // Typical multi-level parking garage: ~240-320 deg net turn per level climb
                        val yawFloors = (abs(cumulativeRampYawDeg) / 260.0).toInt().coerceIn(0, 4)
                        val fusedFloor = maxOf(yawFloors, rampClimbScore.toInt()).coerceIn(0, 4)
                        if (fusedFloor != currentFloorIndex) {
                            currentFloorIndex = fusedFloor
                            persistDetectedFloor("Gyro + Ramp Sensor")
                        }
                    }
                }
                lastGyroTs = event.timestamp
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Low-pass filter for gravity vector to detect sustained uphill ramp inclination (~6° to 18°)
                val alpha = 0.85f
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                val mag = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )

                // Sustained uphill ramp detection (even on straight split-level ramps without 360° spirals!)
                if (garageSessionStarted && !isTrackingWalk && isVehicleMoving) {
                    val nowTs = event.timestamp
                    if (lastPitchTs != 0L) {
                        val dt = (nowTs - lastPitchTs) * 1e-9
                        val gNorm = sqrt(gravity[0]*gravity[0] + gravity[1]*gravity[1] + gravity[2]*gravity[2]).coerceAtLeast(1f)
                        val pitchDeg = Math.toDegrees(atan2(gravity[1].toDouble(), gravity[2].toDouble()))
                        val linearVibration = abs(mag - gNorm)
                        // Vehicle climbing a parking ramp exhibits steady tilt + low-frequency engine/road vibration
                        if (abs(pitchDeg) in 5.5..24.0 && linearVibration in 0.35..3.5) {
                            // ~6 seconds of climbing at ramp grade ≈ 1 floor (~3.5m rise)
                            rampClimbScore += (dt / 6.5)
                            val pitchFloors = rampClimbScore.toInt().coerceIn(0, 4)
                            if (pitchFloors > currentFloorIndex) {
                                currentFloorIndex = pitchFloors
                                persistDetectedFloor("Ramp Climb Sensor")
                            }
                        }
                    }
                    lastPitchTs = nowTs
                }

                // Walk vector tracking from parked spot to elevator lobby
                val parkTs = prefs.getLong("park_ts", 0L)
                val elapsedSincePark = System.currentTimeMillis() - parkTs
                if (isTrackingWalk || (parkTs > 0L && elapsedSincePark in 1000L..45000L && !isVehicleMoving)) {
                    val now = System.currentTimeMillis()
                    if (mag > 11.4f && (now - lastStepTs) > 380L) {
                        lastStepTs = now
                        val stepLen = 0.72
                        val rad = Math.toRadians(currentAzimuthDeg)
                        walkVectorX += stepLen * sin(rad)
                        walkVectorY += stepLen * cos(rad)
                        val totalDist = sqrt(walkVectorX * walkVectorX + walkVectorY * walkVectorY).toInt()
                        val returnBearing = (Math.toDegrees(atan2(-walkVectorX, -walkVectorY)) + 360.0) % 360.0
                        val dirLabel = bearingToNaturalDirection(returnBearing)
                        prefs.edit()
                            .putInt("park_walk_m", totalDist)
                            .putString("park_walk_dir", dirLabel)
                            .putFloat("park_vec_x", (-walkVectorX).toFloat())
                            .putFloat("park_vec_y", (-walkVectorY).toFloat())
                            .apply()
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic = event.values.clone()
                val R = FloatArray(9)
                val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(R, orientation)
                    currentAzimuthDeg = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
                }
            }
        }
    }

    private fun persistDetectedFloor(sourceLabel: String) {
        val floorCodes = arrayOf("G", "L1", "L2", "L3", "L4")
        val code = floorCodes[currentFloorIndex.coerceIn(0, 4)]
        getSharedPreferences("lokiffce", Context.MODE_PRIVATE).edit()
            .putString("park_floor", code)
            .putLong("park_ts", System.currentTimeMillis())
            .putString("park_source", sourceLabel)
            .apply()
    }

    private fun bearingToNaturalDirection(deg: Double): String {
        return when (((deg + 22.5) / 45.0).toInt() % 8) {
            0 -> "straight ahead (North side)"
            1 -> "ahead to the right (North-East)"
            2 -> "to the right (East side)"
            3 -> "back-right (South-East)"
            4 -> "behind the lift (South side)"
            5 -> "back-left (South-West)"
            6 -> "to the left (West side)"
            else -> "ahead to the left (North-West)"
        }
    }

    private fun lockParkingAndStop() {
        stopSelf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(autoStopRunnable)
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Fix #4: Bank-Grade Calendar-Month Indian Rupee (₹) Debit-Only SMS Analyzer
 * - Strictly starts at 1st of the current calendar month (00:00:00.000).
 * - Strips Avl Bal / Balance / Limit clauses BEFORE extracting amounts so account balances (e.g. ₹80,000)
 *   are NEVER misread as a debit spend!
 * - Excludes promos, loan offers, collect requests, failed/declined txns, statements, OTPs, and all credits/refunds.
 * - Matches amounts strictly adjacent to debit verbs and strictly in ₹ / Rs. / INR.
 * - Deduplicates double bank+UPI alerts within 120 seconds.
 */
object SmsSpendAnalyzer {
    private val HARD_EXCLUSIONS = listOf(
        "credited", "received", "deposited", "refund", "cashback", "reversed", "salary",
        "added to", "cr to", "failed", "declined", "unsuccessful", "insufficient",
        "otp", "verification code", "loan", "pre-approved", "preapproved", "offer",
        "credit limit", "statement", "bill is generated", "total due", "min due",
        "collect request", "has requested", "request of", "mandate", "autopay", "bonus",
        "reward points", "voucher", "win rs", "up to rs", "upto rs", "get rs", "earn rs"
    )

    private val BALANCE_CUTOFF_REGEX = Regex(
        """(?i)\b(?:avl\.?\s*bal|avbl\.?\s*bal|avail\.?\s*bal|available\s+bal|clear\s+bal|closing\s+bal|total\s+bal|current\s+bal|bal(?:ance)?\s*(?:is|:|rs|inr|₹)|available\s+limit|avl\.?\s*lmt)\b.*"""
    )

    // Match debit verb BEFORE amount: e.g., "debited by Rs. 240.50", "spent INR 1,200", "paid ₹85"
    private val DEBIT_BEFORE_AMT = Regex(
        """(?i)\b(?:debited|spent|paid|deducted|withdrawn|charged|dr\.?|sent|txn\s+of|purchase\s+of)\b[^0-9₹]{0,24}?(?:rs\.?|inr|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)"""
    )

    // Match amount BEFORE debit verb: e.g., "Rs. 240.00 has been debited", "INR 500 spent", "₹150 paid"
    private val AMT_BEFORE_DEBIT = Regex(
        """(?i)(?:rs\.?|inr|₹)\s*([0-9,]+(?:\.[0-9]{1,2})?)\s*(?:has\s+been|is|was)?\s*\b(?:debited|spent|paid|deducted|withdrawn|charged|sent\s+to)\b"""
    )

    fun scanCurrentMonthInbox(context: Context): String {
        val result = JSONObject()
        val txArray = JSONArray()
        var totalDebited = 0.0

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val monthStartMillis = cal.timeInMillis
        val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date())

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            result.put("permissionGranted", false)
            result.put("monthLabel", monthLabel)
            result.put("totalDebited", 0.0)
            result.put("transactions", txArray)
            return result.toString()
        }

        val seenFingerprints = mutableListOf<Pair<Double, Long>>()
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date >= ?",
                arrayOf(monthStartMillis.toString()),
                "date DESC"
            )
            while (cursor != null && cursor.moveToNext()) {
                val sender = cursor.getString(0) ?: ""
                val rawBody = cursor.getString(1) ?: ""
                val dateMs = cursor.getLong(2)

                // Ignore personal 10-digit phone numbers (real bank/UPI alerts come from alphanumeric sender IDs like AD-HDFCBK, VM-ICICIB)
                val cleanSender = sender.replace("+", "").replace(" ", "")
                if (cleanSender.length >= 10 && cleanSender.all { it.isDigit() }) continue

                val lowerFull = rawBody.lowercase(Locale.US)
                if (HARD_EXCLUSIONS.any { lowerFull.contains(it) }) continue

                // Truncate at Available Balance / Limit so account balances are NEVER matched!
                val debitClause = BALANCE_CUTOFF_REGEX.replace(rawBody, "").trim()
                if (debitClause.length < 10) continue

                val match = DEBIT_BEFORE_AMT.find(debitClause) ?: AMT_BEFORE_DEBIT.find(debitClause) ?: continue
                val amtStr = match.groupValues[1].replace(",", "")
                val amt = amtStr.toDoubleOrNull() ?: continue
                if (amt <= 0.0 || amt > 300000.0) continue

                // Deduplicate identical amount alerts within 2 minutes (e.g., Bank SMS + UPI SMS)
                val isDuplicate = seenFingerprints.any { (prevAmt, prevTime) ->
                    abs(prevAmt - amt) < 0.01 && abs(prevTime - dateMs) < 120_000L
                }
                if (isDuplicate) continue
                seenFingerprints.add(Pair(amt, dateMs))

                val vendor = extractVendorName(debitClause, sender)
                val category = classifyVendor(debitClause.lowercase(Locale.US), vendor.lowercase(Locale.US))
                val txId = "${dateMs}_${amt.toInt()}"

                totalDebited += amt

                if (txArray.length() < 100) {
                    txArray.put(JSONObject().apply {
                        put("id", txId)
                        put("merchant", vendor)
                        put("category", category)
                        put("amount", amt)
                        put("dateMs", dateMs)
                        put("snippet", rawBody.take(160))
                    })
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }

        result.put("permissionGranted", true)
        result.put("monthLabel", monthLabel)
        result.put("monthStartMillis", monthStartMillis)
        result.put("totalDebited", totalDebited)
        result.put("transactions", txArray)
        return result.toString()
    }

    private fun classifyVendor(lowerBody: String, lowerVendor: String): String {
        val text = "$lowerBody $lowerVendor"
        val foodDining = listOf(
            "hungerbox", "zomato", "swiggy", "cafe", "coffee", "tea", "chai", "canteen",
            "restaurant", "hotel", "bhavan", "mess", "tiffin", "bakery", "biryani", "dosa",
            "idli", "food", "eatery", "bistro", "kitchen", "starbucks", "mcdonald", "kfc",
            "domino", "pizza", "burger", "sweets", "juice", "parotta", "udupi", "saravana",
            "a2b", "adyar", "sangeetha", "murugan", "haldiram", "barbeque", "grill"
        )
        val commuteFuel = listOf(
            "rapido", "uber", "ola", "metro", "cmrl", "irctc", "ixigo", "petrol", "fuel", "shell",
            "hpcl", "bpcl", "indian oil", "iocl", "fastag", "parking", "cab", "auto", "bunk", "diesel", "redbus"
        )
        val groceriesBills = listOf(
            "nobroker", "zepto", "bigbasket", "bbnow", "blinkit", "instamart", "supermarket", "mart",
            "airtel", "jio", "electricity", "tneb", "eb ", "broadband", "act fibernet", "water", "rent",
            "maintenance", "pharmacy", "apollo", "medplus", "1mg", "medical", "hospital"
        )
        val shoppingEnt = listOf(
            "amazon", "flipkart", "myntra", "ajio", "decathlon", "croma", "reliance", "mall",
            "bookmyshow", "pvr", "inox", "cinema", "netflix", "spotify", "apple", "google play"
        )

        return when {
            foodDining.any { text.contains(it) } -> "Food & Dining"
            commuteFuel.any { text.contains(it) } -> "Commute & Fuel"
            groceriesBills.any { text.contains(it) } -> "Groceries & Bills"
            shoppingEnt.any { text.contains(it) } -> "Shopping & Lifestyle"
            text.contains("upi") || text.contains("vpa") -> "UPI Transfer"
            else -> "Card / Bank Debit"
        }
    }

    private fun extractVendorName(clause: String, sender: String): String {
        val patterns = listOf(
            Regex("""(?i)\b(?:to|at|towards|vpa|info:|merchant)\s+([A-Za-z0-9@._& -]{3,34})"""),
            Regex("""(?i)UPI/(?:DR|CR|P2M|P2A)/[0-9]+/([A-Za-z0-9 ._-]{3,28})"""),
            Regex("""(?i)trf\s+to\s+([A-Za-z0-9 ._-]{3,28})""")
        )
        for (p in patterns) {
            val m = p.find(clause)
            if (m != null) {
                val cleaned = m.groupValues[1]
                    .replace(Regex("""(?i)\b(on|ref|via|using|from|upi|if not|call|a/c|acct)\b.*"""), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim(' ', '.', '-', ':')
                if (cleaned.length >= 3 && !cleaned.all { it.isDigit() }) {
                    return cleaned
                }
            }
        }
        return sender.replace(Regex("""^[A-Z]{2}-"""), "").ifEmpty { "Bank Debit" }
    }
}

object AlarmController {
    private var activeRingtone: Ringtone? = null
    @Volatile var isRinging: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    fun startRingtone(context: Context) {
        stopRingtone(context)
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val rt = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rt.isLooping = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            rt.play()
            activeRingtone = rt
            isRinging = true
            handler.postDelayed({ stopRingtone(context) }, 25000L)
        } catch (_: Exception) {}
    }

    fun stopRingtone(context: Context) {
        try { activeRingtone?.stop() } catch (_: Exception) {}
        activeRingtone = null
        isRinging = false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(2)
    }
}

fun scheduleExactShiftAlarm(context: Context, outTimeMillis: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
        action = "com.lokiffce.app.ACTION_SHIFT_ALARM"
    }
    val pi = PendingIntent.getBroadcast(
        context, 2, alarmIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    if (outTimeMillis <= System.currentTimeMillis()) return
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, outTimeMillis, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, outTimeMillis, pi)
        }
    } catch (_: Exception) {
        alarmManager.set(AlarmManager.RTC_WAKEUP, outTimeMillis, pi)
    }
}

fun cancelExactShiftAlarm(context: Context) {
    AlarmController.stopRingtone(context)
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
        action = "com.lokiffce.app.ACTION_SHIFT_ALARM"
    }
    val pi = PendingIntent.getBroadcast(
        context, 2, alarmIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pi)
}

class RideAndParkReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val fbBase = prefs.getString("firebase_url", "https://lokiffice-default-rtdb.asia-southeast1.firebasedatabase.app")
            ?: "https://lokiffice-default-rtdb.asia-southeast1.firebasedatabase.app"

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                // Fix #1: Strictly ignore headphones/earbuds; ONLY track authorized vehicle Bluetooth
                if (isAuthorizedVehicleBluetooth(context, device)) {
                    prefs.edit().putBoolean("vehicle_bt_connected", true).apply()
                    startCommuteSensorService(context, "VehicleBT")
                    evaluateRideDndState(context) // Only activates DND once vehicle is ALSO moving!
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (isAuthorizedVehicleBluetooth(context, device) && prefs.getBoolean("vehicle_bt_connected", false)) {
                    val floor = prefs.getString("park_floor", "G") ?: "G"
                    prefs.edit()
                        .putBoolean("vehicle_bt_connected", false)
                        .putBoolean("vehicle_is_moving", false)
                        .putLong("park_ts", System.currentTimeMillis())
                        .apply()
                    evaluateRideDndState(context)
                    pushCrowdDelta(fbBase, floor, 1)
                    sendNotification(
                        context, 11,
                        "Vehicle Parked · ${floorDisplayName(floor)}",
                        "Normal ringer restored & parking level locked."
                    )
                }
            }
        }

        // Fix #1 & #3: ActivityRecognition wakes sensor service EVEN WITHOUT Bluetooth!
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            for (event in result.transitionEvents) {
                val isRideType = event.activityType == DetectedActivity.IN_VEHICLE ||
                    event.activityType == DetectedActivity.ON_BICYCLE

                if (isRideType && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    prefs.edit().putBoolean("vehicle_is_moving", true).apply()
                    startCommuteSensorService(context, "Motion")
                    evaluateRideDndState(context)
                } else if ((isRideType && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) ||
                    (event.activityType == DetectedActivity.WALKING && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)) {
                    prefs.edit()
                        .putBoolean("vehicle_is_moving", false)
                        .putLong("park_ts", System.currentTimeMillis())
                        .apply()
                    evaluateRideDndState(context)
                }
            }
        }
    }

    private fun floorDisplayName(code: String): String = when (code) {
        "G" -> "Ground Floor"
        "L1" -> "1st Floor"
        "L2" -> "2nd Floor"
        "L3" -> "3rd Floor"
        "L4" -> "4th Floor"
        else -> code
    }

    private fun pushCrowdDelta(fbBase: String, floor: String, delta: Int) {
        Thread {
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val url = URL("$fbBase/campus_parking/$today/floors/$floor.json")
                val getConn = url.openConnection() as HttpURLConnection
                getConn.requestMethod = "GET"
                val raw = getConn.inputStream.bufferedReader().readText()
                val current = Regex("\"count\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val nextCount = (current + delta).coerceAtLeast(0)

                val putConn = url.openConnection() as HttpURLConnection
                putConn.requestMethod = "PATCH"
                putConn.setRequestProperty("Content-Type", "application/json")
                putConn.doOutput = true
                val body = "{\"count\":$nextCount,\"updatedAt\":${System.currentTimeMillis()}}"
                putConn.outputStream.use { it.write(body.toByteArray()) }
                putConn.responseCode
            } catch (_: Exception) {}
        }.start()
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val triggering = event.triggeringGeofences ?: return
        val ids = triggering.map { it.requestId }.toSet()

        // Fix #3: Approaching office starts foreground ramp/floor sensor tracking automatically (no BT needed!)
        if (ids.contains("OFFICE_APPROACH") && event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            startCommuteSensorService(context, "Approach")
        }

        if (ids.contains("OFFICE_CAMPUS") &&
            (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
             event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL)) {
            val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            if (prefs.contains(todayKey)) return
            val now = System.currentTimeMillis()
            val outTime = now + 5 * 60 * 60 * 1000L
            prefs.edit().putLong(todayKey, now).apply()

            val fmt = SimpleDateFormat("hh:mm a", Locale.US)
            sendNotification(
                context, 1,
                "LOKIFFICE: Checked In at Office",
                "Logged IN at ${fmt.format(Date(now))}. Leave at ${fmt.format(Date(outTime))}."
            )
            scheduleExactShiftAlarm(context, outTime)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.lokiffce.app.ACTION_DISMISS_ALARM") {
            AlarmController.stopRingtone(context)
            return
        }

        AlarmController.startRingtone(context)

        val dismissIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.lokiffce.app.ACTION_DISMISS_ALARM"
        }
        val dismissPi = PendingIntent.getBroadcast(
            context, 99, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            context, 98, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, "LOKIFFICE_CH")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("LOKIFFICE: Shift Complete")
            .setContentText("Your mandatory office shift is complete. Tap OK to stop alarm.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPi)
            .setDeleteIntent(dismissPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "OK · Stop Alarm", dismissPi)
            .setAutoCancel(true)
            .build()
        nm.notify(2, notif)
    }
}

fun sendNotification(context: Context, id: Int, title: String, msg: String) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notif = NotificationCompat.Builder(context, "LOKIFFICE_CH")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(title)
        .setContentText(msg)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    nm.notify(id, notif)
}
