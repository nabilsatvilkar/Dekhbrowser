package com.nabil.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Plain, dependency-free settings screen. Values live in the "nb" SharedPreferences read by MainActivity. */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout

    private val engines = linkedMapOf(
        "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
        "Google" to "https://www.google.com/search?q=%s",
        "Bing" to "https://www.bing.com/search?q=%s",
        "Brave Search" to "https://search.brave.com/search?q=%s",
        "Startpage" to "https://www.startpage.com/do/search?q=%s"
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nb", MODE_PRIVATE)
        window.statusBarColor = Color.parseColor("#1B1B1F")
        window.navigationBarColor = Color.parseColor("#1B1B1F")
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#121214"))
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(20), dp(8), dp(20), dp(40))
        scroll.addView(list)
        setContentView(scroll)
        build()
    }

    private fun build() {
        list.removeAllViews()

        val head = TextView(this)
        head.text = "\u2190  Settings"
        head.setTextColor(Color.WHITE)
        head.textSize = 24f
        head.setPadding(dp(4), dp(14), dp(4), dp(14))
        head.setOnClickListener { finish() }
        list.addView(head)

        section("General")
        edit("Homepage", "homepage", "https://example.com", "Dekh home page (default)")
        choose(
            "On startup", "start_mode",
            arrayOf("Open my homepage", "Continue where I left off"),
            arrayOf("home", "last"), "home"
        )
        searchEngine()

        section("Privacy")
        toggle("Block ads and trackers", "Blocks known ad and tracker hosts", "adblock", true)
        toggle("Upgrade to HTTPS", "Opens http:// sites securely when possible", "https_up", true)
        toggle("Remove tracking parameters", "Strips utm_*, fbclid, gclid and similar from links", "strip", true)
        toggle("Block third-party cookies", "Stops cross-site tracking cookies", "block3p", true)
        toggle("Clear data when closing the app", "Cache, cookies and site data", "clear_exit", false)
        row("Clear browsing data now", "Cache, cookies and site data") {
            AlertDialog.Builder(this)
                .setTitle("Clear browsing data?")
                .setPositiveButton("Clear") { _, _ ->
                    val w = WebView(this)
                    w.clearCache(true)
                    w.destroy()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        section("Display")
        choose(
            "Text size", "textsize",
            arrayOf("Small (80%)", "Normal (100%)", "Large (125%)", "Extra large (150%)", "Huge (175%)"),
            arrayOf("80", "100", "125", "150", "175"), "100"
        )
        toggle("Request desktop sites by default", "Sites open in their desktop layout", "desktop_default", false)

        section("Car and driving")
        toggle("Car mode", "Big controls, tile home page, screen stays on", "car", false)
        toggle("Pause YouTube video while driving", "Pauses video above 8 km/h (needs location permission)", "speedlock", true)

        section("About")
        row("Dekh Browser 1.1", "Personal browser. Not connected to the Play Store.") {}
        row("Reset all settings", "Also forgets per-site shield and desktop choices") {
            AlertDialog.Builder(this)
                .setTitle("Reset all settings?")
                .setPositiveButton("Reset") { _, _ ->
                    prefs.edit().clear().apply()
                    build()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------------------------------------------------------------- building blocks

    private fun section(t: String) {
        val v = TextView(this)
        v.text = t.uppercase()
        v.setTextColor(Color.parseColor("#4FD1C5"))
        v.textSize = 13f
        v.setPadding(dp(4), dp(26), dp(4), dp(4))
        list.addView(v)
    }

    private fun row(title: String, sub: String?, click: () -> Unit) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(4), dp(14), dp(4), dp(14))
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.textSize = 18f
        r.addView(t)
        if (!sub.isNullOrEmpty()) {
            val s = TextView(this)
            s.text = sub
            s.setTextColor(Color.parseColor("#9A9AA3"))
            s.textSize = 14f
            r.addView(s)
        }
        r.setOnClickListener { click() }
        list.addView(r)
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(4), dp(14), dp(4), dp(14))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.textSize = 18f
        val s = TextView(this)
        s.text = sub
        s.setTextColor(Color.parseColor("#9A9AA3"))
        s.textSize = 14f
        col.addView(t)
        col.addView(s)
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this)
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        list.addView(r)
    }

    private fun choose(title: String, key: String, labels: Array<String>, values: Array<String>, def: String) {
        val cur = prefs.getString(key, def) ?: def
        val idx = values.indexOf(cur)
        row(title, if (idx >= 0) labels[idx] else cur) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, idx) { d, which ->
                    prefs.edit().putString(key, values[which]).apply()
                    d.dismiss()
                    build()
                }
                .show()
        }
    }

    private fun edit(title: String, key: String, hint: String, emptyLabel: String) {
        val cur = prefs.getString(key, "") ?: ""
        row(title, if (cur.isBlank()) emptyLabel else cur) {
            val e = EditText(this)
            e.setText(cur)
            e.hint = hint
            e.setSingleLine()
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(e)
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString(key, e.text.toString().trim()).apply()
                    build()
                }
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset") { _, _ ->
                    prefs.edit().remove(key).apply()
                    build()
                }
                .show()
        }
    }

    private fun searchEngine() {
        val cur = prefs.getString("engine", engines.values.first()) ?: engines.values.first()
        val label = engines.entries.firstOrNull { it.value == cur }?.key ?: "Custom: $cur"
        val names = engines.keys.toTypedArray() + "Custom\u2026"
        row("Search engine", label) {
            AlertDialog.Builder(this)
                .setTitle("Search engine")
                .setItems(names) { _, i ->
                    if (i < engines.size) {
                        prefs.edit().putString("engine", engines.values.elementAt(i)).apply()
                        build()
                    } else customEngine(cur)
                }
                .show()
        }
    }

    private fun customEngine(cur: String) {
        val e = EditText(this)
        e.setText(if (engines.containsValue(cur)) "" else cur)
        e.hint = "https://example.com/search?q=%s"
        e.setSingleLine()
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this)
            .setTitle("Custom search URL (use %s for the query)")
            .setView(e)
            .setPositiveButton("Save") { _, _ ->
                val v = e.text.toString().trim()
                if (v.startsWith("http") && v.contains("%s")) {
                    prefs.edit().putString("engine", v).apply()
                    build()
                } else Toast.makeText(this, "Must start with http and contain %s", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
