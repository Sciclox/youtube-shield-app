package com.example.youtubeshield

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var navBarCard: CardView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var splashOverlay: FrameLayout
    private lateinit var btnShield: ImageButton

    private var isShieldActive = true
    private val adBlockHandler = Handler(Looper.getMainLooper())

    // Variables para el control de pantalla completa
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility = 0
    private var originalOrientation = 0

    // Runnable para inyección periódica de scripts de bloqueo
    private val adBlockRunnable = object : Runnable {
        override fun run() {
            if (isShieldActive) {
                injectAdBlockScript()
            }
            adBlockHandler.postDelayed(this, 2000) // Re-inyecta cada 2 segundos
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

        // Iniciar la inyección de script periódica
        adBlockHandler.post(adBlockRunnable)
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
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Forzar tema oscuro en WebView si el celular lo soporta
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Algunas versiones de WebView permiten forzar el modo oscuro en el contenido
            // Típicamente YouTube móvil detecta el modo oscuro del sistema si el user-agent es compatible
        }

        // Establecer un User Agent de navegador móvil moderno para evitar bloqueos
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                // Mantener la navegación dentro de la app para YouTube
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    return false
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()
                // Si el escudo está activo, comprobar si es publicidad y bloquear
                if (isShieldActive && AdBlocker.isAd(url)) {
                    // Retorna una respuesta vacía
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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Ocultar el Splash screen una vez que carga la primera página
                splashOverlay.visibility = View.GONE
                if (isShieldActive) {
                    injectAdBlockScript()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    showCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }

        // Cargar YouTube inicialmente
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
        // Script CSS inyectable
        val cssRules = """
            #masthead-ad, 
            ytd-companion-ad-renderer, 
            ytd-display-ad-renderer, 
            .ad-container, 
            .ad-div, 
            .video-ads, 
            .ytp-ad-module, 
            .ytp-ad-overlay-container, 
            .ytp-ad-image-overlay, 
            .companion-ad-container, 
            ytd-promoted-sparkles-web-renderer, 
            ytd-display-ad-renderer,
            #player-ads { 
                display: none !important; 
            }
        """.trimIndent().replace("\n", " ")

        // Script JS inyectable
        val jsScript = """
            (function() {
                // 1. Inyectar CSS si no está ya presente
                if (!document.getElementById('shield-adblock-styles')) {
                    var style = document.createElement('style');
                    style.id = 'shield-adblock-styles';
                    style.type = 'text/css';
                    style.innerHTML = '$cssRules';
                    document.head.appendChild(style);
                }

                // 2. Intentar omitir o acelerar anuncios de video inmediatamente
                // Buscar botón de omitir anuncios de YouTube (móvil y escritorio)
                var skipButtons = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot, .ytp-ad-skip-button-container');
                skipButtons.forEach(function(btn) {
                    if (btn) {
                        btn.click();
                        console.log('Shield: Botón de omitir anuncio clickeado.');
                    }
                });

                // Si se está reproduciendo un anuncio (clases .ad-showing o .ad-interrupting)
                var video = document.querySelector('video');
                var isAdPlaying = document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay');
                if (video && isAdPlaying) {
                    video.muted = true;
                    video.playbackRate = 16.0; // Acelerar al máximo para terminarlo rápido
                    if (!isNaN(video.duration) && isFinite(video.duration)) {
                        video.currentTime = video.duration - 0.1; // Saltar al final directamente
                    }
                    console.log('Shield: Anuncio detectado y acelerado.');
                }
            })();
        """.trimIndent()

        // Ejecutar javascript en el WebView
        webView.evaluateJavascript(jsScript, null)
    }

    override fun onBackPressed() {
        // Permitir volver atrás en el historial del WebView con el botón físico de Android
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
        // Limpiar el handler para evitar fugas de memoria
        adBlockHandler.removeCallbacks(adBlockRunnable)
    }
}
