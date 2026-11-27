package com.nabil.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.speech.RecognizerIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

private val BG = Color.parseColor("#121214")
private val BAR = Color.parseColor("#1B1B1F")
private val PILL = Color.parseColor("#2A2A30")

private val TRACKING_PARAMS = setOf(
    "fbclid", "gclid", "msclkid", "dclid", "yclid", "mc_eid", "mc_cid", "igshid", "_ga", "_gl",
    "ref_src", "ref_url", "spm", "vero_id", "wickedid", "twclid", "ttclid", "gbraid", "wbraid"
)

private val HOME_HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#121214;color:#eee;font-family:sans-serif}main{padding:20vh 22px 0}h1{font-weight:300;font-size:28px;margin:0 0 18px}input{width:100%;box-sizing:border-box;padding:14px 18px;border-radius:28px;border:0;background:#2a2a30;color:#eee;font-size:17px;outline:0}nav{display:flex;flex-wrap:wrap;gap:10px;margin-top:22px}a{color:#4fd1c5;background:#1e1e22;padding:10px 14px;border-radius:14px;text-decoration:none;font-size:14px}</style></head><body><main><h1>Dekh Browser</h1><form action="https://duckduckgo.com/" method="get"><input name="q" placeholder="Search privately" autocomplete="off"></form><nav><a href="https://en.wikipedia.org">Wikipedia</a><a href="https://github.com">GitHub</a><a href="https://www.openstreetmap.org">Maps</a><a href="https://www.youtube.com">YouTube</a></nav></main></body></html>"""

private val READER_JS = """(function(){
var root=document.querySelector('article')||document.querySelector('main')||document.body;
var nodes=root.querySelectorAll('h1,h2,h3,p,blockquote,li,pre,img');
var out=document.createElement('div');
for(var i=0;i<nodes.length;i++){var n=nodes[i];
 if(n.tagName==='IMG'){if(n.naturalWidth>200){var im=document.createElement('img');im.src=n.currentSrc||n.src;out.appendChild(im);}continue;}
 var t=(n.textContent||'').trim();
 if(t.length<25&&!/^H/.test(n.tagName))continue;
 var c=document.createElement(n.tagName==='LI'?'p':n.tagName.toLowerCase());
 c.textContent=t;out.appendChild(c);}
var title=document.title;
document.head.innerHTML='<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{max-width:42em;margin:0 auto;padding:16px;font:19px/1.6 Georgia,serif;background:#faf7f0;color:#222}img{max-width:100%;height:auto}h1,h2,h3{font-family:sans-serif;line-height:1.25}blockquote{border-left:3px solid #aaa;margin-left:0;padding-left:12px;color:#555}</style>';
document.body.innerHTML='';
document.body.removeAttribute('class');document.body.removeAttribute('style');
var h=document.createElement('h1');h.textContent=title;
document.body.appendChild(h);document.body.appendChild(out);window.scrollTo(0,0);
})();"""

class Tab(val web: WebView) {
    var title: String = ""
    var url: String = ""
    var home: Boolean = true
    @Volatile var host: String = ""
}

class GestureBar(ctx: Context, private val det: GestureDetector) : LinearLayout(ctx) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        det.onTouchEvent(ev)
        return false
    }
}

open class MainActivity : Activity() {

    protected open val startInCar: Boolean get() = false

    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private lateinit var root: FrameLayout
    private lateinit var holder: FrameLayout
    private lateinit var bar: GestureBar
    private lateinit var urlBox: EditText
    private lateinit var tabBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var prefs: SharedPreferences
    private lateinit var defaultUA: String

    private val blocked = AtomicInteger(0)
    private val blockHosts = HashSet<String>()
    private val upgraded = HashSet<String>()
    private val noUpgrade = HashSet<String>()
    @Volatile private var shieldOff: Set<String> = emptySet()

    private var barShown = true
    @Volatile private var blockOn = true
    private var httpsUp = true
    private var stripParams = true
    private var block3p = true
    private var desktopDefault = false
    private var textPct = 100
    private var homeSig = ""
    private var askedLoc = false
    private lateinit var homeBtn: TextView
    private lateinit var gearBtn: TextView
    private var carMode = false
    private var speedLock = true
    private var watching = false
    private var lastToast = 0L
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private lateinit var micBtn: TextView
    private lateinit var menuBtn: TextView
    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun cw(): WebView? = tabs.getOrNull(cur)?.web

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nb", MODE_PRIVATE)
        defaultUA = WebSettings.getDefaultUserAgent(this)
        shieldOff = (prefs.getStringSet("shieldoff", emptySet()) ?: emptySet()).toSet()
        loadSettings()
        homeSig = carMode.toString() + engine()
        try {
            assets.open("blocklist.txt").bufferedReader().forEachLine {
                val l = it.trim()
                if (l.isNotEmpty() && !l.startsWith("#")) blockHosts.add(l.lowercase())
            }
        } catch (_: Exception) {
        }

        window.statusBarColor = BAR
        window.navigationBarColor = BAR

        root = FrameLayout(this)
        root.setBackgroundColor(BG)
        holder = FrameLayout(this)
        holder.isFocusable = true
        holder.isFocusableInTouchMode = true
        root.addView(holder, FrameLayout.LayoutParams(-1, -1))
        buildBar()
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        applyCarUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.dataString
        val text = if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        val target = data ?: text
        if (target != null) newTab(target) else if (tabs.isEmpty()) newTab(startTarget())
    }

    override fun onPause() {
        super.onPause()
        cw()?.onPause()
        tabs.getOrNull(cur)?.let {
            if (!it.home && it.url.startsWith("http")) prefs.edit().putString("last_url", it.url).apply()
        }
        stopSpeedWatch()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        cw()?.onResume()
        loadSettings()
        applyCarUi()
        val sig = carMode.toString() + engine()
        if (sig != homeSig) {
            homeSig = sig
            tabs.forEach { if (it.home) loadHome(it.web) }
        }
        if (carMode) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (!askedLoc) {
                    askedLoc = true
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 12)
                }
            } else startSpeedWatch()
        } else stopSpeedWatch()
    }

    override fun onDestroy() {
        if (isFinishing && prefs.getBoolean("clear_exit", false)) {
            tabs.firstOrNull()?.web?.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            exitFull(); return
        }
        if (urlBox.hasFocus()) {
            unfocus(); return
        }
        val w = cw()
        val t = tabs.getOrNull(cur)
        if (w != null && t != null && !t.home && w.canGoBack()) w.goBack()
        else if (tabs.size > 1) closeTab(cur)
        else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7) {
            chooserCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooserCb = null
        } else if (requestCode == 8) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (resultCode == RESULT_OK && said != null) handleVoice(said)
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    // ---------------------------------------------------------------- UI

    private fun buildBar() {
        val det = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cw()?.scrollTo(0, 0)
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) * 2 && abs(dx) > dp(80)) {
                    switchBy(if (dx < 0) 1 else -1); return true
                }
                if (dy < -dp(60) && abs(dy) > abs(dx) * 2) {
                    showTabs(); return true
                }
                return false
            }
        })

        bar = GestureBar(this, det)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(BAR)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.GONE
        bar.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(6), dp(8), dp(8))

        urlBox = EditText(this)
        urlBox.hint = "Search or enter address"
        urlBox.setHintTextColor(Color.parseColor("#8A8A93"))
        urlBox.setTextColor(Color.WHITE)
        urlBox.textSize = 15f
        urlBox.setPadding(dp(18), 0, dp(18), 0)
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setSelectAllOnFocus(true)
        val pill = GradientDrawable()
        pill.setColor(PILL)
        pill.cornerRadius = dp(22).toFloat()
        urlBox.background = pill
        urlBox.setOnEditorActionListener { v, _, _ ->
            navigate(v.text.toString())
            unfocus()
            true
        }
        urlBox.setOnFocusChangeListener { _, focused ->
            if (focused) {
                showBar()
                val t = tabs.getOrNull(cur)
                urlBox.setText(if (t == null || t.home) "" else t.url)
                urlBox.selectAll()
            } else refreshBar()
        }

        tabBtn = TextView(this)
        tabBtn.setTextColor(Color.WHITE)
        tabBtn.textSize = 13f
        tabBtn.gravity = Gravity.CENTER
        val box = GradientDrawable()
        box.setColor(Color.TRANSPARENT)
        box.setStroke(dp(2), Color.WHITE)
        box.cornerRadius = dp(8).toFloat()
        tabBtn.background = box
        tabBtn.setOnClickListener { showTabs() }

        menuBtn = TextView(this)
        menuBtn.text = "\u22EE"
        menuBtn.setTextColor(Color.WHITE)
        menuBtn.textSize = 22f
        menuBtn.gravity = Gravity.CENTER
        menuBtn.setOnClickListener { showMenu() }

        micBtn = TextView(this)
        micBtn.text = "\uD83C\uDFA4"
        micBtn.textSize = 18f
        micBtn.gravity = Gravity.CENTER
        micBtn.setOnClickListener { startVoice() }

        homeBtn = TextView(this)
        homeBtn.text = "\uD83C\uDFE0"
        homeBtn.textSize = 18f
        homeBtn.gravity = Gravity.CENTER
        homeBtn.setOnClickListener { goHome() }

        gearBtn = TextView(this)
        gearBtn.text = "\u2699\uFE0F"
        gearBtn.textSize = 18f
        gearBtn.gravity = Gravity.CENTER
        gearBtn.setOnClickListener { openSettings() }

        row.addView(homeBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.addView(urlBox, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(micBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        val tlp = LinearLayout.LayoutParams(dp(32), dp(32))
        tlp.leftMargin = dp(10)
        tlp.rightMargin = dp(4)
        row.addView(tabBtn, tlp)
        row.addView(gearBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(44)))
        bar.addView(row, LinearLayout.LayoutParams(-1, -2))

        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun showBar() {
        if (!barShown) {
            barShown = true
            bar.animate().translationY(0f).setDuration(150).start()
        }
    }

    private fun hideBar() {
        if (barShown) {
            barShown = false
            bar.animate().translationY(bar.height.toFloat()).setDuration(150).start()
        }
    }

    private fun unfocus() {
        holder.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBox.windowToken, 0)
    }

    private fun refreshBar() {
        tabBtn.text = tabs.size.toString()
        if (!urlBox.hasFocus()) {
            val t = tabs.getOrNull(cur)
            urlBox.setText(if (t == null || t.home) "" else t.host.ifEmpty { t.url })
        }
    }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(input: String?) {
        val w = WebView(this)
        val t = Tab(w)
        w.tag = t
        w.setBackgroundColor(BG)

        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = false // Google Safe Browsing needs Play Services; off to stay Google-free
        s.setGeolocationEnabled(true)
        s.textZoom = zoomPct()
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !block3p)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    if (!r.isForMainFrame) return false
                    val str = u.toString()
                    val c = clean(str)
                    val host = u.host ?: ""
                    val needUp = httpsUp && scheme == "http" && host !in noUpgrade && !isLocal(host)
                    if (c != str || needUp) {
                        load(v, c)
                        return true
                    }
                    return false
                }
                if (scheme == "dekh") {
                    openNav(u.getQueryParameter("q"))
                    return true
                }
                if (scheme == "mailto" || scheme == "tel" || scheme == "sms" || scheme == "geo") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (_: Exception) {
                    }
                    return true
                }
                // Block every other scheme (intent:, market:, custom app links) except safe internals.
                return !(scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript")
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (r.isForMainFrame) return null
                val h = r.url.host ?: return null
                val pageHost = (v.tag as? Tab)?.host ?: ""
                if (blockOn && isBlocked(h) && !shieldOff.contains(pageHost)) {
                    blocked.incrementAndGet()
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                val tab = v.tag as Tab
                tab.url = url
                tab.host = Uri.parse(url).host ?: ""
                if (v === cw()) {
                    refreshBar()
                    showBar()
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                val tab = v.tag as Tab
                tab.title = v.title ?: ""
                if (v === cw()) progress.visibility = View.GONE
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame && r.url.scheme == "https") {
                    val h = r.url.host ?: ""
                    if (h in upgraded && h !in noUpgrade) {
                        noUpgrade.add(h)
                        v.loadUrl(r.url.toString().replaceFirst("https://", "http://"))
                    }
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (v === cw()) {
                    progress.progress = p
                    progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                (v.tag as? Tab)?.title = title ?: ""
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                val h = Uri.parse(origin).host ?: ""
                val trusted = h.endsWith("google.com") || h.endsWith("waze.com") || h.endsWith("openstreetmap.org")
                if (!trusted) {
                    cb.invoke(origin, false, false)
                } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false)
                } else {
                    pendingGeo = Pair(origin, cb)
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 11)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onShowFileChooser(
                v: WebView,
                cb: ValueCallback<Array<Uri>>,
                p: WebChromeClient.FileChooserParams
            ): Boolean {
                chooserCb?.onReceiveValue(null)
                chooserCb = cb
                return try {
                    startActivityForResult(p.createIntent(), 7)
                    true
                } catch (e: Exception) {
                    chooserCb = null
                    false
                }
            }

            override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    cb.onCustomViewHidden(); return
                }
                customView = view
                customCb = cb
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                bar.visibility = View.GONE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }

            override fun onHideCustomView() {
                exitFull()
            }
        }

        w.setOnScrollChangeListener { _, _, y, _, oy ->
            val d = y - oy
            if (y < dp(20) || d < -dp(6)) showBar()
            else if (d > dp(10) && !urlBox.hasFocus()) hideBar()
        }

        w.setDownloadListener { url, ua, cd, mime, _ ->
            try {
                val name = URLUtil.guessFileName(url, cd, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                req.setMimeType(mime)
                req.addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }

        w.setOnLongClickListener { v ->
            val r = (v as WebView).hitTestResult
            val link = r.extra
            if (r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && link != null) {
                AlertDialog.Builder(this)
                    .setTitle(Uri.parse(link).host ?: link)
                    .setItems(arrayOf("Open in new tab", "Copy link", "Share")) { _, i ->
                        when (i) {
                            0 -> newTab(link)
                            1 -> copy(link)
                            2 -> share(link)
                        }
                    }.show()
                true
            } else false
        }

        tabs.add(t)
        showTab(tabs.lastIndex)
        if (input == null) {
            loadHome(w)
        } else navigate(input, w)
    }

    private fun showTab(i: Int) {
        cur = i
        holder.removeAllViews()
        holder.addView(tabs[i].web, FrameLayout.LayoutParams(-1, -1))
        tabs.forEachIndexed { j, t -> if (j == i) t.web.onResume() else t.web.onPause() }
        showBar()
        refreshBar()
    }

    private fun switchBy(d: Int) {
        val n = cur + d
        if (n in tabs.indices) showTab(n)
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        holder.removeAllViews()
        tabs[i].web.destroy()
        tabs.removeAt(i)
        if (tabs.isEmpty()) newTab(null) else showTab(minOf(i, tabs.lastIndex))
    }

    private fun closeAll() {
        holder.removeAllViews()
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        newTab(null)
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t ->
            (if (i == cur) "\u25CF " else "") + (if (t.home) "New tab" else t.title.ifEmpty { t.host })
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${tabs.size} tabs")
            .setItems(names) { _, i -> showTab(i) }
            .setPositiveButton("New tab") { _, _ ->
                newTab(null)
                urlBox.requestFocus()
            }
            .setNegativeButton("Close current") { _, _ -> closeTab(cur) }
            .setNeutralButton("Close all") { _, _ -> closeAll() }
            .show()
    }

    // ---------------------------------------------------------------- menu & tools

    private fun showMenu() {
        val t = tabs.getOrNull(cur) ?: return
        val host = t.host
        val desktop = (prefs.getStringSet("desktop", emptySet()) ?: emptySet()).contains(host)
        val shieldOn = host !in shieldOff
        val items = arrayOf(
            "New tab",
            "Reload",
            "Share link",
            "Reader mode",
            "Find in page",
            if (desktop) "Switch to mobile site" else "Request desktop site",
            if (shieldOn) "Shields ON (${blocked.get()} blocked) \u2013 turn off for this site"
            else "Shields OFF here \u2013 turn on",
            "Clear browsing data",
            "Close all tabs",
            if (carMode) "Car mode: ON \u2013 turn off" else "Car mode: OFF \u2013 turn on",
            if (speedLock) "Pause YouTube video while driving: ON" else "Pause YouTube video while driving: OFF",
            "Dekh Nav (voice navigation)",
            "Settings"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            val w = cw()
            when (i) {
                0 -> {
                    newTab(null)
                    urlBox.requestFocus()
                }
                1 -> w?.reload()
                2 -> if (!t.home) share(t.url)
                3 -> w?.evaluateJavascript(READER_JS, null)
                4 -> findInPage()
                5 -> if (host.isNotEmpty() && w != null) {
                    toggle("desktop", host)
                    applyUA(w, host)
                    w.reload()
                }
                6 -> if (host.isNotEmpty() && w != null) {
                    toggle("shieldoff", host)
                    shieldOff = (prefs.getStringSet("shieldoff", emptySet()) ?: emptySet()).toSet()
                    w.reload()
                }
                7 -> clearData()
                8 -> closeAll()
                9 -> toggleCar()
                11 -> openNav(null)
                12 -> openSettings()
                10 -> {
                    speedLock = !speedLock
                    prefs.edit().putBoolean("speedlock", speedLock).apply()
                    Toast.makeText(this, if (speedLock) "Video will pause above 8 km/h" else "Video lock off", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private fun toggle(key: String, host: String) {
        val s = HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())
        if (!s.remove(host)) s.add(host)
        prefs.edit().putStringSet(key, s).apply()
    }

    private fun findInPage() {
        val w = cw() ?: return
        val e = EditText(this)
        e.hint = "Find in page"
        e.setSingleLine()
        var last = ""
        val d = AlertDialog.Builder(this)
            .setView(e)
            .setPositiveButton("Next", null)
            .setNegativeButton("Done", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val q = e.text.toString()
                if (q.isEmpty()) return@setOnClickListener
                if (q != last) {
                    last = q
                    w.findAllAsync(q)
                } else w.findNext(true)
            }
        }
        d.setOnDismissListener { w.clearMatches() }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun clearData() {
        tabs.forEach {
            it.web.clearCache(true)
            it.web.clearHistory()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(this, "Cache, cookies and site data cleared", Toast.LENGTH_SHORT).show()
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("link", clean(s)))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(s: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, clean(s))
        startActivity(Intent.createChooser(i, null))
    }

    private fun exitFull() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        bar.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        customCb?.onCustomViewHidden()
        customCb = null
    }

    // ---------------------------------------------------------------- navigation core

    private fun navigate(input: String, web: WebView? = cw()) {
        val w = web ?: return
        val u = input.trim()
        if (u.isEmpty()) return
        val target = when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            !u.contains(' ') && (u.contains('.') || u.startsWith("localhost")) -> "https://$u"
            else -> searchUrl(u)
        }
        load(w, target)
    }

    private fun load(w: WebView, raw: String) {
        val t = w.tag as Tab
        var u = clean(raw)
        val host = Uri.parse(u).host ?: ""
        if (httpsUp && u.startsWith("http://") && host !in noUpgrade && !isLocal(host)) {
            upgraded.add(host)
            u = u.replaceFirst("http://", "https://")
        }
        t.home = false
        applyUA(w, host, Uri.parse(u).path ?: "")
        w.loadUrl(u)
    }

    private fun applyUA(w: WebView, host: String, path: String = "") {
        val mapsWeb = host.endsWith("google.com") && path.startsWith("/maps")
        val desktop = mapsWeb || desktopDefault || (prefs.getStringSet("desktop", emptySet()) ?: emptySet()).contains(host)
        w.settings.userAgentString =
            if (desktop) defaultUA.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
            else defaultUA
        w.settings.loadWithOverviewMode = desktop
    }

    private fun isLocal(h: String): Boolean =
        h == "localhost" || h.startsWith("192.168.") || h.startsWith("10.") || h.endsWith(".local")

    private fun clean(url: String): String {
        if (!stripParams) return url
        val u = Uri.parse(url)
        if (!u.isHierarchical) return url
        val names = u.queryParameterNames
        val bad = names.filter { it.startsWith("utm_") || it in TRACKING_PARAMS }
        if (bad.isEmpty()) return url
        val b = u.buildUpon().clearQuery()
        for (n in names) if (n !in bad) for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
        return b.build().toString()
    }

    private fun isBlocked(host: String): Boolean {
        var h = host.lowercase()
        var i: Int
        do {
            if (blockHosts.contains(h)) return true
            i = h.indexOf('.')
            if (i >= 0) h = h.substring(i + 1)
        } while (i >= 0)
        return false
    }

    // ---------------------------------------------------------------- car mode

    private fun loadSettings() {
        carMode = startInCar || prefs.getBoolean("car", false)
        speedLock = prefs.getBoolean("speedlock", true)
        blockOn = prefs.getBoolean("adblock", true)
        httpsUp = prefs.getBoolean("https_up", true)
        stripParams = prefs.getBoolean("strip", true)
        block3p = prefs.getBoolean("block3p", true)
        desktopDefault = prefs.getBoolean("desktop_default", false)
        textPct = prefs.getString("textsize", "100")?.toIntOrNull() ?: 100
        tabs.forEach { CookieManager.getInstance().setAcceptThirdPartyCookies(it.web, !block3p) }
    }

    private fun zoomPct(): Int = textPct * (if (carMode) 125 else 100) / 100

    private fun engine(): String =
        prefs.getString("engine", "https://duckduckgo.com/?q=%s") ?: "https://duckduckgo.com/?q=%s"

    private fun engineBase(): String = engine().substringBefore("?")

    private fun searchUrl(q: String): String {
        val e = engine()
        val enc = Uri.encode(q)
        return if (e.contains("%s")) e.replace("%s", enc) else e + enc
    }

    private fun homeHtml(): String =
        if (carMode) carHome() else HOME_HTML.replace("https://duckduckgo.com/", engineBase())

    private fun startTarget(): String? {
        if (prefs.getString("start_mode", "home") == "last") {
            val last = prefs.getString("last_url", "") ?: ""
            if (last.isNotBlank()) return last
        }
        val hp = prefs.getString("homepage", "") ?: ""
        return if (hp.isBlank()) null else hp
    }

    private fun goHome() {
        val w = cw() ?: return
        val t = w.tag as Tab
        unfocus()
        val hp = prefs.getString("homepage", "") ?: ""
        if (hp.isBlank()) {
            t.home = true
            t.url = ""
            t.host = ""
            loadHome(w)
            refreshBar()
        } else navigate(hp, w)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openNav(q: String?) {
        val i = Intent(this, NavActivity::class.java)
        if (!q.isNullOrBlank()) i.putExtra("q", q.trim())
        startActivity(i)
    }

    private fun loadHome(w: WebView) {
        w.loadDataWithBaseURL(null, homeHtml(), "text/html", "utf-8", null)
    }

    private fun toggleCar() {
        carMode = !carMode
        prefs.edit().putBoolean("car", carMode).apply()
        applyCarUi()
        tabs.forEach { if (it.home) loadHome(it.web) }
        if (carMode) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 12)
            else startSpeedWatch()
        } else stopSpeedWatch()
    }

    private fun applyCarUi() {
        val k = if (carMode) 1.5f else 1f
        urlBox.textSize = 15f * k
        urlBox.layoutParams.height = dp((44 * k).toInt())
        micBtn.textSize = 18f * k
        micBtn.layoutParams.width = dp((40 * k).toInt())
        micBtn.layoutParams.height = dp((44 * k).toInt())
        tabBtn.textSize = 13f * k
        tabBtn.layoutParams.width = dp((32 * k).toInt())
        tabBtn.layoutParams.height = dp((32 * k).toInt())
        menuBtn.textSize = 22f * k
        menuBtn.layoutParams.width = dp((40 * k).toInt())
        menuBtn.layoutParams.height = dp((44 * k).toInt())
        for (b in listOf(homeBtn, gearBtn)) {
            b.textSize = 18f * k
            b.layoutParams.width = dp((40 * k).toInt())
            b.layoutParams.height = dp((44 * k).toInt())
        }
        listOf<View>(urlBox, micBtn, tabBtn, menuBtn, homeBtn, gearBtn, bar).forEach { it.requestLayout() }
        tabs.forEach { it.web.settings.textZoom = zoomPct() }
        if (carMode) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun carHome(): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
        sb.append("body{margin:0;background:#0b0b0d;color:#fff;font-family:sans-serif}main{padding:22px 22px 140px}")
        sb.append("form{margin:0 0 14px}input[type=text]{width:100%;box-sizing:border-box;padding:18px 22px;border-radius:34px;border:0;background:#2a2a30;color:#fff;font-size:22px;outline:0}")
        sb.append(".g{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px;margin-top:6px}")
        sb.append("a{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:22px 8px;font-size:20px;border-radius:24px;background:#1e1e22;color:#fff;text-decoration:none}")
        sb.append("a span{font-size:46px;margin-bottom:6px}</style></head><body><main>")
        sb.append("<form action=\"dekh://nav\"><input type=\"text\" name=\"q\" placeholder=\"Navigate to\u2026 (voice guidance)\" autocomplete=\"off\"></form>")
        sb.append("<form action=\"" + engineBase() + "\"><input type=\"text\" name=\"q\" placeholder=\"Search the web\" autocomplete=\"off\"></form>")
        sb.append("<div class=\"g\">")
        try {
            assets.open("cartiles.txt").bufferedReader().forEachLine { l ->
                val p = l.split("|")
                if (!l.startsWith("#") && p.size >= 3) {
                    sb.append("<a href=\"").append(p[1].trim()).append("\"><span>").append(p[2].trim()).append("</span>").append(p[0].trim()).append("</a>")
                }
            }
        } catch (_: Exception) {
        }
        sb.append("</div></main></body></html>")
        return sb.toString()
    }

    // ---------------------------------------------------------------- voice

    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a search, \"navigate to \u2026\" or \"play \u2026\"")
            startActivityForResult(i, 8)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input isn't available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleVoice(said: String) {
        val l = said.trim().lowercase()
        when {
            l.startsWith("navigate to ") -> openNav(said.trim().substring(12))
            l.startsWith("play ") ->
                navigate("https://music.youtube.com/search?q=" + Uri.encode(said.trim().substring(5)))
            else -> navigate(said)
        }
    }

    // ---------------------------------------------------------------- location / speed lock

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 11) {
            pendingGeo?.let { (origin, cb) -> cb.invoke(origin, ok, false) }
            pendingGeo = null
        }
        if (ok && carMode) startSpeedWatch()
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!carMode || !speedLock) return
            if (loc.speed * 3.6f < 8f) return
            val t = tabs.getOrNull(cur) ?: return
            if (t.host.endsWith("youtube.com") && !t.host.startsWith("music.")) {
                t.web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null)
                val now = System.currentTimeMillis()
                if (now - lastToast > 30000) {
                    lastToast = now
                    Toast.makeText(this@MainActivity, "Video paused while driving", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startSpeedWatch() {
        if (watching || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, locListener)
            watching = true
        } catch (_: Exception) {
        }
    }

    private fun stopSpeedWatch() {
        if (!watching) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locListener)
        } catch (_: Exception) {
        }
        watching = false
    }
}
