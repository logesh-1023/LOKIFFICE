package com.lokiffce.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SensorEventListener {

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var activityClient: ActivityRecognitionClient
    private lateinit var sensorManager: SensorManager
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val liveAppUrl = "https://logesh-1023.github.io/LOKIFFICE/"

    // IMU Ramp Floor & Walking Vector State (iQOO Neo 7 Accelerometer + Gyroscope + E-Compass)
    private var lastGyroTs = 0L
    private var cumulativeYawDeg = 0.0
    private var detectedFloor = "L1"
    private var isTrackingWalk = false
    private var stepCount = 0
    private var lastStepTs = 0L
    private var walkVectorX = 0.0
    private var walkVectorY = 0.0
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)
    private var currentAzimuthDeg = 0.0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        tts = TextToSpeech(this, this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        activityClient = ActivityRecognition.getClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun speak(text: String) {
                if (ttsReady) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LOKIFFICE_BRIEFING")
                }
            }

            @JavascriptInterface
            fun updateOfficeGeofence(lat: Double, lng: Double, radiusMeters: Float) {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                prefs.edit()
                    .putFloat("office_lat", lat.toFloat())
                    .putFloat("office_lng", lng.toFloat())
                    .putFloat("office_radius", radiusMeters)
                    .apply()
                registerOfficeGeofence()
            }

            @JavascriptInterface
            fun setRideMode(active: Boolean) {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("ride_mode_active", active).apply()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(
                        if (active) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        else NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                } else {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }

            @JavascriptInterface
            fun requestDndPermission() {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }

            @JavascriptInterface
            fun isDndAccessGranted(): Boolean {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                return nm.isNotificationPolicyAccessGranted
            }

            @JavascriptInterface
            fun getAutoParkingJson(): String {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                val floor = prefs.getString("park_floor", "L1") ?: "L1"
                val dist = prefs.getInt("park_walk_m", 0)
                val dir = prefs.getString("park_walk_dir", "North") ?: "North"
                val ts = prefs.getLong("park_ts", 0L)
                val rideActive = prefs.getBoolean("ride_mode_active", false)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val dndGranted = nm.isNotificationPolicyAccessGranted
                return "{\"floor\":\"$floor\",\"walkMeters\":$dist,\"walkDir\":\"$dir\",\"ts\":$ts,\"rideMode\":$rideActive,\"dndGranted\":$dndGranted}"
            }

            @JavascriptInterface
            fun setManualParkingFloor(floor: String) {
                getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                    .edit().putString("park_floor", floor).apply()
            }

            @JavascriptInterface
            fun setFirebaseUrl(url: String) {
                getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                    .edit().putString("firebase_url", url.trimEnd('/')).apply()
            }
        }, "AndroidBridge")

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun speak(text: String) {
                if (ttsReady) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LOKIFFICE_BRIEFING")
                }
            }
        }, "AndroidTTS")

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
        requestPermissionsAndRegisterGeofence()
        startImuSensors()
    }

    private fun startImuSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val rideActive = prefs.getBoolean("ride_mode_active", false)

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroTs != 0L && rideActive) {
                    val dt = (event.timestamp - lastGyroTs) * 1e-9
                    val yawDeg = Math.toDegrees(event.values[2] * dt)
                    if (abs(yawDeg) > 0.15) {
                        cumulativeYawDeg += yawDeg
                        val floorsClimbed = (abs(cumulativeYawDeg) / 330.0).toInt()
                        detectedFloor = when {
                            floorsClimbed <= 0 -> "G"
                            floorsClimbed == 1 -> "L1"
                            floorsClimbed == 2 -> "L2"
                            floorsClimbed == 3 -> "L3"
                            else -> "L4"
                        }
                        prefs.edit().putString("park_floor", detectedFloor).apply()
                    }
                }
                lastGyroTs = event.timestamp
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = event.values.clone()
                val mag = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                val parkTs = prefs.getLong("park_ts", 0L)
                val elapsedSincePark = System.currentTimeMillis() - parkTs
                if (!rideActive && parkTs > 0L && elapsedSincePark in 1000L..45000L) {
                    if (!isTrackingWalk) {
                        isTrackingWalk = true
                        stepCount = 0
                        walkVectorX = 0.0
                        walkVectorY = 0.0
                    }
                    val now = System.currentTimeMillis()
                    if (mag > 11.4f && (now - lastStepTs) > 380L) {
                        lastStepTs = now
                        stepCount++
                        val stepLen = 0.72
                        val rad = Math.toRadians(currentAzimuthDeg)
                        walkVectorX += stepLen * sin(rad)
                        walkVectorY += stepLen * cos(rad)
                        val totalDist = sqrt(walkVectorX * walkVectorX + walkVectorY * walkVectorY).toInt()
                        val returnBearing = (Math.toDegrees(atan2(-walkVectorX, -walkVectorY)) + 360.0) % 360.0
                        val dirLabel = bearingToCardinal(returnBearing)
                        prefs.edit()
                            .putInt("park_walk_m", totalDist)
                            .putString("park_walk_dir", dirLabel)
                            .apply()
                    }
                } else {
                    isTrackingWalk = false
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun bearingToCardinal(deg: Double): String {
        val dirs = arrayOf("North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West")
        return dirs[((deg + 22.5) / 45.0).toInt() % 8]
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ttsReady = true
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private fun requestPermissionsAndRegisterGeofence() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        } else {
            registerOfficeGeofence()
            registerActivityTransitions()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, p, res)
        if (rc == 100 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            registerOfficeGeofence()
            registerActivityTransitions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerOfficeGeofence() {
        val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("office_lat", 12.946960f).toDouble()
        val lng = prefs.getFloat("office_lng", 80.231400f).toDouble()
        val radius = prefs.getFloat("office_radius", 400f)

        val geofence = Geofence.Builder()
            .setRequestId("COMCAST_OFFICE")
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(15000)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        val intent = Intent(this, GeofenceReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofencingClient.removeGeofences(pendingIntent).addOnCompleteListener {
            geofencingClient.addGeofences(request, pendingIntent)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "LOKIFFICE_CH", "LOKIFFICE Shift & Ride Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

class RideAndParkReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                val name = device?.name?.uppercase(Locale.US) ?: ""
                if (name.contains("SUZUKI") || name.contains("ACCESS") || name.contains("RIDE") || name.isEmpty()) {
                    val prevFloor = prefs.getString("park_floor", "") ?: ""
                    prefs.edit().putBoolean("ride_mode_active", true).apply()
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    }
                    if (prevFloor.isNotEmpty()) {
                        pushCrowdDelta(fbBase, prevFloor, -1)
                    }
                    sendNotification(
                        context, 10,
                        "LOKIFFICE Ride Mode ON",
                        "Access 125 connected. Calls silenced via Auto-DND for a safe ride."
                    )
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (prefs.getBoolean("ride_mode_active", false)) {
                    val floor = prefs.getString("park_floor", "L1") ?: "L1"
                    prefs.edit()
                        .putBoolean("ride_mode_active", false)
                        .putLong("park_ts", System.currentTimeMillis())
                        .apply()

                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    pushCrowdDelta(fbBase, floor, 1)
                    sendNotification(
                        context, 11,
                        "Access 125 Parked · Floor $floor",
                        "Ignition off detected. Ride Mode DND off & live floor synced."
                    )
                }
            }
        }

        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            for (event in result.transitionEvents) {
                if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    prefs.edit().putBoolean("ride_mode_active", true).apply()
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    }
                } else if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                    prefs.edit().putBoolean("ride_mode_active", false).apply()
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                }
            }
        }
    }

    private fun pushCrowdDelta(fbBase: String, floor: String, delta: Int) {
        Thread {
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val url = URL("$fbBase/chennai_one/$today/floors/$floor.json")
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

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

            if (prefs.contains(todayKey)) return
            val now = System.currentTimeMillis()
            val outTime = now + 5 * 60 * 60 * 1000L
            prefs.edit().putLong(todayKey, now).apply()

            val fmt = SimpleDateFormat("hh:mm a", Locale.US)
            sendNotification(
                context, 1,
                "LOKIFFICE: Checked In at Comcast",
                "Logged IN at ${fmt.format(Date(now))}. Leave at ${fmt.format(Date(outTime))}."
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val alarmIntent = Intent(context, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 2, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, outTime, pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        sendNotification(
            context, 2,
            "LOKIFFICE: 5 Hours Complete",
            "Your 5-hour office shift at Comcast is complete."
        )
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.play()
        } catch (_: Exception) {}
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
