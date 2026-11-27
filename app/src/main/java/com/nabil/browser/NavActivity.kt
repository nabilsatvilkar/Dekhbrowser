package com.nabil.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/** Dekh Nav: map + routing + spoken turn-by-turn. Page logic lives in assets/nav.html. */
class NavActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var watching = false
    private var query: String? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            push(l)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    inner class Bridge {
        @JavascriptInterface
        fun speak(t: String) {
            runOnUiThread {
                if (ttsReady) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "nav")
            }
        }

        @JavascriptInterface
        fun openMaps(lat: Double, lon: Double) {
            runOnUiThread {
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=d"))
                    i.setPackage("com.google.android.apps.maps")
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this@NavActivity, "Google Maps app is not installed", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        query = intent.getStringExtra("q")
        tts = TextToSpeech(this, this)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.setGeolocationEnabled(false)
        web.setBackgroundColor(0xFF121214.toInt())
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            // Only the bundled local page may be shown here.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                r.url.scheme != "file"

            override fun onPageFinished(v: WebView, url: String) {
                query?.let { v.evaluateJavascript("window.startQuery&&startQuery(" + JSONObject.quote(it) + ")", null) }
                query = null
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/nav.html")

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps()
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 21)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "No text-to-speech engine found. Voice guidance is off.", Toast.LENGTH_LONG).show()
            return
        }
        var r = tts?.setLanguage(Locale.getDefault())
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = tts?.setLanguage(Locale.ENGLISH)
        }
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) Toast.makeText(this, "Install a text-to-speech voice for spoken directions", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 21 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startGps()
        else Toast.makeText(this, "Location permission is needed for navigation", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (watching) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        for (p in listOf(LocationManager.GPS_PROVIDER) // GPS only: the network provider relies on Google location services) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener)
                    lm.getLastKnownLocation(p)?.let { push(it) }
                    watching = true
                }
            } catch (_: Exception) {
            }
        }
        if (!watching) Toast.makeText(this, "Turn on Location / GPS", Toast.LENGTH_LONG).show()
    }

    private fun push(l: Location) {
        val sp = if (l.hasSpeed()) l.speed else 0f
        val br = if (l.hasBearing()) l.bearing else -1f
        web.evaluateJavascript("window.onPos&&onPos(${l.latitude},${l.longitude},$sp,$br,${l.accuracy})", null)
    }

    override fun onDestroy() {
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
