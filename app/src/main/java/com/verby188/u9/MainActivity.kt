package com.verby188.u9

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.play.core.review.ReviewManagerFactory
import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/** Interface exposée au JS pour déclencher la notation in-app. */
class AndroidBridge(private val activity: MainActivity) {
    @android.webkit.JavascriptInterface
    fun requestReview() {
        activity.runOnUiThread {
            activity.showInAppReview()
        }
    }

    @android.webkit.JavascriptInterface
    fun completeUpdate() {
        activity.runOnUiThread {
            activity.appUpdateManager.completeUpdate()
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var adView: AdView
    private var pendingCode: String? = null
    private var pendingNotifData: String? = null

    // ── Mises à jour in-app (flexible) ──
    val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private val UPDATE_REQUEST_CODE = 1234
    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            // Téléchargée : prévenir le JS pour afficher la bannière « Redémarrer »
            webView.post {
                webView.evaluateJavascript(
                    "if(typeof onUpdateReady==='function')onUpdateReady();", null
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        MobileAds.initialize(this)

        // Extraire le code d'invitation AVANT de charger la WebView
        val notifType = intent?.getStringExtra("type")
        if (notifType == "gameInvite") {
            val code = intent?.getStringExtra("code") ?: ""
            if (code.isNotEmpty()) pendingCode = code
        }

        // Permission notifications Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // Bannière adaptive
        adView = AdView(this).apply {
            adUnitId = "ca-app-pub-6145497382360748/9995318828"
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            val adWidth = (metrics.widthPixels / metrics.density).toInt()
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this@MainActivity, adWidth))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(webView)
        layout.addView(adView)
        setContentView(layout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(AndroidBridge(this), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return when {
                    url.startsWith("sms:") || url.startsWith("smsto:") -> {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    }
                    url.startsWith("tel:") -> {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                        true
                    }
                    url.startsWith("mailto:") -> {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    }
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                getFcmTokenAndInject()
                pendingNotifData?.let { data ->
                    injectNotification(data)
                    pendingNotifData = null
                }
            }
        }

        // Charger la page — le code sera passé via hash si disponible dès le départ
        val startUrl = if (pendingCode != null) {
            val code = pendingCode!!
            pendingCode = null
            "file:///android_asset/index.html#invite=$code"
        } else {
            "file:///android_asset/index.html"
        }
        webView.loadUrl(startUrl)
        adView.loadAd(AdRequest.Builder().build())

        handleIntent(intent)
        checkForUpdate()
    }

    private fun getFcmTokenAndInject() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                webView.post {
                    webView.evaluateJavascript(
                        "if(typeof onFcmToken==='function')onFcmToken('$token');", null
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val notifType = intent.getStringExtra("type")
        if (notifType != null) {
            val code = intent.getStringExtra("code") ?: ""
            val from = intent.getStringExtra("senderName") ?: intent.getStringExtra("from") ?: ""

            if (notifType == "gameInvite" && code.isNotEmpty()) {
                // App ouverte → injecter directement
                if (::webView.isInitialized && webView.url != null) {
                    injectCode(code)
                }
                // App fermée → pendingCode déjà utilisé dans loadUrl avant handleIntent
                return
            }

            val data = """{"type":"$notifType","code":"$code","senderName":"$from"}"""
            if (::webView.isInitialized && webView.url != null) {
                injectNotification(data)
            } else {
                pendingNotifData = data
            }
            return
        }

        // Deep link u9game://join/CODE
        val uri = intent.data ?: return
        val code = extractCode(uri) ?: return
        if (::webView.isInitialized && webView.url != null) {
            injectCode(code)
        } else {
            pendingCode = code
        }
    }

    private fun extractCode(uri: Uri): String? {
        if (uri.scheme == "u9game" && uri.host == "join") {
            val path = uri.pathSegments.firstOrNull()
            if (path?.length == 6) return path.uppercase()
        }
        if (uri.scheme == "https") {
            val code = uri.getQueryParameter("code")
            if (code?.length == 6) return code.uppercase()
        }
        return null
    }

    private fun injectCode(code: String) {
        webView.post {
            webView.evaluateJavascript("""
                (function(){
                    try{
                        if(typeof onDeepLinkCode==='function')onDeepLinkCode('$code');
                    }catch(e){}
                })();
            """.trimIndent(), null)
        }
    }

    private fun injectNotification(data: String) {
        webView.post {
            webView.evaluateJavascript(
                "if(typeof onFcmMessage==='function')onFcmMessage('${data.replace("'", "\\'")}');",
                null
            )
        }
    }

    fun onMessageReceived(data: Map<String, String>) {
        val json = data.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        injectNotification("{$json}")
    }

    // ── Mises à jour in-app ──
    private fun checkForUpdate() {
        appUpdateManager.registerListener(installStateListener)
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    this,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    UPDATE_REQUEST_CODE
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPDATE_REQUEST_CODE && resultCode != Activity.RESULT_OK) {
            // L'utilisateur a refusé/annulé — on réessaiera au prochain lancement
        }
    }

    // Notation in-app (Google Play In-App Review API)
    fun showInAppReview() {
        val reviewManager = ReviewManagerFactory.create(this)
        reviewManager.requestReviewFlow().addOnCompleteListener { request ->
            if (request.isSuccessful) {
                reviewManager.launchReviewFlow(this, request.result)
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume(); webView.onResume(); adView.resume()
        // Si une mise à jour a fini de télécharger en arrière-plan
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                webView.post {
                    webView.evaluateJavascript(
                        "if(typeof onUpdateReady==='function')onUpdateReady();", null
                    )
                }
            }
        }
    }
    override fun onPause() { super.onPause(); webView.onPause(); adView.pause() }
    override fun onDestroy() {
        super.onDestroy()
        appUpdateManager.unregisterListener(installStateListener)
        adView.destroy()
    }
}
