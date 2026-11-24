package com.fuelstride.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import org.json.JSONArray
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private val prefs by lazy { getSharedPreferences("fuelstride", MODE_PRIVATE) }
    private val stepsPermission = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private val spotify by lazy { Spotify(prefs) }
    private val media by lazy {
        MediaHub(this) { json ->
            runOnUiThread {
                if (::web.isInitialized) web.evaluateJavascript("window.__mediaUpdate && window.__mediaUpdate(${JSONObject.quote(json)})", null)
            }
        }
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var voiceId: String? = null
    private var healthId: String? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        deliverFile(uri)
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        deliverFile(if (ok) cameraUri else null)
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
        voiceId?.let { resolve(it, true, text) }
        voiceId = null
    }
    private val healthPermission =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            healthId?.let { resolve(it, true, if (granted.containsAll(stepsPermission)) "granted" else "denied") }
            healthId = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        // Keep the page between the status bar and the navigation bar (newer Android draws apps edge to edge)
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                try {
                    if (params.isCaptureEnabled) {
                        val dir = File(cacheDir, "photos").apply { mkdirs() }
                        dir.listFiles()?.forEach { it.delete() }
                        val f = File(dir, "meal-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                        cameraUri = uri
                        takePicture.launch(uri)
                    } else {
                        pickImage.launch("image/*")
                    }
                } catch (e: ActivityNotFoundException) {
                    deliverFile(null)
                }
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.scheme == "file") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let the page close an open guide first
                web.evaluateJavascript("(window.__onBack && window.__onBack()) ? 1 : 0") { r ->
                    if (r?.trim() == "1") return@evaluateJavascript
                    if (web.canGoBack()) web.goBack() else finish()
                }
            }
        })
        applySystemBars(isSystemDark())
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null)
        media.start()
    }

    override fun onPause() {
        media.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyRedirect(intent)
    }

    /** Spotify sends the browser back to fuelstride://spotify?code=... after sign-in. */
    private fun handleSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "fuelstride" || data.host != "spotify") return
        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        val error = data.getQueryParameter("error")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { spotify.finishAuth(code, state, error) }
            web.evaluateJavascript("window.__spotifyAuth && window.__spotifyAuth(${JSONObject.quote(result)})", null)
        }
    }

    private fun openSpotifyUri(uri: String) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(app); return } catch (_: ActivityNotFoundException) {}
        // Spotify app not installed: open the web player link instead
        val parts = uri.split(":")
        if (parts.size >= 3) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/${parts[1]}/${parts[2]}"))) } catch (_: Exception) {}
        }
    }

    private fun deliverFile(uri: Uri?) {
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    private fun resolve(id: String, ok: Boolean, payload: String) {
        runOnUiThread {
            web.evaluateJavascript(
                "window.__native && window.__native(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(payload)})",
                null
            )
        }
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Colours the status and navigation bars to match the page's light or dark look. */
    private fun applySystemBars(dark: Boolean) {
        val color = if (dark) 0xFF0E1412.toInt() else 0xFFEDF1EE.toInt()
        @Suppress("DEPRECATION")
        window.statusBarColor = color
        @Suppress("DEPRECATION")
        window.navigationBarColor = color
        if (::web.isInitialized) web.setBackgroundColor(color)
        // Behind the status and navigation bars (they're see-through on newer Android)
        if (::root.isInitialized) root.setBackgroundColor(color)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    // The phone switched between light and dark mode (the activity is kept, not recreated)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::web.isInitialized) web.evaluateJavascript("window.__systemTheme && window.__systemTheme()", null)
    }

    private fun progress(id: String, stage: String) {
        runOnUiThread {
            web.evaluateJavascript(
                "window.__nativeProgress && window.__nativeProgress(${JSONObject.quote(id)}, ${JSONObject.quote(stage)})",
                null
            )
        }
    }

    /** Start of the day to the end of the day (or now, for today). Null for future days. */
    private fun dayRange(date: LocalDate): Pair<Instant, Instant>? {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val end = if (dayEnd.isAfter(now)) now else dayEnd
        return if (end.isAfter(start)) start to end else null
    }

    /** Health Connect's de-duplicated total, using the app priority order set in Health Connect. */
    private suspend fun stepsOn(date: LocalDate): Long {
        val (start, end) = dayRange(date) ?: return 0
        val result = HealthConnectClient.getOrCreate(this).aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    /** Steps per source app for a day, plus when each app last wrote steps. Returned as JSON for the page. */
    private suspend fun stepsDetail(date: LocalDate): JSONObject {
        val out = JSONObject().put("total", stepsOn(date))
        val (start, end) = dayRange(date) ?: return out.put("sources", JSONArray())
        val client = HealthConnectClient.getOrCreate(this)
        val perApp = mutableMapOf<String, Pair<Long, Instant>>()
        var token: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token
                )
            )
            for (r in page.records) {
                val pkg = r.metadata.dataOrigin.packageName
                val prev = perApp[pkg]
                perApp[pkg] = Pair((prev?.first ?: 0L) + r.count, if (prev == null || r.endTime.isAfter(prev.second)) r.endTime else prev.second)
            }
            token = page.pageToken
        } while (!token.isNullOrEmpty())
        val sources = JSONArray()
        perApp.entries.sortedByDescending { it.value.first }.forEach { (pkg, v) ->
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            sources.put(JSONObject().put("app", label).put("pkg", pkg).put("steps", v.first).put("last", v.second.toEpochMilli()))
        }
        return out.put("sources", sources)
    }

    /** Methods the page calls as window.Android.*. Async ones answer through window.__native(id, ok, payload). */
    inner class Bridge {
        @JavascriptInterface
        fun hasApiKey(): Boolean = !prefs.getString("api_key", "").isNullOrBlank()

        @JavascriptInterface
        fun setApiKey(key: String) {
            prefs.edit().putString("api_key", key.trim()).apply()
        }

        @JavascriptInterface
        fun ask(id: String, prompt: String, imageBase64: String, effort: String) {
            val key = prefs.getString("api_key", "") ?: ""
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    Nim.ask(key, prompt, imageBase64, effort) { stage -> progress(id, stage) }
                }
                resolve(id, r.ok, r.payload)
            }
        }

        @JavascriptInterface
        fun healthStatus(): String = when (HealthConnectClient.getSdkStatus(this@MainActivity)) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "needs_install"
            else -> "unavailable"
        }

        @JavascriptInterface
        fun healthConnect(id: String) {
            runOnUiThread {
                healthId = id
                try {
                    healthPermission.launch(stepsPermission)
                } catch (e: Exception) {
                    healthId = null
                    resolve(id, false, "health_error")
                }
            }
        }

        @JavascriptInterface
        fun healthSteps(id: String, dateKey: String) {
            lifecycleScope.launch {
                try {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    val granted = client.permissionController.getGrantedPermissions()
                    if (!granted.containsAll(stepsPermission)) {
                        resolve(id, false, "no_permission")
                        return@launch
                    }
                    resolve(id, true, stepsOn(LocalDate.parse(dateKey)).toString())
                } catch (e: Exception) {
                    resolve(id, false, "health_error")
                }
            }
        }

        // ----- Appearance -----
        @JavascriptInterface
        fun isSystemDark(): Boolean = this@MainActivity.isSystemDark()

        @JavascriptInterface
        fun setSystemBars(dark: Boolean) { runOnUiThread { applySystemBars(dark) } }

        // ----- Music: now playing + controls -----
        @JavascriptInterface
        fun mediaRefresh() { runOnUiThread { media.start() } }

        @JavascriptInterface
        fun mediaCommand(cmd: String, arg: String) {
            runOnUiThread { media.command(cmd, arg.toLongOrNull() ?: 0L) }
        }

        @JavascriptInterface
        fun openMediaAccessSettings() {
            runOnUiThread {
                val detail = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                        android.content.ComponentName(this@MainActivity, MediaNotifListener::class.java).flattenToString())
                try { startActivity(detail) } catch (_: Exception) {
                    try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (_: Exception) {}
                }
            }
        }

        // ----- Music: Spotify account -----
        @JavascriptInterface
        fun spotifyStatus(): String =
            JSONObject().put("clientId", spotify.clientId).put("connected", spotify.connected)
                .put("redirect", Spotify.REDIRECT).toString()

        @JavascriptInterface
        fun spotifyConnect(clientId: String) {
            val url = spotify.beginAuth(clientId)
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun spotifyDisconnect() = spotify.disconnect()

        @JavascriptInterface
        fun spotifyApi(id: String, method: String, path: String, body: String) {
            lifecycleScope.launch {
                val (status, text) = withContext(Dispatchers.IO) { spotify.call(method, path, body) }
                resolve(id, true, JSONObject().put("status", status).put("body", text).toString())
            }
        }

        @JavascriptInterface
        fun openSpotify(uri: String) { runOnUiThread { openSpotifyUri(uri) } }

        @JavascriptInterface
        fun healthDetail(id: String, dateKey: String) {
            lifecycleScope.launch {
                try {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    if (!client.permissionController.getGrantedPermissions().containsAll(stepsPermission)) {
                        resolve(id, false, "no_permission")
                        return@launch
                    }
                    resolve(id, true, stepsDetail(LocalDate.parse(dateKey)).toString())
                } catch (e: Exception) {
                    resolve(id, false, "health_error")
                }
            }
        }

        /** Opens Health Connect's settings, where data sources and their priority are managed. */
        @JavascriptInterface
        fun openHealthSettings() {
            runOnUiThread {
                val intents = listOf(
                    Intent("android.health.connect.action.MANAGE_HEALTH_DATA"),
                    Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                )
                for (i in intents) {
                    try { startActivity(i); return@runOnUiThread } catch (_: Exception) {}
                }
            }
        }

        /** Opens Samsung Health so it pushes its latest steps into Health Connect. */
        @JavascriptInterface
        fun openSamsungHealth() {
            runOnUiThread {
                val i = packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")
                if (i != null) startActivity(i)
            }
        }

        @JavascriptInterface
        fun openHealthConnectInstall() {
            runOnUiThread {
                val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
                    } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun voice(id: String) {
            runOnUiThread {
                voiceId = id
                try {
                    speech.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
                    )
                } catch (e: ActivityNotFoundException) {
                    voiceId = null
                    resolve(id, false, "no_voice")
                }
            }
        }
    }
}
