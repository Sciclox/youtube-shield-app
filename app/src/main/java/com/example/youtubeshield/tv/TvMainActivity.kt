package com.example.youtubeshield.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.youtubeshield.AdBlocker
import com.example.youtubeshield.BackgroundWebView
import com.example.youtubeshield.R
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Actividad dedicada para la experiencia 10-Foot de Android TV / Google TV / Fire TV.
 * Carga YouTube TV (Leanback) con:
 * 1. Control total por D-pad y protección de salida.
 * 2. Bloqueo de anuncios multinivel de última generación:
 *    - Inyección DocumentStart pre-boot (antes de cualquier script de YouTube)
 *    - Intercepción y saneamiento profundo de APIs de reproductor (Fetch, XHR, JSON.parse, ytInitialPlayerResponse)
 *    - Limpieza de adPlacements, adSlots, playerAds, tracking y masthead en feed
 *    - Acelerador y omitidor dinámico a 16x de respuesta en tiempo real (<100ms)
 *    - Supresión de avisos anti-adblock
 * 3. Integración nativa de SponsorBlock para saltar intros, patrocinios y auto-promoción.
 * 4. Selector/Rotador de User-Agents de Smart TV certificados.
 * 5. Aceleración por hardware completa a 60fps.
 */
class TvMainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TvMainActivity"
        private const val YOUTUBE_TV_URL = "https://www.youtube.com/tv"
    }

    private lateinit var webView: BackgroundWebView
    private lateinit var splashOverlay: FrameLayout
    private lateinit var hudCard: CardView
    private lateinit var hudIcon: ImageView
    private lateinit var hudText: TextView
    private lateinit var hudSubtext: TextView
    private lateinit var errorContainer: FrameLayout
    private lateinit var btnRetry: Button

    private var isShieldActive = true
    private var isSponsorBlockActive = true
    private var backPressedOnce = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideHudRunnable = Runnable { hideHud() }
    private val resetBackRunnable = Runnable { backPressedOnce = false }
    private val hdThumbFailCache = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantener la pantalla encendida para evitar que el salvapantallas de Android TV corte el video
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_tv)

        // Inicializar motor de filtrado de red de forma síncrona inmediata antes de cualquier petición web
        AdBlocker.init(this)

        // Vincular componentes de la interfaz
        webView = findViewById(R.id.tvWebView)
        splashOverlay = findViewById(R.id.tvSplashOverlay)
        hudCard = findViewById(R.id.tvHudCard)
        hudIcon = findViewById(R.id.tvHudIcon)
        hudText = findViewById(R.id.tvHudText)
        hudSubtext = findViewById(R.id.tvHudSubtext)
        errorContainer = findViewById(R.id.tvErrorContainer)
        btnRetry = findViewById(R.id.btnTvRetry)

        // Cargar preferencias persistentes
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        isShieldActive = prefs.getBoolean("shield_active_tv", true)
        isSponsorBlockActive = prefs.getBoolean("sponsor_block_tv", true)

        setupTvWebView()
        setupListeners()

        // Mostrar HUD informativo al iniciar
        showHud(4000)

        // Cargar YouTube TV
        webView.loadUrl(YOUTUBE_TV_URL)
    }

    private fun setupListeners() {
        btnRetry.setOnClickListener {
            errorContainer.visibility = View.GONE
            splashOverlay.visibility = View.VISIBLE
            webView.loadUrl(YOUTUBE_TV_URL)
        }
    }

    private fun setupTvWebView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Aceleración por hardware para fluidez a 60fps en 1080p y 4K
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Habilitar navegación espacial para mandos D-Pad
        try {
            val method = settings.javaClass.getMethod("setSpatialNavigationEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(settings, true)
        } catch (_: Exception) {}

        // Registrar puente nativo seguro para SponsorBlock (elude restricciones CSP)
        webView.addJavascriptInterface(TvSponsorBlockBridge(this), "AndroidSponsorBlock")

        // Aplicar User-Agent de Smart TV actual
        val currentProfile = TvUserAgentManager.getCurrentProfile(this)
        settings.userAgentString = currentProfile.userAgent

        // Inyección a nivel DocumentStart: se ejecuta antes de cualquier script en la página
        if (isShieldActive && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    getTvShieldInjectionScript(),
                    setOf("*")
                )
                Log.d(TAG, "DocumentStartJavaScript registrado con éxito.")
            } catch (e: Exception) {
                Log.e(TAG, "Error registrando DocumentStartJavaScript", e)
            }
        }

        // Habilitar cookies completas
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 70) {
                    hideSplashWithFade()
                }
            }

            // Elimina el poster/placeholder por defecto de Android (icono gris con botón de reproducción)
            override fun getDefaultVideoPoster(): Bitmap {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val host = request.url.host ?: ""

                // Manejo especial de Google Login: Cambiar a Desktop UA temporal para permitir login sin errores
                if (host.contains("accounts.google.com") || host.contains("google.com/accounts")) {
                    if (view?.settings?.userAgentString != TvUserAgentManager.UA_DESKTOP_AUTH) {
                        view?.settings?.userAgentString = TvUserAgentManager.UA_DESKTOP_AUTH
                    }
                    return false
                } else if (url.contains("youtube.com/tv")) {
                    val activeUa = TvUserAgentManager.getCurrentProfile(this@TvMainActivity).userAgent
                    if (view?.settings?.userAgentString != activeUa) {
                        view?.settings?.userAgentString = activeUa
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && url.contains("youtube.com/tv")) {
                    val activeUa = TvUserAgentManager.getCurrentProfile(this@TvMainActivity).userAgent
                    if (view?.settings?.userAgentString != activeUa) {
                        view?.settings?.userAgentString = activeUa
                    }
                }

                // Inyección temprana al comenzar a cargar la página
                if (isShieldActive) {
                    injectTvAdBlockScript()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideSplashWithFade()

                // Inyectar motor de bloqueo completo y SponsorBlock
                if (isShieldActive) {
                    injectTvAdBlockScript()
                }
                if (isSponsorBlockActive) {
                    injectSponsorBlockScript()
                }

                // Detección proactiva de pantalla "Navegador no compatible": si aparece, rotar UA automáticamente
                checkAndRecoverUnsupportedBrowser()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Reforzar escudos durante transiciones de navegación SPA
                if (isShieldActive) {
                    injectTvAdBlockScript()
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (!isShieldActive) return super.shouldInterceptRequest(view, request)
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                // Bloqueo estricto a nivel de red (DNS, endpoints de seguimiento y streams con marcas de publicidad)
                if (AdBlocker.isAd(url)) {
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }

                // Opción A: Entrega de miniaturas en HD real (hq720.jpg a 1280x720) en lugar de comprimidas (320x180)
                if (url.contains("i.ytimg.com/vi/") && (url.contains("hqdefault") || url.contains("mqdefault"))) {
                    val hdResponse = handleHdThumbnailIntercept(url)
                    if (hdResponse != null) {
                        return hdResponse
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        hideSplashWithFade()
                        errorContainer.visibility = View.VISIBLE
                        btnRetry.requestFocus()
                    }
                }
            }
        }
    }

    /**
     * Intercepta peticiones de miniaturas comprimidas (320x180) y entrega la versión HD real (1280x720)
     */
    private fun handleHdThumbnailIntercept(originalUrl: String): WebResourceResponse? {
        try {
            val regex = Regex("https?://i\\.ytimg\\.com/vi/([^/\\?]+)/(hqdefault|mqdefault)[^/\\?]*\\.jpg")
            val match = regex.find(originalUrl) ?: return null
            val videoId = match.groupValues[1]

            if (hdThumbFailCache.contains(videoId)) return null

            val hdUrl = "https://i.ytimg.com/vi/$videoId/hq720.jpg"
            val conn = (URL(hdUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (conn.responseCode == 200) {
                val headers = mutableMapOf<String, String>()
                headers["Cache-Control"] = "public, max-age=2592000"
                headers["Access-Control-Allow-Origin"] = "*"
                return WebResourceResponse(conn.contentType ?: "image/jpeg", null, 200, "OK", headers, conn.inputStream)
            } else {
                hdThumbFailCache.add(videoId)
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Script central de bloqueo multicapa para YouTube TV.
     * Este código se inyecta tanto en DocumentStart como en onPageStarted/onPageFinished.
     */
    private fun getTvShieldInjectionScript(): String {
        return """
            (function() {
                // 1. Desactivación de experimentos y banderas internas de anuncios en ytcfg
                function applyAdBlockFlags(obj) {
                    if (!obj || typeof obj !== 'object') return;
                    if (obj.EXPERIMENT_FLAGS) {
                        obj.EXPERIMENT_FLAGS.web_enable_ad_signals = false;
                        obj.EXPERIMENT_FLAGS.web_ad_signals = false;
                        obj.EXPERIMENT_FLAGS.web_enable_ad_placement_config = false;
                        obj.EXPERIMENT_FLAGS.web_enable_ad_slots = false;
                        obj.EXPERIMENT_FLAGS.web_enable_midrolls = false;
                        obj.EXPERIMENT_FLAGS.web_enable_pre_rolls = false;
                        obj.EXPERIMENT_FLAGS.external_play_ads = false;
                        obj.EXPERIMENT_FLAGS.ad_pod_disable_prefill = true;
                        obj.EXPERIMENT_FLAGS.html5_ad_timeout_ms = 0;
                        obj.EXPERIMENT_FLAGS.html5_skippable_ad_timeout_ms = 0;
                        obj.EXPERIMENT_FLAGS.web_player_enable_ad_signals = false;
                    }
                }

                if (window.ytcfg) {
                    if (window.ytcfg.set) {
                        var origSet = window.ytcfg.set;
                        window.ytcfg.set = function(payload) {
                            applyAdBlockFlags(payload);
                            return origSet.apply(this, arguments);
                        };
                    }
                    if (window.ytcfg.configs) {
                        window.ytcfg.configs.forEach(applyAdBlockFlags);
                    }
                } else {
                    var _ytcfg;
                    Object.defineProperty(window, 'ytcfg', {
                        get: function() { return _ytcfg; },
                        set: function(val) {
                            _ytcfg = val;
                            if (_ytcfg && _ytcfg.set) {
                                var orig = _ytcfg.set;
                                _ytcfg.set = function(payload) {
                                    applyAdBlockFlags(payload);
                                    return orig.apply(this, arguments);
                                };
                            }
                        },
                        configurable: true
                    });
                }

                // 2. Limpiador maestro de estructuras de publicidad de YouTube
                function cleanAdData(obj) {
                    if (!obj || typeof obj !== 'object') return;

                    // Si la respuesta viene envuelta en un objeto playerResponse
                    if (obj.playerResponse && typeof obj.playerResponse === 'object') {
                        cleanAdData(obj.playerResponse);
                    }

                    // Eliminar matrices de anuncios del reproductor
                    if (obj.adPlacements) obj.adPlacements = [];
                    if (obj.adSlots) obj.adSlots = [];
                    if (obj.playerAds) obj.playerAds = [];
                    if (obj.adBreakHeartbeatParams) delete obj.adBreakHeartbeatParams;
                    if (obj.adPlacementConfig) obj.adPlacementConfig = {};
                    if (obj.playerConfig && obj.playerConfig.adPlacementConfig) {
                        obj.playerConfig.adPlacementConfig = {};
                    }

                    // Eliminar telemetría de reporte de anuncios
                    if (obj.playbackTracking) {
                        delete obj.playbackTracking.videostatsPlaybackUrl;
                        delete obj.playbackTracking.videostatsDelayplayUrl;
                        delete obj.playbackTracking.videostatsWatchtimeUrl;
                        delete obj.playbackTracking.ptrackingUrl;
                        delete obj.playbackTracking.qoeUrl;
                        delete obj.playbackTracking.atrUrl;
                    }

                    // Limpieza recursiva de masthead y banners patrocinados en el feed
                    if (obj.contents) cleanSectionList(obj.contents);
                    if (obj.items) cleanSectionList(obj.items);
                }

                function cleanSectionList(contents) {
                    if (!Array.isArray(contents)) return;
                    for (var i = contents.length - 1; i >= 0; i--) {
                        var item = contents[i];
                        if (!item) continue;
                        if (item.mastheadAdRenderer || item.adSlotRenderer || item.adRenderer ||
                            item.adEngagementSlotRenderer || item.adPlacementRenderer ||
                            item.inFeedAdRenderer || item.promotedSparklesWebRenderer || item.invalidation) {
                            contents.splice(i, 1);
                        } else if (item.itemSectionRenderer && item.itemSectionRenderer.contents) {
                            cleanSectionList(item.itemSectionRenderer.contents);
                        }
                    }
                }

                // 3. Interceptar ytInitialPlayerResponse y ytInitialData antes de que el motor de la TV los lea
                try {
                    var _initPlayer = window.ytInitialPlayerResponse;
                    Object.defineProperty(window, 'ytInitialPlayerResponse', {
                        get: function() { return _initPlayer; },
                        set: function(val) { cleanAdData(val); _initPlayer = val; },
                        configurable: true
                    });
                    if (window.ytInitialPlayerResponse) cleanAdData(window.ytInitialPlayerResponse);

                    var _initData = window.ytInitialData;
                    Object.defineProperty(window, 'ytInitialData', {
                        get: function() { return _initData; },
                        set: function(val) { cleanAdData(val); _initData = val; },
                        configurable: true
                    });
                    if (window.ytInitialData) cleanAdData(window.ytInitialData);
                } catch(e) {}

                // 4. Interceptar JSON.parse universal
                if (!window.__shieldJsonOverridden) {
                    window.__shieldJsonOverridden = true;
                    var origParse = JSON.parse;
                    JSON.parse = function(text, reviver) {
                        var obj = origParse.call(this, text, reviver);
                        try {
                            cleanAdData(obj);
                        } catch(e) {}
                        return obj;
                    };
                }

                // 5. Interceptar window.fetch para /youtubei/v1/player, /browse y /next
                if (!window.__shieldFetchOverridden) {
                    window.__shieldFetchOverridden = true;
                    var origFetch = window.fetch;
                    window.fetch = async function(...args) {
                        var response = await origFetch.apply(this, args);
                        try {
                            var url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                            if (url.includes('youtubei/v1/player') || url.includes('/player') ||
                                url.includes('youtubei/v1/browse') || url.includes('/browse') ||
                                url.includes('youtubei/v1/next') || url.includes('/next')) {
                                var text = await response.text();
                                var json = origParse(text);
                                cleanAdData(json);
                                var newResp = new Response(JSON.stringify(json), {
                                    status: response.status,
                                    statusText: response.statusText,
                                    headers: response.headers
                                });
                                Object.defineProperty(newResp, 'url', { value: response.url, writable: false });
                                return newResp;
                            }
                        } catch(e) {}
                        return response;
                    };
                }

                // 6. Interceptar Response.prototype.json
                if (!window.__shieldRespJsonOverridden) {
                    window.__shieldRespJsonOverridden = true;
                    var origRespJson = Response.prototype.json;
                    Response.prototype.json = async function() {
                        var json = await origRespJson.apply(this, arguments);
                        try {
                            if (this.url && (this.url.includes('youtubei/v1/player') || this.url.includes('/player') ||
                                             this.url.includes('youtubei/v1/browse') || this.url.includes('youtubei/v1/next'))) {
                                cleanAdData(json);
                            }
                        } catch(e) {}
                        return json;
                    };
                }

                // 7. Interceptar XMLHttpRequest para peticiones SPA de TV
                if (!window.__shieldXhrOverridden) {
                    window.__shieldXhrOverridden = true;
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__shieldUrl = url;
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        var url = this.__shieldUrl || '';
                        if (typeof url === 'string' && (url.includes('youtubei/v1/player') || url.includes('/player') ||
                                                       url.includes('youtubei/v1/browse') || url.includes('/browse') ||
                                                       url.includes('youtubei/v1/next') || url.includes('/next'))) {
                            var self = this;
                            var origHandler = this.onreadystatechange;
                            this.onreadystatechange = function() {
                                if (self.readyState === 4) {
                                    try {
                                        if (self.responseType === '' || self.responseType === 'text') {
                                            var text = self.responseText;
                                            var json = origParse(text);
                                            cleanAdData(json);
                                            var modified = JSON.stringify(json);
                                            Object.defineProperty(self, 'responseText', { value: modified, configurable: true });
                                            Object.defineProperty(self, 'response', { value: modified, configurable: true });
                                        } else if (self.responseType === 'json' && typeof self.response === 'object') {
                                            cleanAdData(self.response);
                                        }
                                    } catch(e) {}
                                }
                                if (origHandler) origHandler.apply(this, arguments);
                            };
                        }
                        return origSend.apply(this, arguments);
                    };
                }

                // 8. Inyectar estilos CSS para ocultar contenedores de anuncios, placeholders y transparencias solicitadas
                var cssRules = '.ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-player-overlay, ' +
                    '.ad-showing, .ad-interrupting, #player-ads, .video-ads, ' +
                    '.badge-style-type-ad, ytd-banner-promo-renderer, ' +
                    '.ytp-ad-preview-container, .ytp-ad-skip-button-container, ' +
                    'ytd-enforcement-message-view-model, yt-playability-error-supported-renderers, ' +
                    '[class*="ad-container"], [class*="sponsored"], [class*="masthead-ad"], ' +
                    'ytlr-masthead-ad-renderer, ytlr-ad-slot-renderer, ' +
                    'ytlr-ad-overlay, .ytp-ad-player-overlay-layout { ' +
                    'display: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; } ' +
                    'video { transform: translateZ(0) !important; will-change: transform !important; background-color: #000000 !important; } ' +
                    'video::-webkit-media-controls { display: none !important; } ' +
                    'video::-webkit-media-controls-start-playback-button, ' +
                    'video::-webkit-media-controls-overlay-play-button { display: none !important; -webkit-appearance: none !important; } ' +
                    // 1. Título principal del video y metadatos con fondo transparente
                    'ytlr-watch-metadata, ytlr-watch-metadata *, .h5Pgmd, .LeWSF, .qaHdGc, .WVWtef, .y98TDb { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'text-shadow: 0 1px 4px rgba(0,0,0,0.95), 0 2px 8px rgba(0,0,0,0.85) !important; } ' +
                    // 2. Minutos y duración del video con fondo transparente y texto blanco puro
                    'span.g6XRz, span.V7jTHe, .g6XRz, .V7jTHe, .lxGV2b, .lxGV2b *, .ytp-time-current, .ytp-time-duration, .ytp-time-display * { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'color: #FFFFFF !important; opacity: 1 !important; ' +
                    'text-shadow: 0 1px 4px rgba(0,0,0,0.95) !important; } ' +
                    // 3. 'Up next' con fondo transparente
                    'ytlr-shelf-header ytlr-avatar-lockup, ytlr-shelf-header ytlr-avatar-lockup *, ytlr-avatar-lockup.sjENQb, .sjENQb { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'text-shadow: 0 1px 4px rgba(0,0,0,0.95) !important; } ' +
                    // 4. Títulos y canales de videos sugeridos en carrusel (fondo transparente + blanco nítido para no enfocados)
                    'yt-formatted-string.dxLAmd, yt-formatted-string.JkDfAc, .dxLAmd, .JkDfAc, ' +
                    'ytlr-tile-renderer yt-formatted-string, ytlr-compact-video-renderer yt-formatted-string { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'color: #FFFFFF !important; opacity: 1 !important; ' +
                    'text-shadow: 0 1px 3px rgba(0,0,0,0.95), 0 2px 6px rgba(0,0,0,0.85) !important; } ' +
                    // 5. Puntitos separadores de metadatos (•) con fondo transparente
                    '.QFqCxd::before, [class*="QFqCxd"]::before, span[idomkey*="detail-text"]::before, ' +
                    'yt-badge, ytlr-tile-renderer yt-badge, .RHrpge { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'color: #FFFFFF !important; text-shadow: 0 1px 3px rgba(0,0,0,0.95) !important; } ' +
                    // 6. Tooltips de botones de acción: fondo transparente, sin sombra y texto blanco puro
                    '.A71IFd, [class*="A71IFd"], .TamOWc, [class*="TamOWc"], .TamOWc *, .A71IFd *, ' +
                    'yt-icon-button .A71IFd, yt-icon-button.zylon-focus .A71IFd, yt-icon-button yt-formatted-string, ' +
                    'yt-formatted-string.A71IFd, ytlr-tooltip-renderer, ytlr-tooltip-renderer *, ' +
                    '.ytp-tooltip, .ytp-tooltip *, tp-yt-paper-tooltip, tp-yt-paper-tooltip *, [class*="tooltip"] { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'color: #FFFFFF !important; text-shadow: none !important; } ' +
                    // 7. Textos en botones Description y Subscribe (sin sombras oscuras ni manchas, nitidez perfecta)
                    'yt-button-container yt-formatted-string, ytlr-player-actions-container yt-button-container yt-formatted-string, ' +
                    'ytlr-button-renderer yt-formatted-string, ytlr-subscribe-button-renderer yt-formatted-string { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; text-shadow: none !important; } ' +
                    '.zylon-focus > yt-button-container yt-formatted-string, yt-button-container.zylon-focus yt-formatted-string, ' +
                    '.zylon-focus yt-button-container yt-formatted-string, yt-button-container[focused] yt-formatted-string, ' +
                    'ytlr-button-renderer.zylon-focus yt-button-container yt-formatted-string { ' +
                    'color: #0f0f0f !important; text-shadow: none !important; } ' +
                    'yt-button-container:not(.zylon-focus) yt-formatted-string, ytlr-button-renderer:not(.zylon-focus) yt-formatted-string, ' +
                    'ytlr-subscribe-button-renderer:not(.zylon-focus) yt-formatted-string { ' +
                    'color: #FFFFFF !important; text-shadow: none !important; } ' +
                    // 7.1. Botones de controles (Opción B): Cápsula envolvente transparente y cada botón con su círculo negro transparente
                    'div.clJQEe, .clJQEe:not(.zylon-focus), [class*="clJQEe"] { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; border: none !important; } ' +
                    'yt-button-container:not(.zylon-focus), .MIiKQd:not(.zylon-focus), .CgA6bd:not(.zylon-focus), .XqDMCd:not(.zylon-focus) { ' +
                    'background-color: rgba(0, 0, 0, 0.55) !important; background: rgba(0, 0, 0, 0.55) !important; } ' +
                    'div.clJQEe yt-icon-button, div.clJQEe yt-button-container { margin: 0 3px !important; } ' +
                    '.zylon-focus > yt-button-container, yt-button-container.zylon-focus, ' +
                    '.zylon-focus yt-button-container { ' +
                    'background-color: rgb(241, 241, 241) !important; background: rgb(241, 241, 241) !important; } ' +
                    // 8. Tarjetas de sugerencias de búsqueda (quitar fondo negro a textos y lupas sin tocar fondo gris de tarjeta)
                    'ytlr-tile-header-renderer .RRJiYc, ytlr-tile-metadata-renderer .RRJiYc, .RRJiYc, ' +
                    'ytlr-tile-header-renderer ytlr-tile-metadata-renderer yt-formatted-string { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; } ' +
                    'ytlr-tile-header-renderer ytlr-thumbnail-overlay-icon-renderer, ytlr-tile-header-renderer yt-icon, ' +
                    'ytlr-thumbnail-overlay-icon-renderer yt-icon, yt-icon.j1hAdd, .j1hAdd { ' +
                    'background-color: transparent !important; background: transparent !important; box-shadow: none !important; ' +
                    'color: #FFFFFF !important; fill: #FFFFFF !important; } ' +
                    // 9. Barra de progreso (Opción 1 Carmesí Neón): Fina (3px), degradado rojo, precarga blanca, fondo transparente y bolita roja completa
                    'ytlr-progress-bar .dySudf, ytlr-progress-bar .MheVmd, .ytp-progress-bar-container, .ytp-progress-bar { ' +
                    'height: 3px !important; border-radius: 2px !important; background-color: transparent !important; background: transparent !important; } ' +
                    'ytlr-progress-bar .yTWxAb, .ytp-progress-list { ' +
                    'height: 3px !important; border-radius: 2px !important; background-color: rgba(255, 255, 255, 0.12) !important; background: rgba(255, 255, 255, 0.12) !important; } ' +
                    'ytlr-progress-bar .tkZjRe, .ytp-load-progress { ' +
                    'height: 3px !important; border-radius: 2px !important; background-color: rgba(255, 255, 255, 0.60) !important; background: rgba(255, 255, 255, 0.60) !important; box-shadow: none !important; } ' +
                    'ytlr-progress-bar .STXq3b, .ytp-play-progress { ' +
                    'height: 3px !important; border-radius: 2px !important; ' +
                    'background: linear-gradient(90deg, #8B0000 0%, #FF0033 65%, #FF2A55 100%) !important; background-color: #FF0033 !important; ' +
                    'box-shadow: 0 0 8px rgba(255, 0, 51, 0.7) !important; } ' +
                    'ytlr-playhead .G0uJP, .ytp-scrubber-button { ' +
                    'width: 10px !important; height: 10px !important; border-radius: 50% !important; ' +
                    'background: #FF0033 !important; background-color: #FF0033 !important; border: none !important; outline: none !important; ' +
                    'box-shadow: 0 0 8px rgba(255, 0, 51, 0.9), 0 0 2px #990000 !important; transform: scale(1.1) !important; }';

                var style = document.getElementById('shield-tv-styles');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'shield-tv-styles';
                    style.type = 'text/css';
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = cssRules;

                // 9. Actualizador proactivo de miniaturas a HD real (hq720.jpg)
                function upgradeThumbElement(el) {
                    if (!el || el.dataset.hdUpgraded) return;
                    var style = el.getAttribute('style') || '';
                    if (!style.includes('i.ytimg.com/vi/')) return;

                    var m = style.match(/https?:\/\/i\.ytimg\.com\/vi\/([^\/\?]+)\/(hqdefault|mqdefault)[^"'\)]*/);
                    if (!m) return;

                    var videoId = m[1];
                    var hdUrl = 'https://i.ytimg.com/vi/' + videoId + '/hq720.jpg';

                    var test = new Image();
                    test.onload = function() {
                        if (test.naturalWidth >= 640) {
                            var curStyle = el.getAttribute('style') || '';
                            if (curStyle.includes(videoId)) {
                                el.style.backgroundImage = 'url("' + hdUrl + '")';
                                el.dataset.hdUpgraded = 'true';
                            }
                        }
                    };
                    test.onerror = function() {
                        el.dataset.hdUpgraded = 'failed';
                    };
                    test.src = hdUrl;
                }

                function scanAndUpgradeThumbs() {
                    var items = document.querySelectorAll('ytlr-thumbnail-details, [class*="thumbnail"], [style*="i.ytimg.com/vi/"]');
                    for (var i = 0; i < items.length; i++) {
                        upgradeThumbElement(items[i]);
                    }
                }

                scanAndUpgradeThumbs();
                if (!window.__shieldHdThumbInterval) {
                    window.__shieldHdThumbInterval = setInterval(scanAndUpgradeThumbs, 1500);
                }

                // 9.1. Observer para eliminar anuncios masthead o promociones del feed dinámicamente y limpiar posters
                if (!window.__shieldMutationObserver && window.MutationObserver) {
                    try {
                        window.__shieldMutationObserver = new MutationObserver(function() {
                            var sponsored = document.querySelectorAll('ytlr-masthead-ad-renderer, ytlr-ad-slot-renderer, [class*="sponsored"], [aria-label*="Sponsored"], [aria-label*="Patrocinado"]');
                            for (var i = 0; i < sponsored.length; i++) {
                                sponsored[i].remove();
                            }
                            var v = document.querySelector('video');
                            if (v && v.hasAttribute('poster')) {
                                v.removeAttribute('poster');
                            }
                            var thumbs = document.querySelectorAll('ytlr-thumbnail-details:not([data-hd-upgraded]), [style*="i.ytimg.com/vi/"]:not([data-hd-upgraded])');
                            for (var t = 0; t < thumbs.length; t++) {
                                upgradeThumbElement(thumbs[t]);
                            }
                        });
                        window.__shieldMutationObserver.observe(document.documentElement || document.body, { childList: true, subtree: true });
                    } catch(e) {}
                }

                // 10. Monitor activo de alta velocidad (cada 90ms) como red de seguridad para cualquier anuncio
                if (!window.__shieldAdMonitorInterval) {
                    window.__shieldAdMonitorInterval = setInterval(function() {
                        var player = document.getElementById('ytlr-player__player-container-player') ||
                                     document.getElementById('movie_player') ||
                                     document.querySelector('.html5-video-player');
                        var video = document.querySelector('video');

                        var isAd = false;
                        if (player) {
                            if (player.classList.contains('ad-showing') ||
                                player.classList.contains('ad-interrupting')) {
                                isAd = true;
                            }
                            if (typeof player.getAdState === 'function' && player.getAdState() > 0) {
                                isAd = true;
                            }
                            if (typeof player.isLifaAdPlaying === 'function' && player.isLifaAdPlaying()) {
                                isAd = true;
                            }
                        }
                        if (!isAd && document.querySelector('.ytp-ad-player-overlay, .ytp-ad-module, ytlr-ad-overlay, .ytp-ad-text, .ytp-ad-preview-text, [class*="ad-overlay"]')) {
                            isAd = true;
                        }

                        if (isAd) {
                            if (video) {
                                video.muted = true;
                                video.playbackRate = 16.0;
                                if (isFinite(video.duration) && video.duration > 0 && video.duration <= 120) {
                                    video.currentTime = video.duration;
                                }
                            }

                            if (player) {
                                try { if (typeof player.onAdUxClicked === 'function') player.onAdUxClicked(); } catch(e){}
                                try { if (typeof player.finishAd === 'function') player.finishAd(); } catch(e){}
                                try { if (typeof player.skipAd === 'function') player.skipAd(); } catch(e){}
                                try { if (typeof player.cancelPlaybackAd === 'function') player.cancelPlaybackAd(); } catch(e){}
                                player.classList.remove('ad-showing', 'ad-interrupting');
                            }

                            var skipBtns = document.querySelectorAll(
                                '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
                                'button[class*="skip"], .ytp-ad-skip-button-slot, [aria-label*="Skip"], [aria-label*="Saltar"]'
                            );
                            for (var i = 0; i < skipBtns.length; i++) {
                                try { skipBtns[i].click(); } catch(e){}
                            }
                        } else {
                            if (video && video.playbackRate > 2.0) {
                                video.playbackRate = 1.0;
                                video.muted = false;
                            }
                        }

                        // Auto-cerrar diálogos emergentes de advertencia o anti-adblock
                        var dismissBtn = document.querySelector(
                            'tp-yt-paper-dialog button[aria-label="Dismiss"], ' +
                            'ytd-popup-container button, .ytp-ad-overlay-close-button, ' +
                            'ytlr-dialog-renderer button'
                        );
                        if (dismissBtn) {
                            try { dismissBtn.click(); } catch(e){}
                        }

                        // Auto-confirmar y continuar reproducción ante avisos de "¿Sigues viendo?" / inactividad
                        var confirmBtn = document.querySelector(
                            'yt-confirm-dialog-renderer #confirm-button, ' +
                            'ytlr-confirm-dialog-renderer button, ' +
                            'tp-yt-paper-dialog #confirm-button, ' +
                            'yt-button-renderer#confirm-button button, ' +
                            'button[aria-label*="Continuar"], button[aria-label*="Continue"], ' +
                            'button[aria-label*="Sí"], button[aria-label*="Yes"]'
                        );
                        if (confirmBtn) {
                            try {
                                confirmBtn.click();
                                var v = document.querySelector('video');
                                if (v && v.paused && !v.ended) {
                                    v.play();
                                }
                            } catch(e){}
                        }
                    }, 90);
                }
            })();
        """.trimIndent()
    }

    private fun injectTvAdBlockScript() {
        runOnUiThread {
            webView.evaluateJavascript(getTvShieldInjectionScript(), null)
        }
    }

    /**
     * Inyección de SponsorBlock con puente nativo seguro (sin violaciones de Content Security Policy)
     */
    private fun injectSponsorBlockScript() {
        val sbJs = """
            (function() {
                if (window.__shieldSponsorBlockActive) return;
                window.__shieldSponsorBlockActive = true;

                var currentVideoId = null;
                var cachedSegments = [];

                window.__onSponsorSegmentsReceived = function(videoId, data) {
                    if (videoId === currentVideoId && Array.isArray(data)) {
                        cachedSegments = data;
                        console.log('SponsorBlock nativo: ' + data.length + ' segmentos recibidos para ' + videoId);
                    }
                };

                function extractVideoId() {
                    try {
                        var player = document.getElementById('ytlr-player__player-container-player') || document.getElementById('movie_player');
                        if (player && typeof player.getVideoData === 'function') {
                            var data = player.getVideoData();
                            if (data && data.video_id) return data.video_id;
                        }
                        var url = window.location.href;
                        var match = url.match(/[?&]v=([^&#]+)/) || url.match(/\/shorts\/([^&#/?]+)/) || url.match(/\/watch\/([^&#/?]+)/);
                        if (match) return match[1];
                    } catch(e) {}
                    return null;
                }

                function checkAndRequestSegments() {
                    var videoId = extractVideoId();
                    if (videoId && videoId !== currentVideoId) {
                        currentVideoId = videoId;
                        cachedSegments = [];
                        if (window.AndroidSponsorBlock && typeof window.AndroidSponsorBlock.requestSkipSegments === 'function') {
                            window.AndroidSponsorBlock.requestSkipSegments(videoId);
                        }
                    }
                }

                function checkAndSkipSponsor() {
                    checkAndRequestSegments();

                    if (!cachedSegments || cachedSegments.length === 0) return;

                    var video = document.querySelector('video');
                    if (!video || video.paused) return;

                    var curTime = video.currentTime;
                    for (var i = 0; i < cachedSegments.length; i++) {
                        var seg = cachedSegments[i].segment;
                        if (seg && seg.length >= 2) {
                            var start = seg[0];
                            var end = seg[1];
                            if (curTime >= start && curTime < (end - 0.5)) {
                                video.currentTime = end;
                                showSponsorBadge(cachedSegments[i].category);
                                break;
                            }
                        }
                    }
                }

                function showSponsorBadge(category) {
                    var badge = document.getElementById('shield-sb-badge');
                    if (!badge) {
                        badge = document.createElement('div');
                        badge.id = 'shield-sb-badge';
                        badge.style.position = 'fixed';
                        badge.style.bottom = '40px';
                        badge.style.right = '40px';
                        badge.style.backgroundColor = 'rgba(16, 19, 30, 0.95)';
                        badge.style.border = '2px solid #2ECC71';
                        badge.style.borderRadius = '14px';
                        badge.style.padding = '10px 20px';
                        badge.style.color = '#FFFFFF';
                        badge.style.fontSize = '16px';
                        badge.style.fontWeight = 'bold';
                        badge.style.zIndex = '999999';
                        badge.style.boxShadow = '0 6px 20px rgba(0,0,0,0.8)';
                        badge.style.transition = 'opacity 0.35s ease';
                        document.body.appendChild(badge);
                    }
                    var label = category === 'sponsor' ? 'Patrocinio' : (category === 'selfpromo' ? 'Auto-promoción' : 'Intro/Outro');
                    badge.textContent = '🛡️ ' + label + ' saltado (SponsorBlock)';
                    badge.style.opacity = '1';
                    badge.style.display = 'block';
                    clearTimeout(window.__shieldSbTimeout);
                    window.__shieldSbTimeout = setTimeout(function() {
                        badge.style.opacity = '0';
                        setTimeout(function() { badge.style.display = 'none'; }, 350);
                    }, 2800);
                }

                setInterval(checkAndSkipSponsor, 300);
            })();
        """.trimIndent()

        runOnUiThread {
            webView.evaluateJavascript(sbJs, null)
        }
    }

    /**
     * Consulta nativa de SponsorBlock en segundo plano para eludir la política CSP de YouTube TV
     */
    fun fetchSponsorSegments(videoId: String) {
        Thread {
            try {
                val url = URL("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22%5D")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "YouTubeShield-AndroidTV/1.0")
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    runOnUiThread {
                        webView.evaluateJavascript("if(window.__onSponsorSegmentsReceived) window.__onSponsorSegmentsReceived('$videoId', $json);", null)
                    }
                } else {
                    runOnUiThread {
                        webView.evaluateJavascript("if(window.__onSponsorSegmentsReceived) window.__onSponsorSegmentsReceived('$videoId', []);", null)
                    }
                }
            } catch (e: Exception) {
                Log.e("TvSponsorBlock", "Error al consultar SponsorBlock API: ${e.message}")
                runOnUiThread {
                    webView.evaluateJavascript("if(window.__onSponsorSegmentsReceived) window.__onSponsorSegmentsReceived('$videoId', []);", null)
                }
            }
        }.start()
    }

    /**
     * Puente JavaScript para que el código inyectado en la página invoque SponsorBlock de forma nativa
     */
    class TvSponsorBlockBridge(private val activity: TvMainActivity) {
        @JavascriptInterface
        fun requestSkipSegments(videoId: String) {
            activity.fetchSponsorSegments(videoId)
        }
    }

    /**
     * Detección de incompatibilidad y rotación automática de User-Agent
     */
    private fun checkAndRecoverUnsupportedBrowser() {
        val checkJs = """
            (function() {
                var bodyText = document.body ? document.body.innerText.toLowerCase() : '';
                return bodyText.indexOf('browser not supported') !== -1 ||
                       bodyText.indexOf('navegador no compatible') !== -1 ||
                       bodyText.indexOf('update your browser') !== -1;
            })();
        """.trimIndent()

        webView.evaluateJavascript(checkJs) { result ->
            if (result == "true") {
                rotateUserAgent(autoRecover = true)
            }
        }
    }

    private fun rotateUserAgent(autoRecover: Boolean = false) {
        val nextProfile = TvUserAgentManager.rotateNext(this)
        webView.settings.userAgentString = nextProfile.userAgent

        val msg = if (autoRecover) {
            "Perfil rotado por incompatibilidad: ${nextProfile.name}"
        } else {
            "Perfil TV cambiado a: ${nextProfile.name}"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        updateHudDisplay()
        showHud(4500)

        // Recargar con el nuevo perfil
        webView.reload()
    }

    /**
     * Gestión avanzada de eventos del control remoto de Android TV
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Tecla Atrás del control remoto
                KeyEvent.KEYCODE_BACK -> {
                    return handleTvBackKey()
                }
                // Tecla Menú o Info: Alternar / Mostrar HUD del Escudo
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    toggleShield()
                    return true
                }
                // Tecla Guía, Botón Amarillo/Rojo o Tecla U: Rotar User-Agent de Smart TV
                KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_U -> {
                    rotateUserAgent()
                    return true
                }
                // Teclas multimedia del control remoto
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    triggerMediaAction("playPause")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    triggerMediaAction("play")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    triggerMediaAction("pause")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    triggerMediaAction("seekForward")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    triggerMediaAction("seekBackward")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (!handleTvBackKey()) {
            super.onBackPressed()
        }
    }

    private fun handleTvBackKey(): Boolean {
        val currentUrl = webView.url ?: ""

        // Si la navegación está en una página externa (como cuentas o login de Google)
        if (!currentUrl.contains("youtube.com/tv")) {
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            } else {
                webView.loadUrl(YOUTUBE_TV_URL)
                return true
            }
        }

        // Script inteligente para la SPA de YouTube TV (Leanback)
        val jsBack = """
            (function() {
                var url = window.location.href || '';
                var hash = window.location.hash || '';
                var bodyClass = document.body ? document.body.className : '';

                // 1. Detección de pantalla de selección de cuenta / usuarios
                var isAccountSelector = bodyClass.indexOf('WEB_PAGE_TYPE_ACCOUNT_SELECTOR') !== -1 ||
                                        hash.indexOf('/identity') !== -1 ||
                                        hash.indexOf('/accounts') !== -1;
                if (isAccountSelector) {
                    return "EXIT_PROMPT";
                }

                // 2. Detección de video o modo de reproducción (Watch)
                var isWatch = bodyClass.indexOf('WEB_PAGE_TYPE_WATCH') !== -1 ||
                              hash.indexOf('/watch') !== -1 ||
                              url.indexOf('/watch') !== -1;

                var video = document.querySelector('video');
                var isVideoActive = isWatch || (video && video.offsetWidth > 0 && video.offsetHeight > 0 && window.getComputedStyle(video).display !== 'none');

                function dispatchEsc() {
                    var opts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
                    var target = document.activeElement || document.body || document;
                    try { target.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch(e) {}
                    try { target.dispatchEvent(new KeyboardEvent('keyup', opts)); } catch(e) {}
                    try { document.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch(e) {}
                    try { document.dispatchEvent(new KeyboardEvent('keyup', opts)); } catch(e) {}
                    try { window.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch(e) {}
                    try { window.dispatchEvent(new KeyboardEvent('keyup', opts)); } catch(e) {}
                }

                if (isVideoActive) {
                    dispatchEsc();
                    return "HANDLED_VIDEO_BACK";
                }

                // 3. Detección de si está en la raíz de Home
                var isRootHome = (hash === '#/' || hash === '' || hash === '#/browse') && 
                                 bodyClass.indexOf('WEB_PAGE_TYPE_BROWSE') !== -1;

                var el = document.activeElement;
                var isSidebarFocused = false;
                if (el) {
                    var menuContainer = el.closest('ytlr-navigation-menu, ytlr-guide-renderer, #guide, [role="navigation"]');
                    var text = (el.innerText || '').trim();
                    if (menuContainer || text === 'Home' || text === 'Search' || text === 'Sign in' || text === 'Subscriptions' || text === 'Library') {
                        isSidebarFocused = true;
                    }
                }

                // Si ya está en la barra lateral del Home raíz, presionar Atrás debe pedir confirmación de salida,
                // evitando que YouTube TV salte a la pantalla de selección de cuenta (#/identity).
                if (isRootHome && isSidebarFocused) {
                    return "EXIT_PROMPT";
                }

                // Para cualquier otra navegación interna (subpáginas como búsqueda, canal, o abrir menú lateral desde el feed):
                dispatchEsc();
                return "HANDLED_NAV_BACK";
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsBack) { rawResult ->
            val result = rawResult?.replace("\"", "") ?: "HANDLED_NAV_BACK"

            when (result) {
                "EXIT_PROMPT" -> {
                    if (backPressedOnce) {
                        finish()
                    } else {
                        backPressedOnce = true
                        Toast.makeText(this, getString(R.string.tv_exit_prompt), Toast.LENGTH_SHORT).show()
                        mainHandler.postDelayed(resetBackRunnable, 2500)
                    }
                }
                "HANDLED_VIDEO_BACK" -> {
                    // El usuario estaba viendo un video: se envió ESC y vuelve al Home.
                    // Reseteamos el flag de salida para evitar que salga de la app por error.
                    backPressedOnce = false
                    mainHandler.removeCallbacks(resetBackRunnable)
                }
                "HANDLED_NAV_BACK" -> {
                    backPressedOnce = false
                    mainHandler.removeCallbacks(resetBackRunnable)
                }
                "EXTERNAL_BACK" -> {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        webView.loadUrl(YOUTUBE_TV_URL)
                    }
                }
            }
        }
        return true
    }

    private fun triggerMediaAction(action: String) {
        val js = when (action) {
            "playPause" -> "var v = document.querySelector('video'); if (v) { if (v.paused) v.play(); else v.pause(); }"
            "play" -> "var v = document.querySelector('video'); if (v) v.play();"
            "pause" -> "var v = document.querySelector('video'); if (v) v.pause();"
            "seekForward" -> "var v = document.querySelector('video'); if (v) v.currentTime += 10;"
            "seekBackward" -> "var v = document.querySelector('video'); if (v) v.currentTime -= 10;"
            else -> ""
        }
        if (js.isNotEmpty()) {
            webView.evaluateJavascript("(function(){ $js })();", null)
        }
    }

    private fun toggleShield() {
        isShieldActive = !isShieldActive
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("shield_active_tv", isShieldActive).apply()

        updateHudDisplay()
        showHud(4000)

        if (isShieldActive) {
            injectTvAdBlockScript()
        } else {
            webView.evaluateJavascript(
                "(function(){ var s = document.getElementById('shield-tv-styles'); if (s) s.remove(); if (window.__shieldAdMonitorInterval) { clearInterval(window.__shieldAdMonitorInterval); window.__shieldAdMonitorInterval = null; } })();",
                null
            )
        }
    }

    private fun showHud(durationMs: Long) {
        mainHandler.removeCallbacks(hideHudRunnable)
        updateHudDisplay()
        hudCard.visibility = View.VISIBLE
        hudCard.animate().alpha(1f).setDuration(250).start()
        mainHandler.postDelayed(hideHudRunnable, durationMs)
    }

    private fun hideHud() {
        hudCard.animate().alpha(0f).setDuration(300).withEndAction {
            hudCard.visibility = View.GONE
        }.start()
    }

    private fun updateHudDisplay() {
        val currentProfile = TvUserAgentManager.getCurrentProfile(this)
        if (isShieldActive) {
            hudIcon.setImageResource(R.drawable.ic_shield)
            hudIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.shield_on)
            hudText.text = getString(R.string.tv_shield_enabled)
        } else {
            hudIcon.setImageResource(R.drawable.ic_shield_grey)
            hudIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.primary_red)
            hudText.text = getString(R.string.tv_shield_disabled)
        }
        hudSubtext.text = "${currentProfile.name} • SponsorBlock: ${if (isSponsorBlockActive) "ON" else "OFF"}"
    }

    private fun hideSplashWithFade() {
        if (splashOverlay.visibility == View.VISIBLE) {
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    splashOverlay.visibility = View.GONE
                    splashOverlay.alpha = 1f
                }
                .start()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
