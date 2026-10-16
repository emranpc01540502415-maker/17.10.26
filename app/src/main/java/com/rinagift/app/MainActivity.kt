package com.rinagift.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pageProgressBar: android.widget.ProgressBar
    private lateinit var splashOverlay: View
    private lateinit var maintenanceOverlay: View
    private lateinit var forceUpdateOverlay: View
    private lateinit var offlineOverlay: View
    private lateinit var floatingButtonsContainer: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var deviceId: String

    private var config: JSONObject? = null
    private var swipeBackEnabled = false
    private var lastTouchDownX = 0f

    // ---------------------------------------------------------
    // Connectivity + advanced cache state
    // ---------------------------------------------------------
    private var hasEverLoadedSuccessfully = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val adminBaseUrl: String by lazy { getString(R.string.admin_base_url).trimEnd('/') }
    private val appVersionCode: Int by lazy { BuildConfig.VERSION_CODE }

    private val heartbeatIntervalMs = 20_000L
    private val popupPollIntervalMs = 60_000L

    private val phoneHintLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                val phone = Identity.getSignInClient(this).getPhoneNumberFromIntent(result.data)
                performCustomerLogin(phone)
            } catch (e: Exception) {
                Log.e("RinaApp", "Phone hint parse failed", e)
            }
        } else {
            SmartLogin.markDeclined(this)
        }
    }

    // ---------------------------------------------------------
    // WebView <input type="file"> support (e.g. the "attach
    // image" button in the customer-support chat widget). Without
    // this, tapping that button does nothing at all — Android
    // WebView never opens a file/gallery picker on its own; the
    // host app has to supply one via onShowFileChooser below.
    // ---------------------------------------------------------
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult

        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val uris: Array<Uri> = when {
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> emptyArray()
        }
        callback.onReceiveValue(if (uris.isNotEmpty()) uris else null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("rina_prefs", MODE_PRIVATE)
        deviceId = getOrCreateDeviceId()
        if (!prefs.contains("install_time")) {
            prefs.edit().putLong("install_time", System.currentTimeMillis()).apply()
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        pageProgressBar = findViewById(R.id.pageProgressBar)
        splashOverlay = findViewById(R.id.splashOverlay)
        maintenanceOverlay = findViewById(R.id.maintenanceOverlay)
        forceUpdateOverlay = findViewById(R.id.forceUpdateOverlay)
        offlineOverlay = findViewById(R.id.offlineOverlay)
        floatingButtonsContainer = findViewById(R.id.floatingButtonsContainer)

        findViewById<Button>(R.id.maintenanceRetryButton).setOnClickListener { fetchConfig() }
        findViewById<TextView>(R.id.offlineRetryButton).setOnClickListener {
            offlineOverlay.visibility = View.GONE
            if (config == null) fetchConfig() else retryLoadRespectingConnectivity()
        }

        requestNotificationPermissionIfNeeded()
        registerConnectivityWatcher()
        fetchConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------
    // Connectivity awareness — used so we NEVER show a black
    // screen or an endless spinner. If there is no network at
    // all, we fail fast straight to the custom offline page (or
    // to cache, if one exists) instead of waiting on a timeout.
    // When connectivity comes back, we auto-recover without the
    // person needing to tap "retry" themselves.
    // ---------------------------------------------------------
    private fun hasActiveNetwork(): Boolean {
        return try {
            val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // if we can't tell, don't block the normal flow
        }
    }

    private fun registerConnectivityWatcher() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (offlineOverlay.visibility == View.VISIBLE) {
                        offlineOverlay.visibility = View.GONE
                        if (config == null) fetchConfig() else retryLoadRespectingConnectivity()
                    }
                }
            }
            override fun onLost(network: Network) {
                // Handled reactively by onReceivedError/timeout — no
                // need to interrupt a page that's still fine in cache.
            }
        }
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun retryLoadRespectingConnectivity() {
        webView.settings.cacheMode = if (hasActiveNetwork())
            android.webkit.WebSettings.LOAD_DEFAULT
        else
            android.webkit.WebSettings.LOAD_CACHE_ONLY
        webView.reload()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 1001)
            }
        }
    }

    // ---------------------------------------------------------
    // Device ID
    // ---------------------------------------------------------
    private fun getOrCreateDeviceId(): String {
        prefs.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    // ---------------------------------------------------------
    // Config fetch (api/config.php)
    // ---------------------------------------------------------
    private fun fetchConfig() {
        splashOverlay.visibility = View.VISIBLE
        maintenanceOverlay.visibility = View.GONE
        forceUpdateOverlay.visibility = View.GONE

        // Fail fast: if there's clearly no network at all, don't wait on a
        // timeout — go straight to the WebView, which (if this device has
        // opened the site before) can still serve the site instantly from
        // its own local HTTP cache while offline.
        if (!hasActiveNetwork() && config == null && !hasEverLoadedSuccessfully) {
            splashOverlay.visibility = View.GONE
            showOffline("ইন্টারনেট সংযোগ পাওয়া যায়নি। সংযোগ চালু হলে এটি নিজে থেকেই লোড হবে।")
            return
        }

        Thread {
            val result = httpGet("$adminBaseUrl/api/config.php?device_id=$deviceId")
            mainHandler.post {
                if (result != null) {
                    try {
                        config = JSONObject(result)
                        applyConfig(config!!)
                    } catch (e: Exception) {
                        Log.e("RinaApp", "Bad config JSON", e)
                        showOffline("সার্ভার থেকে সঠিক তথ্য পাওয়া যায়নি। আবার চেষ্টা করুন।")
                    }
                } else {
                    showOffline(getString(R.string.app_name) + " লোড করা যাচ্ছে না। ইন্টারনেট সংযোগ চেক করুন।")
                }
            }
        }.start()
    }

    private fun applyConfig(cfg: JSONObject) {
        // theme color
        try {
            val color = Color.parseColor(cfg.optString("theme_color", "#8B7FD6"))
            window.statusBarColor = color
        } catch (_: Exception) {}

        val maintenance = cfg.optBoolean("maintenance_mode", false)
        val forceUpdate = cfg.optBoolean("force_update", false)
        val minVersion = cfg.optInt("min_supported_version", 1)

        splashOverlay.visibility = View.GONE

        when {
            maintenance -> {
                findViewById<TextView>(R.id.maintenanceMessage).text =
                    cfg.optString("maintenance_message", "আমরা শীঘ্রই ফিরছি।")
                maintenanceOverlay.visibility = View.VISIBLE
            }
            forceUpdate && appVersionCode < minVersion -> {
                findViewById<TextView>(R.id.forceUpdateMessage).text =
                    cfg.optString("force_update_message", "নতুন ভার্সন উপলব্ধ।")
                val playStoreUrl = cfg.optString("play_store_url", "")
                findViewById<Button>(R.id.updateNowButton).setOnClickListener {
                    if (playStoreUrl.isNotBlank()) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(playStoreUrl)))
                    }
                }
                forceUpdateOverlay.visibility = View.VISIBLE
            }
            else -> {
                setupWebView(cfg)
                setupFloatingButtons(cfg)
                registerDevice(cfg)
                startHeartbeatLoop()
                startPopupPolling()
                applyDarkModeIfEnabled(cfg)
                setupGeofenceIfEnabled(cfg)
                checkForInAppUpdate()
                mainHandler.postDelayed({ maybeShowRatingPopup(cfg) }, 4000)
                mainHandler.postDelayed({ startSmartLoginFlow() }, 2500)
            }
        }
    }

    private fun showOffline(message: String) {
        splashOverlay.visibility = View.GONE
        val noNetwork = !hasActiveNetwork()
        findViewById<TextView>(R.id.offlineTitle).text =
            if (noNetwork) "সংযোগ পাওয়া যাচ্ছে না" else "লোড করা যাচ্ছে না"
        findViewById<TextView>(R.id.offlineMessage).text = message
        // If this device has a browser HTTP cache from a previous visit,
        // let them know we'll use it the moment they're back online —
        // reassures them nothing is broken.
        findViewById<TextView>(R.id.offlineCacheHint).visibility =
            if (noNetwork && hasEverLoadedSuccessfully) View.VISIBLE else View.GONE
        offlineOverlay.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------
    // Dark Mode — follows the phone's system setting automatically,
    // no in-app toggle/button. Uses WebView's algorithmic darkening
    // so it applies even to a site that has no dark CSS of its own.
    // ---------------------------------------------------------
    private fun applyDarkModeIfEnabled(cfg: JSONObject) {
        if (!cfg.optBoolean("dark_mode_auto", true)) return
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isSystemDark)
        }
    }

    // ---------------------------------------------------------
    // Location-based alert (geofence around the shop)
    // ---------------------------------------------------------
    private fun setupGeofenceIfEnabled(cfg: JSONObject) {
        if (!cfg.optBoolean("geofence_enabled", false)) return
        val lat = cfg.optString("geofence_lat", "").toDoubleOrNull() ?: return
        val lng = cfg.optString("geofence_lng", "").toDoubleOrNull() ?: return
        val radius = cfg.optInt("geofence_radius_m", 500).toFloat()

        prefs.edit().putString("geofence_message", cfg.optString("geofence_message", "")).apply()

        val hasPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1002)
            return // will register the geofence next time config is applied, after permission is granted
        }

        val geofence = Geofence.Builder()
            .setRequestId("rina_shop_geofence")
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, 3001, Intent(this, GeofenceBroadcastReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(this)
        try {
            geofencingClient.addGeofences(request, pendingIntent)
        } catch (_: SecurityException) {
            // permission revoked between check and call — safe to ignore
        }
    }

    // ---------------------------------------------------------
    // Seamless background update — ONLY works when the app was
    // installed from Google Play (Play Core requires that). For a
    // sideloaded APK this silently does nothing, which is correct.
    // ---------------------------------------------------------
    private fun checkForInAppUpdate() {
        try {
            val appUpdateManager = AppUpdateManagerFactory.create(this)
            appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    appUpdateManager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, this, 5001)
                }
            }
        } catch (_: Exception) {
            // Play Core not available (e.g. sideloaded build) — expected, ignore.
        }
    }

    // ---------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------
    private var pageLoadTimeoutRunnable: Runnable? = null
    private val pageLoadTimeoutMs = 20_000L

    private fun setupWebView(cfg: JSONObject) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.textZoom = cfg.optInt("zoom_level", 100)

        // ---------------------------------------------------------
        // Cookies / session persistence.
        // By default Android WebView accepts first-party cookies
        // already, but third-party cookies (e.g. a session/affiliate
        // cookie set from an embedded widget, tracking pixel, or a
        // sub-domain resource loaded inside a page) are BLOCKED
        // unless explicitly allowed — this is the most common cause
        // of "logged out when moving between pages" in a WebView
        // app. We also force an explicit flush to disk on every
        // page load so a session survives the app being killed by
        // Android in the background, not just app-open navigation.
        // ---------------------------------------------------------
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // ---------------------------------------------------------
        // Advanced caching:
        //  - Online: LOAD_DEFAULT — WebView serves from its local
        //    HTTP cache first for speed, and transparently validates
        //    each resource against the server per normal HTTP cache
        //    rules (ETag/Last-Modified), so changes on the live site
        //    still show up automatically. This is what makes repeat
        //    loads fast without ever showing stale content silently.
        //  - Offline: LOAD_CACHE_ONLY — never even attempts the
        //    network (which would just fail slowly), instantly shows
        //    whatever was cached from the last visit instead.
        //  - Remote purge: if the Admin Panel's cache_bust_token for
        //    this device changed since we last checked (either a
        //    global refresh or a reset aimed at this specific
        //    device), we drop the WebView's cache once so the next
        //    load re-downloads everything fresh. This never touches
        //    cookies/localStorage, so login sessions and the browser
        //    authorization system are completely unaffected.
        // ---------------------------------------------------------
        settings.cacheMode = if (hasActiveNetwork())
            android.webkit.WebSettings.LOAD_DEFAULT
        else
            android.webkit.WebSettings.LOAD_CACHE_ONLY

        val serverCacheToken = cfg.optString("cache_bust_token", "")
        val appliedCacheToken = prefs.getString("applied_cache_token", "")
        if (serverCacheToken.isNotBlank() && serverCacheToken != appliedCacheToken) {
            webView.clearCache(true)
            prefs.edit().putString("applied_cache_token", serverCacheToken).apply()
            Log.i("RinaApp", "Cache token changed by admin — WebView cache cleared")
        }

        // Avoid a harsh black flash while the page is still loading
        webView.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.background))

        swipeRefresh.isEnabled = cfg.optBoolean("pull_to_refresh", true)
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        swipeBackEnabled = cfg.optBoolean("swipe_back", true)
        webView.setOnTouchListener { _, event ->
            if (swipeBackEnabled) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> lastTouchDownX = event.x
                    MotionEvent.ACTION_UP -> {
                        val deltaX = event.x - lastTouchDownX
                        if (lastTouchDownX < 60 && deltaX > 120 && webView.canGoBack()) {
                            webView.goBack()
                        }
                    }
                }
            }
            false
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress in 1..99) {
                    pageProgressBar.visibility = View.VISIBLE
                    pageProgressBar.progress = newProgress
                } else {
                    pageProgressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // A previous chooser that never returned a result (e.g. the
                // user backed out without picking anything twice in a row)
                // must be released, or WebView leaks/hangs the JS promise.
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() && it != "*/*" }
                    ?: emptyList()

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
                    if (acceptTypes.isNotEmpty()) {
                        putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                    }
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }

                return try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "ছবি বা ফাইল বেছে নিন"))
                    true
                } catch (e: Exception) {
                    Log.e("RinaApp", "Could not open file chooser", e)
                    fileChooserCallback = null
                    filePathCallback?.onReceiveValue(null)
                    false
                }
            }
        }

        val offlineMsg = cfg.optString("offline_message", "সাইট লোড হতে সমস্যা হচ্ছে। আবার চেষ্টা করুন।")

        webView.webViewClient = object : WebViewClient() {
            /**
             * Hands off any link that isn't a normal http/https webpage
             * to the appropriate installed app instead of letting
             * WebView try (and fail) to "load" it as a page.
             *
             * This is exactly what was missing for Share-to-WhatsApp /
             * Facebook / TikTok buttons on the website: those buttons
             * use links like `whatsapp://send?...`, `fb://...`,
             * `intent://...#Intent;...;end`, `tg://`, `tel:`, `mailto:`
             * etc. — none of those are real webpages, so without this,
             * WebView just silently does nothing when tapped (which is
             * exactly the symptom reported). Ordinary http(s) links
             * (all normal site browsing) are completely untouched and
             * behave exactly as before.
             */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false

                if (scheme == "http" || scheme == "https") {
                    return false // normal webpage — let WebView load it as always
                }

                if (scheme == "intent") {
                    return try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        try {
                            startActivity(intent)
                        } catch (notFound: ActivityNotFoundException) {
                            // App not installed — intent:// links from JS share
                            // buttons usually carry a web fallback for this case.
                            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                            if (!fallbackUrl.isNullOrBlank()) {
                                view?.loadUrl(fallbackUrl)
                            } else {
                                Toast.makeText(this@MainActivity, "প্রয়োজনীয় অ্যাপটি এই ডিভাইসে ইনস্টল করা নেই।", Toast.LENGTH_SHORT).show()
                            }
                        }
                        true
                    } catch (e: Exception) {
                        Log.w("RinaApp", "Could not resolve intent:// url: $uri", e)
                        true
                    }
                }

                // Any other app deep link: whatsapp://, fb://, fb-messenger://,
                // tiktok/snssdk..., tg://, tel:, mailto:, sms:, market:// etc.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "প্রয়োজনীয় অ্যাপটি এই ডিভাইসে ইনস্টল করা নেই।", Toast.LENGTH_SHORT).show()
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                cancelPageLoadTimeout()
                pageLoadTimeoutRunnable = Runnable {
                    handleMainFrameFailure(webView, "সাইট লোড হতে অনেক সময় নিচ্ছে। ইন্টারনেট সংযোগ ধীর হতে পারে, অথবা হোস্টিং সার্ভার ব্যস্ত আছে।")
                }
                mainHandler.postDelayed(pageLoadTimeoutRunnable!!, pageLoadTimeoutMs)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cancelPageLoadTimeout()
                swipeRefresh.isRefreshing = false
                pageProgressBar.visibility = View.GONE
                if (url != null && url != "about:blank") {
                    offlineOverlay.visibility = View.GONE
                    hasEverLoadedSuccessfully = true
                    // Persist cookies (login/session) to disk now, so a
                    // session survives the app being closed/killed, not
                    // just in-app navigation.
                    CookieManager.getInstance().flush()
                    // A real page loaded successfully — if we'd switched to
                    // cache-only while offline, and connectivity has since
                    // returned, switch back to normal mode for next time.
                    if (hasActiveNetwork()) {
                        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    }
                    sendHeartbeat(url, view?.title ?: "")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    handleMainFrameFailure(view, offlineMsg)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 0
                    handleMainFrameFailure(view, "সার্ভারে সাময়িক সমস্যা হচ্ছে (কোড $code)। একটু পর আবার চেষ্টা করুন।")
                }
            }
        }

        val notificationClickUrl = intent.getStringExtra("open_url")
        val shortcutPath = intent.getStringExtra("shortcut_path")
        val baseSiteUrl = cfg.optString("active_url", "https://rinagift.shop").trimEnd('/')
        val activeUrl = when {
            !notificationClickUrl.isNullOrBlank() -> notificationClickUrl
            !shortcutPath.isNullOrBlank() -> baseSiteUrl + shortcutPath
            else -> baseSiteUrl
        }
        val separator = if (activeUrl.contains("?")) "&" else "?"
        webView.loadUrl("$activeUrl${separator}rina_device_id=$deviceId")
    }

    private fun cancelPageLoadTimeout() {
        pageLoadTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    /**
     * Central handler for any main-frame load failure (no connection,
     * timeout, DNS failure, HTTP 4xx/5xx from the server, etc).
     *
     * Important: we explicitly stop the load and clear to about:blank
     * first. Without this, Android WebView can render its own default
     * "Webpage not available / net::ERR_..." browser-style error page
     * underneath our overlay — which is exactly the raw, unbranded
     * error screen we want to replace. Clearing it guarantees only
     * our custom offline page is ever visible, never a blank/black
     * screen and never the native Chromium error page.
     */
    private fun handleMainFrameFailure(view: WebView?, message: String) {
        cancelPageLoadTimeout()
        swipeRefresh.isRefreshing = false
        pageProgressBar.visibility = View.GONE
        view?.stopLoading()
        view?.loadUrl("about:blank")
        showOffline(message)
    }

    override fun onBackPressed() {
        if (offlineOverlay.visibility != View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------
    // Floating buttons (WhatsApp / Call / Custom)
    // ---------------------------------------------------------
    private fun setupFloatingButtons(cfg: JSONObject) {
        floatingButtonsContainer.removeAllViews()
        val buttons = cfg.optJSONArray("floating_buttons") ?: JSONArray()
        for (i in 0 until buttons.length()) {
            val b = buttons.getJSONObject(i)
            val type = b.optString("type", "custom")
            val value = b.optString("value", "")
            val icon = b.optString("icon_emoji", "💬")

            val btn = TextView(this).apply {
                text = icon
                textSize = 22f
                setPadding(28, 20, 28, 20)
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.primary))
                setTextColor(Color.WHITE)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 12
                layoutParams = lp
            }
            btn.setOnClickListener {
                when (type) {
                    "whatsapp" -> {
                        val digits = value.filter { it.isDigit() }
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
                    }
                    "call" -> {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$value")))
                    }
                    else -> {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                    }
                }
            }
            floatingButtonsContainer.addView(btn)
        }
    }

    // ---------------------------------------------------------
    // Device registration + heartbeat + popups (all fire-and-forget on background threads)
    // ---------------------------------------------------------
    private fun registerDevice(cfg: JSONObject) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val fcmToken = if (task.isSuccessful) task.result else ""
                sendDeviceRegistration(fcmToken ?: "")
            }
        } catch (e: Exception) {
            // Firebase not configured yet (no google-services.json) — that's fine,
            // register the device without a push token; everything else still works.
            Log.w("RinaApp", "Firebase not configured, registering without push token", e)
            sendDeviceRegistration("")
        }
    }

    private fun sendDeviceRegistration(fcmToken: String) {
        prefs.edit().putString("fcm_token", fcmToken).apply()
        val installSource = prefs.getString("install_referrer", null)?.takeIf { it.isNotBlank() } ?: "direct_apk"

        Thread {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("fcm_token", fcmToken)
                put("install_source", installSource)
                put("app_version", appVersionCode.toString())
                put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("os_version", "Android ${Build.VERSION.RELEASE}")
                put("network_type", getNetworkType())
            }
            httpPostJson("$adminBaseUrl/api/register_device.php", body)
        }.start()
    }

    private fun getNetworkType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile_data"
                else -> "other"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun startHeartbeatLoop() {
        val runnable = object : Runnable {
            override fun run() {
                sendHeartbeat(webView.url ?: "", webView.title ?: "")
                mainHandler.postDelayed(this, heartbeatIntervalMs)
            }
        }
        mainHandler.postDelayed(runnable, heartbeatIntervalMs)
    }

    private fun sendHeartbeat(pageUrl: String, pageTitle: String) {
        Thread {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("page_url", pageUrl)
                put("page_title", pageTitle)
            }
            httpPostJson("$adminBaseUrl/api/heartbeat.php", body)
        }.start()
    }

    private fun startPopupPolling() {
        val runnable = object : Runnable {
            override fun run() {
                pollPopups()
                mainHandler.postDelayed(this, popupPollIntervalMs)
            }
        }
        mainHandler.postDelayed(runnable, 5000) // first check shortly after load, then repeats every popupPollIntervalMs
    }

    private fun pollPopups() {
        Thread {
            val result = httpGet("$adminBaseUrl/api/popups.php?device_id=$deviceId")
            if (result != null) {
                try {
                    val json = JSONObject(result)
                    val popups = json.optJSONArray("popups") ?: JSONArray()
                    if (popups.length() > 0) {
                        val first = popups.getJSONObject(0)
                        mainHandler.post { showPopup(first) }
                    }
                } catch (e: Exception) {
                    Log.e("RinaApp", "popup parse error", e)
                }
            }
        }.start()
    }

    /**
     * Advanced, branded popup card for in-app notifications (sent from
     * Admin Panel -> Notifications, with "Show as popup" enabled) —
     * replaces the old plain system AlertDialog with a custom image
     * banner, rounded card, and theme-colored action button.
     */
    private fun showPopup(popup: JSONObject) {
        if (isFinishing) return
        val notifId = popup.optInt("id")
        val title = popup.optString("title")
        val body = popup.optString("body")
        val clickUrl = popup.optString("click_url", "")
        val imageUrl = popup.optString("image_url", "")

        trackNotificationEvent(notifId, "view")

        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(R.layout.dialog_popup)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val card = dialog.findViewById<View>(R.id.popupCard)
        dialog.findViewById<TextView>(R.id.popupTitle).text = title
        val bodyView = dialog.findViewById<TextView>(R.id.popupBody)
        if (body.isBlank()) {
            bodyView.visibility = View.GONE
        } else {
            bodyView.text = body
        }

        val viewButton = dialog.findViewById<TextView>(R.id.popupViewButton)
        if (clickUrl.isBlank()) {
            viewButton.text = "ঠিক আছে"
        }
        viewButton.setOnClickListener {
            trackNotificationEvent(notifId, "click")
            if (clickUrl.isNotBlank()) {
                webView.loadUrl(clickUrl)
            }
            dialog.dismiss()
        }
        dialog.findViewById<TextView>(R.id.popupDismissButton).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<ImageButton>(R.id.popupCloseButton).setOnClickListener { dialog.dismiss() }

        if (imageUrl.isNotBlank()) {
            val imageView = dialog.findViewById<ImageView>(R.id.popupImage)
            val emojiView = dialog.findViewById<TextView>(R.id.popupImageEmoji)
            loadBitmapAsync(imageUrl) { bitmap ->
                if (bitmap != null && !isFinishing && dialog.isShowing) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                    emojiView.visibility = View.GONE
                }
            }
        }

        dialog.show()
        // Small pop-in animation so it feels intentional and premium,
        // not like a generic system alert.
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** Tiny best-effort image downloader — no external image library needed. */
    private fun loadBitmapAsync(url: String, callback: (Bitmap?) -> Unit) {
        Thread {
            val bitmap = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w("RinaApp", "Popup image load failed: $url", e)
                null
            }
            mainHandler.post { callback(bitmap) }
        }.start()
    }

    private fun trackNotificationEvent(notificationId: Int, event: String) {
        Thread {
            val body = JSONObject().apply {
                put("notification_id", notificationId)
                put("device_id", deviceId)
                put("event", event)
            }
            httpPostJson("$adminBaseUrl/api/track.php", body)
        }.start()
    }

    // ---------------------------------------------------------
    // App rating popup
    // ---------------------------------------------------------
    private fun maybeShowRatingPopup(cfg: JSONObject) {
        if (isFinishing) return
        if (!cfg.optBoolean("rating_popup_enabled", false)) return
        if (prefs.getBoolean("rating_never", false)) return

        val delayDays = cfg.optInt("rating_popup_delay_days", 3)
        val installTime = prefs.getLong("install_time", System.currentTimeMillis())
        val nextPromptTime = prefs.getLong("rating_next_prompt", installTime + delayDays * 86_400_000L)

        if (System.currentTimeMillis() < nextPromptTime) return

        val playStoreUrl = cfg.optString("play_store_url", "")

        AlertDialog.Builder(this)
            .setTitle("আপনার মতামত জানান")
            .setMessage("অ্যাপটি কেমন লাগছে? Play Store-এ একটা রেটিং দিলে আমরা কৃতজ্ঞ থাকব।")
            .setPositiveButton("রেট করুন") { d, _ ->
                if (playStoreUrl.isNotBlank()) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(playStoreUrl)))
                }
                prefs.edit().putBoolean("rating_never", true).apply()
                d.dismiss()
            }
            .setNeutralButton("পরে জিজ্ঞেস করুন") { d, _ ->
                prefs.edit().putLong("rating_next_prompt", System.currentTimeMillis() + 7 * 86_400_000L).apply()
                d.dismiss()
            }
            .setNegativeButton("আর দেখাবেন না") { d, _ ->
                prefs.edit().putBoolean("rating_never", true).apply()
                d.dismiss()
            }
            .show()
    }

    // ---------------------------------------------------------
    // Smart Login — Phone Number Hint (1-tap, first time) then
    // biometric/PIN auto-login on every visit after. No button is
    // ever shown on the website itself; this is a native Android
    // prompt layered on top.
    // ---------------------------------------------------------
    private fun startSmartLoginFlow() {
        if (isFinishing) return

        if (SmartLogin.hasSavedLogin(this)) {
            unlockSmartLoginWithBiometric()
        } else if (SmartLogin.canAskAgain(this)) {
            requestPhoneNumberHint()
        }
    }

    private fun requestPhoneNumberHint() {
        try {
            val request = GetPhoneNumberHintIntentRequest.builder().build()
            Identity.getSignInClient(this)
                .getPhoneNumberHintIntent(request)
                .addOnSuccessListener { pendingIntent ->
                    try {
                        phoneHintLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } catch (e: Exception) {
                        Log.e("RinaApp", "Could not launch phone hint", e)
                    }
                }
                .addOnFailureListener {
                    // No Google account / Play Services unavailable — silently skip,
                    // customer just continues with the normal website flow.
                }
        } catch (_: Exception) {
        }
    }

    private fun performCustomerLogin(phone: String) {
        Thread {
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("phone", phone)
            }
            val result = httpPostJson("$adminBaseUrl/api/customer_login.php", body)
            if (result != null) {
                try {
                    val json = JSONObject(result)
                    if (json.optBoolean("success", false)) {
                        val token = json.getString("token")
                        SmartLogin.saveLogin(this, token, phone)
                        mainHandler.post {
                            injectAutofillFromProfile(json.optString("name", ""), json.optString("address", ""), phone)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RinaApp", "customer_login parse error", e)
                }
            }
        }.start()
    }

    private fun unlockSmartLoginWithBiometric() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric/PIN set up on this phone at all — just skip silently,
            // the token stays saved for when they do set one up.
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                fetchSavedProfileAndAutofill()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled or failed — customer just continues as a normal guest this time.
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle("দ্রুত লগইন করতে যাচাই করুন")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun fetchSavedProfileAndAutofill() {
        val token = SmartLogin.getToken(this) ?: return
        Thread {
            val result = httpGet("$adminBaseUrl/api/customer_profile.php?token=$token")
            if (result != null) {
                try {
                    val json = JSONObject(result)
                    if (json.optBoolean("success", false)) {
                        mainHandler.post {
                            injectAutofillFromProfile(
                                json.optString("name", ""),
                                json.optString("address", ""),
                                json.optString("phone", "")
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RinaApp", "customer_profile parse error", e)
                }
            }
        }.start()
    }

    /**
     * Best-effort autofill: tries common field name/id patterns used
     * on most checkout forms. Since every website is built
     * differently, this may need small adjustments once we know
     * rinagift.shop's actual checkout field names/ids for a perfect
     * match — this covers the most common cases out of the box.
     */
    private fun injectAutofillFromProfile(name: String, address: String, phone: String) {
        val js = """
            (function() {
                function fill(selectors, value) {
                    if (!value) return;
                    for (var i = 0; i < selectors.length; i++) {
                        var els = document.querySelectorAll(selectors[i]);
                        for (var j = 0; j < els.length; j++) {
                            if (!els[j].value) { els[j].value = value; }
                        }
                    }
                }
                fill(['input[name="name"]','input[name="full_name"]','input[id*="name" i]'], ${JSONObject.quote(name)});
                fill(['input[name="phone"]','input[type="tel"]','input[id*="phone" i]'], ${JSONObject.quote(phone)});
                fill(['textarea[name="address"]','input[name="address"]','input[id*="address" i]','textarea[id*="address" i]'], ${JSONObject.quote(address)});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------
    // Runtime permission results (location for geofence, etc.)
    // ---------------------------------------------------------
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            config?.let { setupGeofenceIfEnabled(it) }
        }
    }

    // ---------------------------------------------------------
    // Tiny HTTP helpers (no external libraries needed)
    // ---------------------------------------------------------
    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            Log.e("RinaApp", "GET failed: $urlStr", e)
            null
        }
    }

    private fun httpPostJson(urlStr: String, body: JSONObject): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            Log.e("RinaApp", "POST failed: $urlStr", e)
            null
        }
    }
}
