package com.lokiffce.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var geofencingClient: GeofencingClient
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val liveAppUrl = "https://logesh-1023.github.io/LOKIFFICE/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        tts = TextToSpeech(this, this)
        geofencingClient = LocationServices.getGeofencingClient(this)

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

    private fun requestPermissionsAndRegisterGeofence() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        } else {
            registerOfficeGeofence()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, p, res)
        if (rc == 100 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101
                )
            }
            registerOfficeGeofence()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerOfficeGeofence() {
        val geofence = Geofence.Builder()
            .setRequestId("COMCAST_OFFICE")
            .setCircularRegion(12.947238, 80.229203, 150f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val intent = Intent(this, GeofenceReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofencingClient.addGeofences(request, pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "LOKIFFICE_CH", "LOKIFFICE Shift Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            val prefs = context.getSharedPreferences("lokiffice", Context.MODE_PRIVATE)
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
