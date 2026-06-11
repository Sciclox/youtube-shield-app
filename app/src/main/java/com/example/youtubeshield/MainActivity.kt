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
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var isActivityRunning = false
    }

    private lateinit var webView: WebView
    private lateinit var navBarCard: CardView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var splashOverlay: FrameLayout
    private lateinit var btnShield: ImageButton

    private var isShieldActive = true
    private var isDynamicShieldActive = false
    private var isLoopEnabled = false
    private val adBlockHandler = Handler(Looper.getMainLooper())

    // Variables de control para el pulso automático del escudo
    private var isPulseActive = false
    private var lastPulseVideoId: String? = null
    private val shieldPulseHandler = Handler(Looper.getMainLooper())
    private val shieldPulseRunnable = Runnable {
        isShieldActive = true
        isPulseActive = false
        updateShieldUI()
        android.util.Log.d("Shield", "Escudo reactivado automáticamente después del pulso")
    }

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
    private var currentThumbnail: Bitmap? = null
    private var loadedThumbnailVideoId: String? = null

    // Extractor de colores dinámicos
    private lateinit var colorExtractor: DynamicColorExtractor

    @Volatile
    private var currentActiveUrl: String = "https://m.youtube.com"

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
            if (isShieldActive && isDynamicShieldActive) {
                val currentUrl = webView.url ?: ""
                val isWatchOrShort = currentUrl.contains("watch?v=") || currentUrl.contains("/shorts/")
                if (isWatchOrShort) {
                    injectAdBlockScript()
                }
            }
            adBlockHandler.postDelayed(this, 2000) // Re-inyecta cada 2 segundos
        }
    }

    // Runnable para monitorear el estado del video de YouTube y actualizar la notificación
    private val playbackMonitorRunnable = object : Runnable {
        override fun run() {
            queryVideoState()
            adBlockHandler.postDelayed(this, 1000) // Verifica cada 1 segundo
        }
    }

    private val videoChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val url = intent.getStringExtra("video_url")
            if (!url.isNullOrEmpty()) {
                changeVideo(url)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar base de datos de AdBlocker
        AdBlocker.init(this)

        // Inicializar extractor de colores dinámicos
        colorExtractor = DynamicColorExtractor()

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
        adBlockHandler.post(playbackMonitorRunnable)
        setupSplashGif()

        // Procesar intent de inicio si viene desde el widget
        handleIntent(intent)

        isActivityRunning = true

        // Registrar broadcast receiver para cambiar de canción en segundo plano
        val videoChangeFilter = android.content.IntentFilter("com.example.youtubeshield.ACTION_CHANGE_VIDEO")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(videoChangeReceiver, videoChangeFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(videoChangeReceiver, videoChangeFilter)
        }
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

    private fun setupSplashGif() {
        val splashLogo = findViewById<ImageView>(R.id.splashLogo) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(assets, "ytsplash.gif")
                val drawable = ImageDecoder.decodeDrawable(source)
                splashLogo.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.start()
                }
            } catch (e: Exception) {
                splashLogo.setImageResource(R.drawable.ic_youtube_logo)
            }
        } else {
            try {
                val layout = splashLogo.parent as? android.view.ViewGroup
                if (layout != null) {
                    val index = layout.indexOfChild(splashLogo)
                    val lp = splashLogo.layoutParams
                    
                    val webViewGif = WebView(this).apply {
                        layoutParams = lp
                        setBackgroundColor(0x00000000)
                        settings.allowFileAccess = true
                    }
                    
                    layout.removeView(splashLogo)
                    layout.addView(webViewGif, index)
                    
                    val html = """
                        <html>
                        <head>
                        <style>
                            body { margin: 0; padding: 0; background: #FFFFFF; display: flex; justify-content: center; align-items: center; height: 100vh; overflow: hidden; }
                            img { width: 100%; height: 100%; object-fit: contain; }
                        </style>
                        </head>
                        <body>
                            <img src="ytsplash.gif" />
                        </body>
                        </html>
                    """.trimIndent()
                    
                    webViewGif.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
                }
            } catch (e: Exception) {
                splashLogo.setImageResource(R.drawable.ic_youtube_logo)
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
                            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (player) {
                                try {
                                    if (typeof player.setLoop === 'function') player.setLoop($isLoopEnabled);
                                } catch(e){}
                                try {
                                    if (typeof player.setOption === 'function') player.setOption('loop', $isLoopEnabled);
                                } catch(e){}
                                try {
                                    if (typeof player.setOption === 'function') player.setOption('playlist', 'loop', $isLoopEnabled);
                                } catch(e){}
                            }
                            return $isLoopEnabled;
                        })()
                    """.trimIndent()
                    webView.evaluateJavascript(js) { _ ->
                        Toast.makeText(
                            this@MainActivity,
                            if (isLoopEnabled) "Repetir canción: ACTIVADO" else "Repetir canción: DESACTIVADO",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onSeekTo(position: Long) {
                runOnUiThread {
                    val seconds = position / 1000.0
                    webView.evaluateJavascript("var v = document.querySelector('video'); if (v) v.currentTime = $seconds;", null)
                }
            }
        })
    }

    private fun queryVideoState() {
        val currentUrl = webView.url ?: ""
        currentActiveUrl = currentUrl
        updateMediaPlaybackGestureSetting(currentUrl)

        val isWatchOrShort = currentUrl.contains("watch?v=") || currentUrl.contains("/shorts/")
        if (!isWatchOrShort) {
            lastPulseVideoId = null
        }
        
        var currentVideoId: String? = null
        if (isWatchOrShort) {
            if (currentUrl.contains("watch?v=")) {
                try {
                    val uri = Uri.parse(currentUrl)
                    val videoId = uri.getQueryParameter("v")
                    if (videoId != null) {
                        if (videoId != lastVideoId) {
                            lastVideoId = videoId
                            runOnUiThread {
                                injectVisibilityOverride()
                            }
                        }
                        currentVideoId = videoId
                    }
                } catch (e: Exception) {
                    // Ignorar error al parsear URL
                }
            } else if (currentUrl.contains("/shorts/")) {
                try {
                    val segments = Uri.parse(currentUrl).pathSegments
                    val shortId = segments.lastOrNull()
                    if (shortId != null) {
                        if (shortId != lastVideoId) {
                            lastVideoId = shortId
                            runOnUiThread {
                                injectVisibilityOverride()
                            }
                        }
                        currentVideoId = shortId
                    }
                } catch (e: Exception) {
                    // Ignorar
                }
            }

            // Descargar miniatura si hay un video activo nuevo
            if (currentVideoId != null) {
                if (currentVideoId != loadedThumbnailVideoId) {
                    loadedThumbnailVideoId = currentVideoId
                    currentThumbnail = null
                    fetchThumbnail(currentVideoId)
                }
            } else {
                loadedThumbnailVideoId = null
                currentThumbnail = null
            }
        } else {
            if (lastVideoId != null || loadedThumbnailVideoId != null) {
                lastVideoId = null
                loadedThumbnailVideoId = null
                currentThumbnail = null
                playbackService?.updateMetadata("YouTube Shield", false, isLoopEnabled, null, 0L, 0L)
            }
        }

        val js = """
            (function() {
                var video = document.querySelector('video');
                var playlist = [];
                var seenUrls = new Set();

                // 1. Intentar extraer de la playlist/mix activa de YouTube (ytm-playlist-panel-video-renderer)
                var playlistItems = document.querySelectorAll('ytm-playlist-panel-video-renderer, .playlist-panel-video-renderer, ytm-playlist-panel-renderer ytm-playlist-panel-video-renderer');
                if (playlistItems && playlistItems.length > 0) {
                    playlistItems.forEach(function(item) {
                        var link = item.querySelector('a[href*="watch?v="], a[href*="/watch"]');
                        if (!link && item.tagName === 'A') {
                            link = item;
                        }
                        var titleEl = item.querySelector('.playlist-panel-video-title, .title, h4, h3');
                        var channelEl = item.querySelector('.playlist-panel-video-owner, .playlist-panel-video-subtitle, .channel, span');
                        
                        if (link && titleEl) {
                            var href = link.getAttribute('href') || link.href || "";
                            if (href && !seenUrls.has(href)) {
                                seenUrls.add(href);
                                var titleText = (titleEl.textContent || titleEl.innerText || "").trim();
                                var channelText = channelEl ? (channelEl.textContent || channelEl.innerText || "").trim() : "";
                                if (channelText.includes('\n')) {
                                    channelText = channelText.split('\n')[0].trim();
                                }
                                if (titleText && href) {
                                    playlist.push({
                                        title: titleText,
                                        url: href,
                                        channel: channelText
                                    });
                                }
                            }
                        }
                    });
                }

                // 2. Si no hay mix/playlist activa, extraer recomendaciones (Up Next / Feed)
                if (playlist.length === 0) {
                    var items = document.querySelectorAll('ytm-media-item, ytm-compact-video-renderer, ytm-video-with-context-renderer, ytm-rich-item-renderer, .compact-media-item');
                    items.forEach(function(item) {
                        var link = item.querySelector('a[href*="/watch"], a[href*="watch?v="]');
                        var titleEl = item.querySelector('.media-item-title, .compact-media-item-headline, .playlist-panel-video-title, h3, h4, [class*="title"]');
                        var channelEl = item.querySelector('.media-item-subtitle, .compact-media-item-channel-name, .playlist-panel-video-owner, [class*="channel"], [class*="owner"], [class*="subtitle"]');
                        
                        if (link && titleEl) {
                            var href = link.getAttribute('href') || link.href || "";
                            if (href && !seenUrls.has(href)) {
                                seenUrls.add(href);
                                var titleText = (titleEl.textContent || titleEl.innerText || "").trim();
                                var channelText = channelEl ? (channelEl.textContent || channelEl.innerText || "").trim() : "";
                                if (channelText.includes('\n')) {
                                    channelText = channelText.split('\n')[0].trim();
                                }
                                if (titleText && href) {
                                    playlist.push({
                                        title: titleText,
                                        url: href,
                                        channel: channelText
                                    });
                                }
                            }
                        }
                    });
                }

                // 3. Fallback final por si no encuentra elementos estructurados
                if (playlist.length === 0) {
                    var links = document.querySelectorAll('a[href*="/watch"], a[href*="watch?v="]');
                    links.forEach(function(link) {
                        var href = link.getAttribute('href') || link.href || "";
                        if (href && !seenUrls.has(href)) {
                            seenUrls.add(href);
                            var titleText = "";
                            var titleEl = link.querySelector('h3, h4, span, [class*="title"]');
                            if (titleEl) {
                                titleText = titleEl.textContent || titleEl.innerText || "";
                            } else {
                                titleText = link.textContent || link.innerText || "";
                            }
                            titleText = titleText.replace(/\s+/g, ' ').trim();
                            if (titleText && titleText.length > 5) {
                                playlist.push({
                                    title: titleText,
                                    url: href,
                                    channel: ""
                                });
                            }
                        }
                    });
                }

                var slicedPlaylist = playlist.slice(0, 20);

                if (!video) return { title: "YouTube Shield", isPlaying: false, position: 0, duration: 0, playlist: slicedPlaylist };
                
                var title = document.title;
                title = title.replace(/^\(\d+\)\s+/, '');
                if (title.endsWith(' - YouTube')) {
                    title = title.substring(0, title.length - 10);
                }
                
                var posMs = Math.floor(video.currentTime * 1000);
                var durMs = isNaN(video.duration) ? 0 : Math.floor(video.duration * 1000);
                
                return {
                    title: title || "YouTube Video",
                    isPlaying: !video.paused && !video.ended,
                    position: posMs,
                    duration: durMs,
                    playlist: slicedPlaylist
                };
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"null\"") {
                try {
                    val json = org.json.JSONObject(result)
                    val title = json.optString("title", "YouTube Shield")
                    val isPlaying = json.optBoolean("isPlaying", false)
                    val position = json.optLong("position", 0L)
                    val duration = json.optLong("duration", 0L)
                    
                    // Parsear la playlist recibida
                    val playlistArray = json.optJSONArray("playlist")
                    val newPlaylist = ArrayList<PlaylistRepository.PlaylistItem>()
                    if (playlistArray != null) {
                        for (i in 0 until playlistArray.length()) {
                            val itemObj = playlistArray.getJSONObject(i)
                            val itemTitle = itemObj.optString("title", "")
                            val itemUrl = itemObj.optString("url", "")
                            val itemChannel = itemObj.optString("channel", "")
                            if (itemTitle.isNotEmpty() && itemUrl.isNotEmpty()) {
                                newPlaylist.add(PlaylistRepository.PlaylistItem(itemTitle, itemUrl, itemChannel))
                            }
                        }
                    }
                    
                    // Si ha cambiado la playlist o la canción activa (comparando IDs de video), actualizar el repositorio y el Widget
                    val oldVideoId = getVideoId(PlaylistRepository.currentPlayingUrl)
                    val newVideoId = getVideoId(currentUrl)
                    val urlChanged = oldVideoId != newVideoId
                    
                    val oldUrls = PlaylistRepository.playlist.map { it.url }
                    val newUrls = newPlaylist.map { it.url }
                    
                    // Si la nueva playlist está vacía y estamos en una página de video/shorts,
                    // asumimos que es una carga intermedia y NO actualizamos la lista con elementos vacíos.
                    val shouldKeepOldPlaylist = newPlaylist.isEmpty() && isWatchOrShort
                    val playlistChanged = !shouldKeepOldPlaylist && (oldUrls != newUrls)
                    
                    if (playlistChanged || urlChanged || PlaylistRepository.isPlaying != isPlaying) {
                        if (!shouldKeepOldPlaylist) {
                            PlaylistRepository.playlist = newPlaylist
                        }
                        PlaylistRepository.currentPlayingUrl = currentUrl
                        PlaylistRepository.isPlaying = isPlaying
                        PlaylistWidgetProvider.refreshPlaylist(this@MainActivity)
                    }

                    if (urlChanged && newVideoId != null) {
                        triggerShieldPulse(newVideoId, shouldReload = true)
                    }

                    if (isPlaying && isShieldActive && !isDynamicShieldActive) {
                        isDynamicShieldActive = true
                        runOnUiThread {
                            injectAdBlockScript()
                        }
                    }
                    
                    if (isWatchOrShort) {
                        playbackService?.updateMetadata(title, isPlaying, isLoopEnabled, currentThumbnail, position, duration)
                    }
                } catch (e: Exception) {
                    // Ignorar errores de parsing
                }
            }
        }
    }

    private fun fetchThumbnail(videoId: String) {
        Thread {
            try {
                val url = java.net.URL("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                val connection = url.openConnection()
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val input = connection.getInputStream()
                var bitmap = android.graphics.BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    bitmap = cropTo16v9(bitmap)
                }
                runOnUiThread {
                    if (loadedThumbnailVideoId == videoId) {
                        currentThumbnail = bitmap

                        // Extraer color dominante y enviarlo al servicio si está enlazado
                        try {
                            if (bitmap != null && isBound && playbackService != null) {
                                val dominantColor = colorExtractor.extractDominantColor(bitmap)
                                playbackService?.setNotificationColor(dominantColor)
                            }
                        } catch (e: Exception) {
                            // No bloquear si falla la extracción
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback a mqdefault si falla el de alta calidad
                try {
                    val url = java.net.URL("https://img.youtube.com/vi/$videoId/mqdefault.jpg")
                    val connection = url.openConnection()
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    val input = connection.getInputStream()
                    var bitmap = android.graphics.BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        bitmap = cropTo16v9(bitmap)
                    }
                    runOnUiThread {
                        if (loadedThumbnailVideoId == videoId) {
                            currentThumbnail = bitmap

                            // Extraer color dominante y enviarlo al servicio si está enlazado
                            try {
                                if (bitmap != null && isBound && playbackService != null) {
                                    val dominantColor = colorExtractor.extractDominantColor(bitmap)
                                    playbackService?.setNotificationColor(dominantColor)
                                }
                            } catch (e2: Exception) {
                                // Ignorar
                            }
                        }
                    }
                } catch (e2: Exception) {
                    // Ignorar
                }
            }
        }.start()
    }

    private fun cropTo16v9(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetHeight = (width * 9) / 16
        if (targetHeight < height) {
            val yOffset = (height - targetHeight) / 2
            try {
                return Bitmap.createBitmap(bitmap, 0, yOffset, width, targetHeight)
            } catch (e: Exception) {
                // Fallback
            }
        }
        return bitmap
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
            // Cancelar el pulso automático si está en ejecución
            shieldPulseHandler.removeCallbacks(shieldPulseRunnable)
            isPulseActive = false

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

    private fun triggerShieldPulse(videoId: String?, shouldReload: Boolean) {
        if (videoId == null || videoId == lastPulseVideoId) return
        lastPulseVideoId = videoId

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val globalShieldActive = prefs.getBoolean("shield_active", true)
        if (!globalShieldActive) return

        android.util.Log.d("Shield", "Iniciando pulso del escudo para videoId: $videoId (shouldReload: $shouldReload)")

        shieldPulseHandler.removeCallbacks(shieldPulseRunnable)

        isShieldActive = false
        isPulseActive = true
        updateShieldUI()

        if (shouldReload) {
            webView.post {
                webView.reload()
            }
        }

        shieldPulseHandler.postDelayed(shieldPulseRunnable, 100)
    }

    private fun setupWebView() {
        // Limpiar base de datos, local storage y Service Workers registrados al iniciar
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)

        // Habilitar aceleración por hardware para renderizado fluido en GPU
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
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

                val activeUrl = currentActiveUrl
                val isWatchOrShort = activeUrl.contains("watch?v=") || activeUrl.contains("/shorts/")

                if (isShieldActive && isDynamicShieldActive && isWatchOrShort && AdBlocker.isAd(url)) {
                    return WebResourceResponse(
                        "application/json",
                        "utf-8",
                        ByteArrayInputStream("{}".toByteArray())
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val cleanUrl = url ?: ""
                currentActiveUrl = cleanUrl
                updateMediaPlaybackGestureSetting(cleanUrl)
                injectVisibilityOverride()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                splashOverlay.visibility = View.GONE
                val cleanUrl = url ?: ""
                currentActiveUrl = cleanUrl
                updateMediaPlaybackGestureSetting(cleanUrl)
                injectVisibilityOverride()
                val isWatchOrShort = cleanUrl.contains("watch?v=") || cleanUrl.contains("/shorts/")
                if (isShieldActive && isWatchOrShort && isDynamicShieldActive) {
                    injectAdBlockScript()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val cleanUrl = url ?: ""
                currentActiveUrl = cleanUrl
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 30) {
                    injectVisibilityOverride()
                    val activeUrl = webView.url ?: ""
                    val isWatchOrShort = activeUrl.contains("watch?v=") || activeUrl.contains("/shorts/")
                    if (isShieldActive && isWatchOrShort && isDynamicShieldActive) {
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
        navBarCard.visibility = View.GONE

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
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsScript, null)
        injectVisibilityOverride()
    }

    private fun injectVisibilityOverride() {
        val js = """
            (function() {
                // Estilos específicos de feed removidos para permitir previsualizaciones

                // 0. Interceptar ytcfg y JSON.parse para desactivar/bloquear anuncios nativos
                if (!window.shieldYtcfgOverridden) {
                    window.shieldYtcfgOverridden = true;
                    var applyAdBlockFlags = function(obj) {
                        if (obj && typeof obj === 'object') {
                            if (obj.ad_placements || obj.adPlacements) {
                                obj.ad_placements = [];
                                obj.adPlacements = [];
                            }
                            if (obj.ad_slots || obj.adSlots) {
                                obj.ad_slots = [];
                                obj.adSlots = [];
                            }
                            if (obj.EXPERIMENT_FLAGS) {
                                obj.EXPERIMENT_FLAGS.web_enable_ad_signals = false;
                                obj.EXPERIMENT_FLAGS.web_ad_signals = false;
                                obj.EXPERIMENT_FLAGS.web_enable_ad_placement_config = false;
                                obj.EXPERIMENT_FLAGS.web_enable_ad_slots = false;
                                obj.EXPERIMENT_FLAGS.web_enable_midrolls = false;
                                obj.EXPERIMENT_FLAGS.web_enable_pre_rolls = false;
                                obj.EXPERIMENT_FLAGS.external_play_ads = false;
                            }
                        }
                    };
                    if (window.ytcfg) {
                        if (window.ytcfg.set) {
                            var originalSet = window.ytcfg.set;
                            window.ytcfg.set = function(payload) {
                                applyAdBlockFlags(payload);
                                originalSet.apply(this, arguments);
                            };
                        }
                        if (window.ytcfg.configs) {
                            window.ytcfg.configs.forEach(applyAdBlockFlags);
                        }
                    } else {
                        var ytcfgVal;
                        Object.defineProperty(window, 'ytcfg', {
                            get: function() { return ytcfgVal; },
                            set: function(val) {
                                ytcfgVal = val;
                                if (ytcfgVal && ytcfgVal.set) {
                                    var orig = ytcfgVal.set;
                                    ytcfgVal.set = function(payload) {
                                        applyAdBlockFlags(payload);
                                        orig.apply(this, arguments);
                                    };
                                }
                            },
                            configurable: true
                        });
                    }
                }

                if (!window.shieldJsonOverridden) {
                    window.shieldJsonOverridden = true;
                    var originalParse = JSON.parse;
                    JSON.parse = function(text) {
                        var obj = originalParse(text);
                        try {
                            if (obj && typeof obj === 'object') {
                                if (obj.adPlacements) {
                                    obj.adPlacements = [];
                                }
                                if (obj.playerAds) {
                                    obj.playerAds = [];
                                }
                                if (obj.adSlots) {
                                    obj.adSlots = [];
                                }
                                if (obj.playerConfig && obj.playerConfig.adPlacementConfig) {
                                    obj.playerConfig.adPlacementConfig = {};
                                }
                            }
                        } catch(e) {}
                        return obj;
                    };
                }

                // Bloqueadores de recursos multimedia del feed removidos para permitir previsualizaciones

                // Interceptar Fetch para youtubei/v1/player
                if (!window.shieldFetchOverridden) {
                    window.shieldFetchOverridden = true;
                    const originalFetch = window.fetch;
                    window.fetch = async function(...args) {
                        let urlStr = '';
                        if (typeof args[0] === 'string') {
                            urlStr = args[0];
                        } else if (args[0] && typeof args[0] === 'object' && args[0].url) {
                            urlStr = args[0].url;
                        }
                        const isPlayerApi = urlStr.includes('youtubei/v1/player');
                        if (isPlayerApi) {
                            try {
                                const response = await originalFetch.apply(this, args);
                                const text = await response.text();
                                let json = JSON.parse(text);
                                if (json && typeof json === 'object') {
                                    if (json.adPlacements) json.adPlacements = [];
                                    if (json.playerAds) json.playerAds = [];
                                    if (json.adSlots) json.adSlots = [];
                                    if (json.playerConfig && json.playerConfig.adPlacementConfig) {
                                        json.playerConfig.adPlacementConfig = {};
                                    }
                                }
                                const newResponse = new Response(JSON.stringify(json), {
                                    status: response.status,
                                    statusText: response.statusText,
                                    headers: response.headers
                                });
                                Object.defineProperty(newResponse, 'url', { value: response.url, writable: false, configurable: true });
                                Object.defineProperty(newResponse, 'redirected', { value: response.redirected, writable: false, configurable: true });
                                return newResponse;
                            } catch (e) {
                                console.error("Shield: Fetch player override error", e);
                            }
                        }
                        return originalFetch.apply(this, args);
                    };
                }

                // Interceptar Response.prototype.json para asegurar limpieza si se llama directamente
                if (!window.shieldResponseJsonOverridden) {
                    window.shieldResponseJsonOverridden = true;
                    const originalResponseJson = Response.prototype.json;
                    Response.prototype.json = async function() {
                        const json = await originalResponseJson.apply(this, arguments);
                        if (this.url && this.url.includes('youtubei/v1/player')) {
                            try {
                                if (json && typeof json === 'object') {
                                    if (json.adPlacements) json.adPlacements = [];
                                    if (json.playerAds) json.playerAds = [];
                                    if (json.adSlots) json.adSlots = [];
                                    if (json.playerConfig && json.playerConfig.adPlacementConfig) {
                                        json.playerConfig.adPlacementConfig = {};
                                    }
                                }
                            } catch (e) {
                                console.error("Shield: Response.json override error", e);
                            }
                        }
                        return json;
                    };
                }

                // Interceptar XMLHttpRequest para youtubei/v1/player
                if (!window.shieldXhrOverridden) {
                    window.shieldXhrOverridden = true;
                    const originalOpen = XMLHttpRequest.prototype.open;
                    const originalSend = XMLHttpRequest.prototype.send;
                    
                    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                        this._url = url;
                        return originalOpen.apply(this, [method, url, ...rest]);
                    };
                    
                    XMLHttpRequest.prototype.send = function(...args) {
                        const self = this;
                        const isPlayerApi = typeof this._url === 'string' && this._url.includes('youtubei/v1/player');
                        if (isPlayerApi) {
                            const originalOnReadyStateChange = this.onreadystatechange;
                            
                            var modifyResponse = function() {
                                try {
                                    let responseType = self.responseType;
                                    if (responseType === 'json') {
                                        let json = self.response;
                                        if (json && typeof json === 'object') {
                                            if (json.adPlacements) json.adPlacements = [];
                                            if (json.playerAds) json.playerAds = [];
                                            if (json.adSlots) json.adSlots = [];
                                            if (json.playerConfig && json.playerConfig.adPlacementConfig) {
                                                json.playerConfig.adPlacementConfig = {};
                                            }
                                            Object.defineProperty(self, 'response', { value: json, writable: true, configurable: true });
                                        }
                                    } else if (responseType === '' || responseType === 'text') {
                                        let modifiedResponseText = self.responseText;
                                        let json = JSON.parse(modifiedResponseText);
                                        if (json && typeof json === 'object') {
                                            if (json.adPlacements) json.adPlacements = [];
                                            if (json.playerAds) json.playerAds = [];
                                            if (json.adSlots) json.adSlots = [];
                                            if (json.playerConfig && json.playerConfig.adPlacementConfig) {
                                                json.playerConfig.adPlacementConfig = {};
                                            }
                                            modifiedResponseText = JSON.stringify(json);
                                            Object.defineProperty(self, 'responseText', { value: modifiedResponseText, writable: true, configurable: true });
                                            Object.defineProperty(self, 'response', { value: modifiedResponseText, writable: true, configurable: true });
                                        }
                                    }
                                } catch (e) {
                                    console.error("Shield: XHR player override error", e);
                                }
                            };

                            this.onreadystatechange = function() {
                                if (self.readyState === 4 && self.status === 200) {
                                    modifyResponse();
                                }
                                if (originalOnReadyStateChange) {
                                    originalOnReadyStateChange.apply(this, arguments);
                                }
                            };
                            
                            self.addEventListener('load', function() {
                                if (self.readyState === 4 && self.status === 200) {
                                    modifyResponse();
                                }
                            }, true);
                            
                            self.addEventListener('readystatechange', function() {
                                if (self.readyState === 4 && self.status === 200) {
                                    modifyResponse();
                                }
                            }, true);
                        }
                        return originalSend.apply(this, args);
                    };
                }

                // Sincronizar estado de bucle desde Android Kotlin
                window.shieldLoopEnabled = $isLoopEnabled;
                var v = document.querySelector('video');
                if (v) {
                    v.loop = $isLoopEnabled;
                }
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (player) {
                    try {
                        if (typeof player.setLoop === 'function') player.setLoop($isLoopEnabled);
                    } catch(e){}
                    try {
                        if (typeof player.setOption === 'function') player.setOption('loop', $isLoopEnabled);
                    } catch(e){}
                    try {
                        if (typeof player.setOption === 'function') player.setOption('playlist', 'loop', $isLoopEnabled);
                    } catch(e){}
                }

                // 1. Unregister and block Service Workers
                if (typeof navigator.serviceWorker !== 'undefined') {
                    try {
                        navigator.serviceWorker.getRegistrations().then(function(registrations) {
                            for (let registration of registrations) {
                                registration.unregister();
                                console.log('Shield: Unregistered ServiceWorker:', registration);
                            }
                        }).catch(function(e) {});

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
                        window.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                        document.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                        
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



                // 6. Desmutear automáticamente lo antes posible (solo en watch/shorts)
                const isWatchOrShort = window.location.href.includes('watch?v=') || window.location.href.includes('/shorts/');
                if (isWatchOrShort) {
                    var video = document.querySelector('video');
                    if (video && video.muted) {
                        video.muted = false;
                        video.volume = 1.0;
                        console.log('Shield: Early video auto-unmuted.');
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onStart() {
        super.onStart()
        adBlockHandler.post(adBlockRunnable)
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("window.shieldIgnorePause = false;", null)
    }

    override fun onPause() {
        super.onPause()
        webView.evaluateJavascript("window.shieldIgnorePause = true;", null)
    }

    override fun onStop() {
        super.onStop()
        adBlockHandler.removeCallbacks(adBlockRunnable)
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
        isActivityRunning = false
        try {
            unregisterReceiver(videoChangeReceiver)
        } catch (e: Exception) {}

        adBlockHandler.removeCallbacks(adBlockRunnable)
        adBlockHandler.removeCallbacks(playbackMonitorRunnable)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun updateMediaPlaybackGestureSetting(url: String) {
        val isWatchOrShort = url.contains("watch?v=") || url.contains("/shorts/")
        runOnUiThread {
            if (isWatchOrShort) {
                if (webView.settings.mediaPlaybackRequiresUserGesture) {
                    webView.settings.mediaPlaybackRequiresUserGesture = false
                    android.util.Log.d("Shield", "mediaPlaybackRequiresUserGesture set to false (watch/shorts page)")
                }
            } else {
                if (!webView.settings.mediaPlaybackRequiresUserGesture) {
                    webView.settings.mediaPlaybackRequiresUserGesture = true
                    android.util.Log.d("Shield", "mediaPlaybackRequiresUserGesture set to true (feed page)")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val url = intent.getStringExtra("video_url")
        if (!url.isNullOrEmpty()) {
            changeVideo(url)
        }
    }

    private fun changeVideo(url: String) {
        val fullUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> "https://m.youtube.com$url"
            else -> "https://m.youtube.com/$url"
        }
        
        // Actualizar el URL reproduciéndose actualmente de inmediato para actualizar el highlight en el widget al instante
        PlaylistRepository.currentPlayingUrl = fullUrl
        val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(this)
        val thisWidget = android.content.ComponentName(this, PlaylistWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
        appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widgetListView)

        PlaylistRepository.isPlaying = false
        PlaylistWidgetProvider.refreshPlaylist(this)

        val videoId = getVideoId(fullUrl)
        triggerShieldPulse(videoId, shouldReload = false)

        webView.post {
            webView.loadUrl(fullUrl)
        }
    }

    private fun getVideoId(url: String?): String? {
        if (url == null) return null
        try {
            val parsedUri = if (url.startsWith("http://") || url.startsWith("https://")) {
                android.net.Uri.parse(url)
            } else {
                android.net.Uri.parse("https://m.youtube.com" + if (url.startsWith("/")) url else "/$url")
            }
            val v = parsedUri.getQueryParameter("v")
            if (!v.isNullOrEmpty()) {
                return v
            }
            val path = parsedUri.path
            if (path != null && path.contains("/shorts/")) {
                return parsedUri.lastPathSegment
            }
            if (url.contains("watch?v=")) {
                val parts = url.split("watch?v=")
                if (parts.size > 1) {
                    return parts[1].split("&")[0]
                }
            }
            if (url.contains("/shorts/")) {
                val parts = url.split("/shorts/")
                if (parts.size > 1) {
                    return parts[1].split("?")[0].split("/")[0]
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }
        return null
    }
}