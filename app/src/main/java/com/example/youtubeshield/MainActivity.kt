package com.example.youtubeshield

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var navBarCard: CardView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var splashOverlay: FrameLayout
    private lateinit var btnShield: ImageButton

    private var isShieldActive = true
    private var isLoopEnabled = false
    private val adBlockHandler = Handler(Looper.getMainLooper())

    // Variables para el control de pantalla completa
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility = 0
    private var originalOrientation = 0

    // Servicio en segundo plano
    private var playbackService: MediaPlaybackService? = null
    private var isBound = false
    private val NOTIFICATION_PERMISSION_CODE = 1002
    private var lastVideoId: String? = null

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as MediaPlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            setupServiceCallback()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    // Runnable para inyección periódica de scripts de bloqueo
    private val adBlockRunnable = object : Runnable {
        override fun run() {
            if (isShieldActive) {
                injectAdBlockScript()
            }
            adBlockHandler.postDelayed(this, 2000) // Re-inyecta cada 2 segundos
        }
    }

    // Runnable para monitorear el estado del video de YouTube y actualizar la notificación
    private val playbackMonitorRunnable = object : Runnable {
        override fun run() {
            if (isBound && playbackService != null) {
                queryVideoState()
            }
            adBlockHandler.postDelayed(this, 1000) // Verifica cada 1 segundo
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar base de datos de AdBlocker
        AdBlocker.init(this)

        // Cargar preferencia del escudo
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        isShieldActive = prefs.getBoolean("shield_active", true)

        // Vincular vistas
        webView = findViewById(R.id.webView)
        navBarCard = findViewById(R.id.navBarCard)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        splashOverlay = findViewById(R.id.splashOverlay)
        btnShield = findViewById(R.id.btnShield)

        setupNavigationButtons()
        setupWebView()
        updateShieldUI()

        // Solicitar permisos de notificación en Android 13+
        checkNotificationPermission()

        // Iniciar y enlazar el servicio en segundo plano
        val intent = Intent(this, MediaPlaybackService::class.java)
        try {
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // Manejar excepciones de inicio de servicio en background
        }

        // Iniciar los runnables periódicos
        adBlockHandler.post(adBlockRunnable)
        adBlockHandler.post(playbackMonitorRunnable)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun setupServiceCallback() {
        playbackService?.setPlaybackCallback(object : MediaPlaybackService.PlaybackCallback {
            override fun onPlay() {
                runOnUiThread {
                    webView.evaluateJavascript("document.querySelector('video').play()", null)
                }
            }

            override fun onPause() {
                runOnUiThread {
                    // Desactivar temporalmente la protección de pausa para procesar el clic manual del widget
                    webView.evaluateJavascript(
                        "(function() { window.shieldIgnorePause = false; var v = document.querySelector('video'); if(v) v.pause(); window.shieldIgnorePause = true; })()",
                        null
                    )
                }
            }

            override fun onNext() {
                runOnUiThread {
                    // Botones inversos: Siguiente en el widget va al video anterior (historial)
                    webView.evaluateJavascript("window.history.back()", null)
                }
            }

            override fun onPrevious() {
                runOnUiThread {
                    // Botones inversos: Atrás en el widget va al video siguiente (autoplay/click)
                    val js = """
                        (function() {
                            var btn = document.querySelector('.ytp-next-button, .next-button, .ytm-next-button, [class*="next-button"]');
                            if (btn) {
                                btn.click();
                                return;
                            }
                            var firstRecom = document.querySelector('ytm-compact-video-renderer a, ytm-video-with-context-renderer a, .compact-media-item-image, a[href*="/watch"]');
                            if (firstRecom) {
                                firstRecom.click();
                            }
                        })()
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
            }

            override fun onToggleLoop() {
                runOnUiThread {
                    isLoopEnabled = !isLoopEnabled
                    val js = """
                        (function() {
                            window.shieldLoopEnabled = $isLoopEnabled;
                            var v = document.querySelector('video');
                            if (v) {
                                v.loop = $isLoopEnabled;
                            }
                            return $isLoopEnabled;
                        })()
                    """.trimIndent()
                    webView.evaluateJavascript(js) { value ->
                        Toast.makeText(
                            this@MainActivity,
                            if (isLoopEnabled) "Repetir canción: ACTIVADO" else "Repetir canción: DESACTIVADO",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun queryVideoState() {
        val currentUrl = webView.url ?: ""
        if (currentUrl.contains("watch?v=")) {
            try {
                val uri = Uri.parse(currentUrl)
                val videoId = uri.getQueryParameter("v")
                if (videoId != null && videoId != lastVideoId) {
                    lastVideoId = videoId
                    runOnUiThread {
                        // Recargar el WebView para forzar la inyección limpia del bloqueador en el nuevo video
                        webView.reload()
                    }
                    return
                }
            } catch (e: Exception) {
                // Ignorar error al parsear URL
            }
        } else if (currentUrl.contains("/shorts/")) {
            try {
                val segments = Uri.parse(currentUrl).pathSegments
                val shortId = segments.lastOrNull()
                if (shortId != null && shortId != lastVideoId) {
                    lastVideoId = shortId
                    runOnUiThread {
                        webView.reload()
                    }
                    return
                }
            } catch (e: Exception) {
                // Ignorar
            }
        }

        val js = """
            (function() {
                var video = document.querySelector('video');
                if (!video) return JSON.stringify({ title: "YouTube Shield", isPlaying: false });
                
                var title = document.title;
                title = title.replace(/^\(\d+\)\s+/, '');
                if (title.endsWith(' - YouTube')) {
                    title = title.substring(0, title.length - 10);
                }
                
                return JSON.stringify({
                    title: title || "YouTube Video",
                    isPlaying: !video.paused && !video.ended
                });
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"null\"") {
                try {
                    var cleanResult = result
                    if (cleanResult.startsWith("\"") && cleanResult.endsWith("\"")) {
                        cleanResult = cleanResult.substring(1, cleanResult.length - 1)
                        cleanResult = cleanResult.replace("\\\"", "\"")
                        cleanResult = cleanResult.replace("\\\\", "\\")
                    }
                    
                    val json = org.json.JSONObject(cleanResult)
                    val title = json.optString("title", "YouTube Shield")
                    val isPlaying = json.optBoolean("isPlaying", false)
                    
                    playbackService?.updateMetadata(title, isPlaying, isLoopEnabled)
                } catch (e: Exception) {
                    // Ignorar errores de parsing
                }
            }
        }
    }

    private fun setupNavigationButtons() {
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnForward = findViewById<ImageButton>(R.id.btnForward)
        val btnHome = findViewById<ImageButton>(R.id.btnHome)
        val btnRefresh = findViewById<ImageButton>(R.id.btnRefresh)

        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                Toast.makeText(this, "No hay páginas anteriores", Toast.LENGTH_SHORT).show()
            }
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            } else {
                Toast.makeText(this, "No hay páginas siguientes", Toast.LENGTH_SHORT).show()
            }
        }

        btnHome.setOnClickListener {
            webView.loadUrl("https://m.youtube.com")
        }

        btnRefresh.setOnClickListener {
            webView.reload()
        }

        btnShield.setOnClickListener {
            isShieldActive = !isShieldActive
            
            // Guardar preferencia
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("shield_active", isShieldActive).apply()

            updateShieldUI()
            webView.reload()

            val message = if (isShieldActive) "Escudo Protector Activado (Anuncios bloqueados)" else "Escudo Desactivado (Se mostrarán anuncios)"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateShieldUI() {
        if (isShieldActive) {
            btnShield.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.shield_on))
        } else {
            btnShield.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.shield_off))
        }
    }

    private fun setupWebView() {
        // Limpiar caché de WebView al iniciar para evitar anuncios cacheados
        webView.clearCache(true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    return false
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()
                if (isShieldActive && AdBlocker.isAd(url)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream("".toByteArray())
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectVisibilityOverride()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                splashOverlay.visibility = View.GONE
                injectVisibilityOverride()
                if (isShieldActive) {
                    injectAdBlockScript()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 30) {
                    injectVisibilityOverride()
                    if (isShieldActive) {
                        injectAdBlockScript()
                    }
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    showCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        webView.loadUrl("https://m.youtube.com")
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            hideCustomView()
            return
        }

        customView = view
        originalSystemUiVisibility = window.decorView.systemUiVisibility
        originalOrientation = requestedOrientation

        webView.visibility = View.GONE
        navBarCard.visibility = View.GONE

        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.addView(customView)
        customViewCallback = callback

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun hideCustomView() {
        if (customView == null) return

        fullscreenContainer.removeView(customView)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()

        webView.visibility = View.VISIBLE
        navBarCard.visibility = View.VISIBLE

        requestedOrientation = originalOrientation
        window.decorView.systemUiVisibility = originalSystemUiVisibility
    }

    private fun injectAdBlockScript() {
        val cssRules = """
            #masthead-ad, 
            ytd-companion-ad-renderer, 
            ytm-companion-ad-renderer, 
            ytd-display-ad-renderer, 
            ytm-display-ad-renderer, 
            ytm-promoted-item, 
            ytm-banner-ad-renderer, 
            ytm-inline-ad-renderer, 
            ytm-carousel-ad-renderer, 
            ytm-statement-banner-ad-renderer, 
            ytm-interactive-tabbed-header-ad-renderer, 
            .ad-container, 
            .ad-div, 
            .video-ads, 
            .ytp-ad-module, 
            .ytp-ad-overlay-container, 
            .ytp-ad-image-overlay, 
            .companion-ad-container, 
            ytd-promoted-sparkles-web-renderer, 
            ytm-promoted-sparkles-web-renderer, 
            .ad-showing,
            .ad-interrupting,
            .ytp-ad-player-overlay,
            .ytp-ad-player-overlay-layout,
            #player-ads { 
                display: none !important; 
                height: 0 !important;
                width: 0 !important;
                visibility: hidden !important;
            }
        """.trimIndent().replace("\n", " ")

        val jsScript = """
            (function() {
                // 1. Inyectar estilos CSS
                if (!document.getElementById('shield-adblock-styles')) {
                    var style = document.createElement('style');
                    style.id = 'shield-adblock-styles';
                    style.type = 'text/css';
                    style.innerHTML = '$cssRules';
                    document.head.appendChild(style);
                }

                // 2. Función omitidora principal y auto-desmuteado
                var skipAds = function() {
                    var skipButtons = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot, .ytp-ad-skip-button-container, [class*="skip-button"]');
                    skipButtons.forEach(function(btn) {
                        if (btn) {
                            btn.click();
                            console.log('Shield: Botón omitir clickeado.');
                        }
                    });

                    var video = document.querySelector('video');
                    var isAdPlaying = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-player-overlay-layout');
                    if (video) {
                        if (isAdPlaying) {
                            video.muted = true;
                            video.playbackRate = 16.0;
                            if (!isNaN(video.duration) && isFinite(video.duration)) {
                                video.currentTime = video.duration - 0.1;
                            }
                            console.log('Shield: Anuncio omitido / acelerado.');
                        } else {
                            // Video normal: Asegurar volumen activado y loop si corresponde
                            if (video.muted) {
                                video.muted = false;
                                video.volume = 1.0;
                                console.log('Shield: Video auto-desmuteado.');
                            }
                            if (window.shieldLoopEnabled && !video.loop) {
                                video.loop = true;
                            }
                        }
                    }

                    // Forzar click en el botón de desmutear/activar sonido de YouTube si está silenciado
                    if (!isAdPlaying) {
                        var unmuteBtns = document.querySelectorAll('.ytp-unmute, .ytp-unmute-box, .ytm-mute-button, [class*="unmute"], [aria-label*="unmute"], [aria-label*="Unmute"]');
                        unmuteBtns.forEach(function(btn) {
                            if (btn) {
                                var label = (btn.getAttribute('aria-label') || btn.innerText || btn.className || "").toLowerCase();
                                if (label.includes('unmute')) {
                                    btn.click();
                                    console.log('Shield: Click en botón de desmutear de YouTube.');
                                }
                            }
                        });
                    }
                };

                // Ejecutar inmediatamente
                skipAds();

                // 3. MutationObserver para interceptación en microsegundos
                if (!window.shieldObserver) {
                    window.shieldObserver = new MutationObserver(function(mutations) {
                        skipAds();
                    });
                    window.shieldObserver.observe(document.body || document.documentElement, {
                        childList: true,
                        subtree: true
                    });
                    console.log('Shield: MutationObserver activo.');
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsScript, null)
        injectVisibilityOverride()
    }

    private fun injectVisibilityOverride() {
        val js = """
            (function() {
                // Sincronizar estado de bucle desde Android Kotlin
                window.shieldLoopEnabled = $isLoopEnabled;

                // 1. Bloquear Service Workers
                if (typeof navigator.serviceWorker !== 'undefined') {
                    try {
                        Object.defineProperty(navigator, 'serviceWorker', {
                            get: function() { return undefined; },
                            configurable: true
                        });
                        console.log('Shield: ServiceWorker blocked.');
                    } catch (e) {
                        console.error('Shield: Failed to block ServiceWorker', e);
                    }
                }

                // 2. Mock de visibilidad
                if (!window.shieldVisibilityOverridden) {
                    try {
                        Object.defineProperty(document, 'visibilityState', {
                            get: function() { return 'visible'; },
                            configurable: true
                        });
                        Object.defineProperty(document, 'hidden', {
                            get: function() { return false; },
                            configurable: true
                        });
                        var blockVisibilityEvent = function(e) {
                            e.stopImmediatePropagation();
                        };
                        window.addEventListener('visibilitychange', blockVisibilityEvent, true);
                        document.addEventListener('visibilitychange', blockVisibilityEvent, true);
                        
                        // Evitar detención por foco
                        window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);
                        
                        window.shieldVisibilityOverridden = true;
                        console.log('Shield: Visibility API overridden successfully.');
                    } catch (e) {
                        console.error('Shield: visibility override failed', e);
                    }
                }

                // 3. Evitar que YouTube pause el video por el sistema
                if (!window.shieldPauseOverridden) {
                    try {
                        const originalPause = HTMLVideoElement.prototype.pause;
                        HTMLVideoElement.prototype.pause = function() {
                            if (window.shieldIgnorePause) {
                                console.log('Shield: Pause call ignored in background.');
                                return;
                            }
                            return originalPause.apply(this, arguments);
                        };
                        window.shieldPauseOverridden = true;
                    } catch (e) {
                        console.error('Shield: Failed to override pause', e);
                    }
                }

                // 4. Overrides para repetición de canción (loop)
                if (!window.shieldLoopOverridden) {
                    try {
                        const originalLoopGet = Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype, 'loop').get;
                        const originalLoopSet = Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype, 'loop').set;
                        Object.defineProperty(HTMLVideoElement.prototype, 'loop', {
                            get: function() {
                                if (window.shieldLoopEnabled) return true;
                                return originalLoopGet.call(this);
                            },
                            set: function(val) {
                                if (window.shieldLoopEnabled) {
                                    return originalLoopSet.call(this, true);
                                }
                                return originalLoopSet.call(this, val);
                            },
                            configurable: true
                        });
                        window.shieldLoopOverridden = true;
                        console.log('Shield: Loop property overridden.');
                    } catch(e) {
                        console.error('Shield: Failed to override loop property', e);
                    }
                }

                // 5. Interceptar evento de finalización en fase de captura para reiniciar el video
                if (!window.shieldEndedListenerRegistered) {
                    window.addEventListener('ended', function(e) {
                        if (window.shieldLoopEnabled && e.target && e.target.tagName === 'VIDEO') {
                            e.stopImmediatePropagation();
                            e.preventDefault();
                            var video = e.target;
                            video.currentTime = 0;
                            video.play().catch(function(err) {
                                console.error('Shield loop play error', err);
                            });
                            console.log('Shield: Intercepted ended event and looped!');
                        }
                    }, true);
                    window.shieldEndedListenerRegistered = true;
                    console.log('Shield: Capture ended event listener registered.');
                }

                // 6. Desmutear automáticamente lo antes posible
                var video = document.querySelector('video');
                if (video && video.muted) {
                    video.muted = false;
                    video.volume = 1.0;
                    console.log('Shield: Early video auto-unmuted.');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("window.shieldIgnorePause = false;", null)
    }

    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript("window.shieldIgnorePause = true;", null)
    }

    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adBlockHandler.removeCallbacks(adBlockRunnable)
        adBlockHandler.removeCallbacks(playbackMonitorRunnable)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
