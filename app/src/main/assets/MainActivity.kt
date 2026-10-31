package com.journeyos.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.webkit.RenderProcessGoneDetail
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private var web: WebView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var lastTitle = ""
    private val poll = object : Runnable {
        override fun run() { pushMedia(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { startWeb() } catch (t: Throwable) { showError("No se pudo iniciar el WebView:\n" + t.message) }
        requestLocation()
    }

    private var status: TextView? = null
    private var loaded = false
    private var hadError = false
    private val logLines = ArrayList<String>()

    private fun say(t: String) {
        runOnUiThread {
            logLines.add(t)
            while (logLines.size > 7) logLines.removeAt(0)
            status?.text = logLines.joinToString("\n") + "\n[toca aqui para cambiar el modo de dibujo]"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWeb() {
        val prefs = getSharedPreferences("j", MODE_PRIVATE)
        val sw = prefs.getBoolean("sw", true)
        val w = WebView(this)
        w.setBackgroundColor(0xFF03060D.toInt())
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        if (sw) w.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                if (m != null && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) { hadError = true; say("JS: " + m.message()) }
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loaded = true
                say("Pagina cargada")
                ui.postDelayed({ if (!hadError) status?.visibility = View.GONE }, 20000)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) { hadError = true; say("Error: " + error?.description) }
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                web = null
                showError("El WebView se cerro inesperadamente.")
                return true
            }
        }
        w.addJavascriptInterface(Bridge(), "Android")
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF03060D.toInt())
        root.addView(w, FrameLayout.LayoutParams(-1, -1))
        val st = TextView(this)
        st.setTextColor(Color.YELLOW)
        st.setBackgroundColor(0xAA000000.toInt())
        st.textSize = 14f
        st.setPadding(16, 8, 16, 8)
        st.setOnClickListener { prefs.edit().putBoolean("sw", !sw).apply(); recreate() }
        root.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        status = st
        setContentView(root)
        web = w
        say("Journey OS 0.2 - dibujo: " + (if (sw) "software" else "hardware"))
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val pk = WebView.getCurrentWebViewPackage()
                say("WebView: " + (pk?.packageName ?: "?") + " " + (pk?.versionName ?: "?"))
            } catch (t: Throwable) { say("WebView: sin datos") }
        } else say("Android " + Build.VERSION.SDK_INT)
        say("Cargando pagina...")
        w.loadUrl("file:///android_asset/index.html")
        ui.postDelayed({ if (!loaded) say("La pagina NO termino de cargar") }, 8000)
    }

    private fun showError(msg: String) {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.setBackgroundColor(Color.parseColor("#03060D"))
        l.setPadding(60, 60, 60, 60)
        val t = TextView(this)
        t.text = "Journey OS\n\n" + msg
        t.setTextColor(Color.WHITE)
        t.textSize = 22f
        t.gravity = Gravity.CENTER
        l.addView(t)
        fun btn(txt: String, a: () -> Unit) {
            val b = Button(this); b.text = txt; b.setOnClickListener { a() }; l.addView(b)
        }
        btn("Reintentar") { recreate() }
        btn("Ajustes de Android") { launch(Intent(Settings.ACTION_SETTINGS)) }
        btn("Cambiar pantalla de inicio") { launch(Intent(Settings.ACTION_HOME_SETTINGS)) }
        setContentView(l)
    }

    override fun onResume() { super.onResume(); ui.post(poll) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(poll) }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); js("window.onBack&&onBack()") }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { js("window.onBack&&onBack()") }

    private fun js(s: String) { runOnUiThread { web?.evaluateJavascript(s, null) } }

    // ---------- GPS / velocidad ----------
    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else startGps()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startGps()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    val k = if (l.hasSpeed()) Math.round(l.speed * 3.6f) else 0
                    js("window.onSpeed&&onSpeed($k)")
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            })
        } catch (e: Exception) { }
    }

    // ---------- Musica ----------
    private fun hasAccess(): Boolean =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun controller(): MediaController? = try {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(this, NotifService::class.java)).firstOrNull()
    } catch (e: Exception) { null }

    private fun artB64(m: MediaMetadata?): String {
        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: return ""
        val sm = Bitmap.createScaledBitmap(bmp, 160, 160, true)
        val o = ByteArrayOutputStream()
        sm.compress(Bitmap.CompressFormat.JPEG, 80, o)
        return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    private fun pushMedia() {
        if (!hasAccess()) { js("window.onMedia&&onMedia({perm:false})"); return }
        val c = controller()
        if (c == null) { js("window.onMedia&&onMedia({perm:true,none:true})"); return }
        val m = c.metadata
        val s = c.playbackState
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val playing = s?.state == PlaybackState.STATE_PLAYING
        var pos = s?.position ?: 0L
        if (playing && s != null) pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        val o = JSONObject()
        o.put("perm", true)
        o.put("title", title)
        o.put("artist", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
        o.put("dur", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        o.put("pos", pos)
        o.put("playing", playing)
        if (title != lastTitle) { lastTitle = title; o.put("art", artB64(m)) }
        js("window.onMedia&&onMedia($o)")
    }

    // ---------- Apps ----------
    private fun launch(i: Intent?): Boolean {
        if (i == null) return false
        return try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); true } catch (e: Exception) { false }
    }

    private fun icon(d: Drawable): String {
        val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 72, 72)
        d.draw(Canvas(bmp))
        val o = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
        return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }

    private fun launchables() = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != packageName }

    inner class Bridge {
        @JavascriptInterface fun openApp(pkg: String): Boolean = launch(packageManager.getLaunchIntentForPackage(pkg))

        @JavascriptInterface fun openAppByLabel(q: String): Boolean {
            val terms = q.lowercase().split("|")
            val app = launchables().firstOrNull { r ->
                val l = r.loadLabel(packageManager).toString().lowercase()
                terms.any { l.contains(it) }
            } ?: return false
            return launch(packageManager.getLaunchIntentForPackage(app.activityInfo.packageName))
        }

        @JavascriptInterface fun openDialer() { launch(Intent(Intent.ACTION_DIAL)) }
        @JavascriptInterface fun openSettings() { launch(Intent(Settings.ACTION_SETTINGS)) }
        @JavascriptInterface fun openNotificationAccess() { launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        @JavascriptInterface fun getApps(): String {
            val arr = JSONArray()
            launchables().sortedBy { it.loadLabel(packageManager).toString().lowercase() }.forEach { r ->
                arr.put(JSONObject()
                    .put("l", r.loadLabel(packageManager).toString())
                    .put("p", r.activityInfo.packageName)
                    .put("i", icon(r.loadIcon(packageManager))))
            }
            return arr.toString()
        }

        @JavascriptInterface fun mediaToggle() {
            val c = controller() ?: return
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        @JavascriptInterface fun mediaNext() { controller()?.transportControls?.skipToNext() }
        @JavascriptInterface fun mediaPrev() { controller()?.transportControls?.skipToPrevious() }
    }
}
