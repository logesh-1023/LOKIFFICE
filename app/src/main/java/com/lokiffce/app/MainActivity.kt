package com.lokiffce.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SensorEventListener {

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var activityClient: ActivityRecognitionClient
    private lateinit var sensorManager: SensorManager
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val liveAppUrl = "https://logesh-1023.github.io/LOKIFFICE/"

    // IMU Ramp Floor & Walking Vector State (Accelerometer + Gyroscope + E-Compass)
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
            fun isAlarmRinging(): Boolean {
                return AlarmController.isRinging
            }

            @JavascriptInterface
            fun setRideMode(active: Boolean) {
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

            @JavascriptInterface
            fun getAutoParkingJson(): String {
                val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
                val floor = prefs.getString("park_floor", "L1") ?: "L1"
                val dist = prefs.getInt("park_walk_m", 0)
                val dir = prefs.getString("park_walk_dir", "North") ?: "North"
                val vecX = prefs.getFloat("park_vec_x", 12f)
                val vecY = prefs.getFloat("park_vec_y", 18f)
                val ts = prefs.getLong("park_ts", 0L)
                val rideActive = prefs.getBoolean("ride_mode_active", false)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val dndGranted = nm.isNotificationPolicyAccessGranted
                return JSONObject().apply {
                    put("floor", floor)
                    put("walkMeters", dist)
                    put("walkDir", dir)
                    put("vecX", vecX.toDouble())
                    put("vecY", vecY.toDouble())
                    put("ts", ts)
                    put("rideMode", rideActive)
                    put("dndGranted", dndGranted)
                    put("alarmRinging", AlarmController.isRinging)
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
                            .putFloat("park_vec_x", (-walkVectorX).toFloat())
                            .putFloat("park_vec_y", (-walkVectorY).toFloat())
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
        val prefs = getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("office_lat", 12.946960f).toDouble()
        val lng = prefs.getFloat("office_lng", 80.231400f).toDouble()
        val radius = prefs.getFloat("office_radius", 400f)

        val geofence = Geofence.Builder()
            .setRequestId("OFFICE_CAMPUS")
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
            channel.enableVibration(true)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/**
 * Calendar-Month Debit-Only SMS Spend Analyzer
 * Filters from the 1st of the current calendar month (00:00:00.000) to now,
 * excludes all credited/refund/salary messages, and extracts raw vendor + snippet
 * so SmolLM2-360M can classify vendors semantically without generic "Other" buckets.
 */
object SmsSpendAnalyzer {
    private val DEBIT_KEYWORDS = listOf(
        "debited", "spent", "paid", "sent", "withdrawn", "purchase", "txn of", "charged", "auto-debit", "upi/"
    )
    private val CREDIT_EXCLUSIONS = listOf(
        "credited", "received", "deposited", "refund", "cashback", "reversed", "salary", "added to", "cr to"
    )
    private val AMOUNT_REGEX = Regex(
        """(?:rs\.?|inr|₹|\$|€|£)\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
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
                val body = cursor.getString(1) ?: ""
                val dateMs = cursor.getLong(2)
                val lower = body.lowercase(Locale.US)

                if (lower.contains("otp") || lower.contains("verification code")) continue
                if (CREDIT_EXCLUSIONS.any { lower.contains(it) }) continue
                if (!DEBIT_KEYWORDS.any { lower.contains(it) }) continue

                val match = AMOUNT_REGEX.find(body) ?: continue
                val amtStr = match.groupValues[1].replace(",", "")
                val amt = amtStr.toDoubleOrNull() ?: continue
                if (amt <= 0.0 || amt > 500000.0) continue

                val vendor = extractVendorName(body, sender)
                val heuristicCat = heuristicVendorCategory(lower, vendor.lowercase(Locale.US))

                totalDebited += amt

                if (txArray.length() < 80) {
                    txArray.put(JSONObject().apply {
                        put("merchant", vendor)
                        put("category", heuristicCat)
                        put("amount", amt)
                        put("dateMs", dateMs)
                        put("snippet", body.take(120))
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

    private fun heuristicVendorCategory(lowerBody: String, lowerVendor: String): String {
        val text = "$lowerBody $lowerVendor"
        val foodDining = listOf(
            "hungerbox", "zomato", "swiggy", "cafe", "coffee", "tea", "chai", "canteen",
            "restaurant", "hotel", "bhavan", "mess", "tiffin", "bakery", "biryani", "dosa",
            "idli", "food", "eatery", "bistro", "kitchen", "starbucks", "mcdonald", "kfc",
            "domino", "pizza", "burger", "sweets", "juice", "parotta", "udupi", "saravana", "a2b", "adyar"
        )
        val commuteFuel = listOf(
            "rapido", "uber", "ola", "metro", "cmrl", "irctc", "petrol", "fuel", "shell",
            "hpcl", "bpcl", "indian oil", "iocl", "fastag", "parking", "cab", "auto", "bunk", "diesel"
        )
        val groceriesBills = listOf(
            "nobroker", "zepto", "bigbasket", "blinkit", "instamart", "supermarket", "mart",
            "airtel", "jio", "electricity", "eb ", "broadband", "water", "rent", "maintenance", "pharmacy", "medical"
        )
        val shoppingRetail = listOf(
            "amazon", "flipkart", "myntra", "ajio", "decathlon", "croma", "reliance", "mall", "store", "retail"
        )
        val peerTransfer = listOf(
            "upi", "neft", "imps", "vpa", "paytm", "gpay", "phonepe"
        )

        return when {
            foodDining.any { text.contains(it) } -> "Food & Dining"
            commuteFuel.any { text.contains(it) } -> "Commute & Fuel"
            groceriesBills.any { text.contains(it) } -> "Groceries & Bills"
            shoppingRetail.any { text.contains(it) } -> "Shopping & Retail"
            peerTransfer.any { text.contains(it) } -> "UPI Merchant / Transfer"
            else -> "Direct Card / Bank Debit"
        }
    }

    private fun extractVendorName(body: String, sender: String): String {
        val patterns = listOf(
            Regex("""(?:to|at|towards|info:|vpa|merchant)\s+([A-Za-z0-9@._& -]{3,32})""", RegexOption.IGNORE_CASE),
            Regex("""UPI/[A-Za-z0-9]+/([A-Za-z0-9 ._-]{3,28})""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(body)
            if (m != null) {
                val cleaned = m.groupValues[1].trim()
                    .replace(Regex("""\s+"""), " ")
                    .replace(Regex("""^(?:rs|inr|upi|dr|cr)\b[:\s-]*""", RegexOption.IGNORE_CASE), "")
                    .trim()
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
        try {
            activeRingtone?.stop()
        } catch (_: Exception) {}
        activeRingtone = null
        isRinging = false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(2)
    }
}

fun applyRideModeProfile(context: Context, active: Boolean) {
    val prefs = context.getSharedPreferences("lokiffce", Context.MODE_PRIVATE)
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    prefs.edit().putBoolean("ride_mode_active", active).apply()

    if (!nm.isNotificationPolicyAccessGranted) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
                val majorClass = device?.bluetoothClass?.majorDeviceClass
                val name = device?.name?.uppercase(Locale.US) ?: ""
                val isVehicleOrHeadset = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    name.contains("CAR") || name.contains("BIKE") || name.contains("RIDE") ||
                    name.contains("BT") || name.contains("SUZUKI") || name.contains("HONDA") ||
                    name.contains("YAMAHA") || name.contains("TVS") || name.contains("HERO") ||
                    name.contains("BMW") || name.contains("TOYOTA") || name.contains("HYUNDAI") ||
                    name.isEmpty()

                if (isVehicleOrHeadset) {
                    val prevFloor = prefs.getString("park_floor", "") ?: ""
                    applyRideModeProfile(context, true)
                    if (prevFloor.isNotEmpty()) {
                        pushCrowdDelta(fbBase, prevFloor, -1)
                    }
                    sendNotification(
                        context, 10,
                        "LOKIFFICE Ride Mode ON",
                        "Vehicle Bluetooth connected. Phone switched to Silent/DND profile."
                    )
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (prefs.getBoolean("ride_mode_active", false)) {
                    val floor = prefs.getString("park_floor", "L1") ?: "L1"
                    prefs.edit().putLong("park_ts", System.currentTimeMillis()).apply()
                    applyRideModeProfile(context, false)
                    pushCrowdDelta(fbBase, floor, 1)
                    sendNotification(
                        context, 11,
                        "Vehicle Parked · Floor $floor",
                        "Ignition off detected. Normal ringtone restored & 3D spatial pin locked."
                    )
                }
            }
        }

        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            for (event in result.transitionEvents) {
                if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    applyRideModeProfile(context, true)
                } else if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                    applyRideModeProfile(context, false)
                }
            }
        }
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
