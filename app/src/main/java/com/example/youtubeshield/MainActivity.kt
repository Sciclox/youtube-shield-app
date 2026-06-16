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
import android.widget.ProgressBar
import android.widget.VideoView
import android.os.PowerManager
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
    private lateinit var transitionOverlay: FrameLayout
    private lateinit var transitionProgressBar: ProgressBar
    private lateinit var transitionVideoView: VideoView
    private lateinit var splashVideoView: VideoView
    private lateinit var btnShield: ImageButton

    private var isShieldActive = true
    private var isDynamicShieldActive = false
    private var isLoopEnabled = false
    private var isNavigatingNextOnEnd = false
    private val adBlockHandler = Handler(Looper.getMainLooper())

    // Variables de control para el pulso automático del escudo
    private var isPulseActive = false
    private var isPulseOverlayPendingHide = false
    private var lastPulseVideoId: String? = null
    private var lastTransitionPosition = 0
    private val shieldPulseHandler = Handler(Looper.getMainLooper())
    private val shieldPulseRunnable = Runnable {
        isShieldActive = true
        isPulseActive = false
        updateShieldUI()
        android.util.Log.d("Shield", "Escudo reactivado automáticamente después del pulso")
    }
    // Runnable de seguridad: oculta el overlay de transición tras un timeout máximo
    private val pulseOverlaySafetyRunnable = Runnable {
        if (isPulseOverlayPendingHide) {
            isPulseOverlayPendingHide = false
            hideTransitionOverlay()
            android.util.Log.d("Shield", "Overlay de transición ocultado por timeout de seguridad")
        }
    }

    // Wake lock para mantener CPU activa durante reproducción en segundo plano
    private var wakeLock: PowerManager.WakeLock? = null

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

    // Runnable para inyección periódica de scripts de bloqueo (reducido a 8 segundos para evitar sobrecarga)
    private val adBlockRunnable = object : Runnable {
        override fun run() {
            if (isShieldActive && isDynamicShieldActive) {
                val currentUrl = webView.url ?: ""
                val isWatchOrShort = currentUrl.contains("watch?v=") || currentUrl.contains("/shorts/")
                if (isWatchOrShort) {
                    injectAdBlockScript()
                }
            }
            adBlockHandler.postDelayed(this, 8000) // Re-inyecta cada 8 segundos
        }
    }

    private var playlistQueryCounter = 0

    // Runnable para monitorear el estado del video de YouTube y actualizar la notificación
    private val playbackMonitorRunnable = object : Runnable {
        override fun run() {
            queryVideoState()
            
            // Cada 15 segundos ejecutamos una consulta pesada de la playlist/recomendaciones como respaldo
            playlistQueryCounter++
            if (playlistQueryCounter >= 15) {
                playlistQueryCounter = 0
                queryPlaylistAndRecommendations()
            }
            
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

        // Inicializar base de datos de AdBlocker en segundo plano para no bloquear el hilo de UI
        Thread {
            AdBlocker.init(this)
        }.start()

        // Inicializar extractor de colores dinámicos
        colorExtractor = DynamicColorExtractor()

        // Inicializar Wake Lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YouTubeShield:PlaybackWakeLock")

        // Cargar preferencia del escudo
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        isShieldActive = prefs.getBoolean("shield_active", true)

        // Vincular vistas
        webView = findViewById(R.id.webView)
        navBarCard = findViewById(R.id.navBarCard)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        splashOverlay = findViewById(R.id.splashOverlay)
        transitionOverlay = findViewById(R.id.transitionOverlay)
        transitionProgressBar = findViewById(R.id.transitionProgressBar)
        transitionVideoView = findViewById(R.id.transitionVideoView)
        splashVideoView = findViewById(R.id.splashVideoView)
        btnShield = findViewById(R.id.btnShield)

        // Configurar video de transición desde res/raw/transicion.mp4
        try {
            val videoUri = Uri.parse("android.resource://" + packageName + "/" + R.raw.transicion)
            transitionVideoView.setVideoURI(videoUri)
            transitionVideoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = true
                mediaPlayer.setVolume(0f, 0f)
            }
            transitionVideoView.setOnErrorListener { _, _, _ ->
                true // Prevenir el diálogo nativo de error si falla la reproducción
            }
        } catch (e: Exception) {
            // Evitar bloqueos si falla la carga
        }

        // Configurar video del splash desde res/raw/splash.mp4
        try {
            val splashLogo = findViewById<ImageView>(R.id.splashLogo)
            val videoUri = Uri.parse("android.resource://" + packageName + "/" + R.raw.splash)
            splashVideoView.setVideoURI(videoUri)
            splashVideoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = true
                mediaPlayer.setVolume(0f, 0f)
                splashVideoView.start()
            }
            splashVideoView.setOnErrorListener { _, _, _ ->
                splashLogo?.visibility = View.VISIBLE
                true
            }
        } catch (e: Exception) {
            findViewById<ImageView>(R.id.splashLogo)?.visibility = View.VISIBLE
        }

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

    private fun setupServiceCallback() {
        playbackService?.setPlaybackCallback(object : MediaPlaybackService.PlaybackCallback {
            override fun onPlay() {
                runOnUiThread {
                    webView.evaluateJavascript("document.querySelector('video').play()", null)
                }
            }

            override fun onPause() {
                runOnUiThread {
                    webView.evaluateJavascript(
                        "(function() { if (typeof window.shieldRegisterIntentionalPause === 'function') window.shieldRegisterIntentionalPause(); var v = document.querySelector('video'); if(v) v.pause(); })()",
                        null
                    )
                }
            }

            override fun onNext() {
                runOnUiThread {
                    navigatePlaylist(next = true)
                }
            }

            override fun onPrevious() {
                runOnUiThread {
                    navigatePlaylist(next = false)
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

    private fun acquireWakeLock() {
        if (wakeLock != null && !wakeLock!!.isHeld) {
            wakeLock!!.acquire(10 * 60 * 1000L) // 10 min max para evitar agotar batería
            android.util.Log.d("Shield", "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
            android.util.Log.d("Shield", "WakeLock released")
        }
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
                if (loadedThumbnailVideoId != null) {
                    loadedThumbnailVideoId = null
                    currentThumbnail = null
                    resetDynamicColorsToDefault()
                }
            }
        } else {
            if (lastVideoId != null || loadedThumbnailVideoId != null) {
                lastVideoId = null
                loadedThumbnailVideoId = null
                currentThumbnail = null
                resetDynamicColorsToDefault()
                playbackService?.updateMetadata("YouTube Shield", false, isLoopEnabled, null, 0L, 0L)
            }
        }

        val js = """
            (function() {
                var video = document.querySelector('video');
                if (!video) return { title: document.title || "YouTube Shield", isPlaying: false, ended: false, position: 0, duration: 0 };
                
                var title = document.title;
                title = title.replace(/^\(\d+\)\s+/, '');
                if (title.endsWith(' - YouTube')) {
                    title = title.substring(0, title.length - 10);
                }
                
                var posMs = Math.floor(video.currentTime * 1000);
                var durMs = isNaN(video.duration) ? 0 : Math.floor(video.duration * 1000);
                var isVideoEnded = video.ended || (posMs > 0 && durMs > 0 && posMs >= durMs - 1500);
                
                return {
                    title: title || "YouTube Video",
                    isPlaying: !video.paused && !isVideoEnded,
                    ended: isVideoEnded,
                    position: posMs,
                    duration: durMs
                };
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"null\"") {
                try {
                    val json = org.json.JSONObject(result)
                    val title = json.optString("title", "YouTube Shield")
                    val isPlaying = json.optBoolean("isPlaying", false)
                    val ended = json.optBoolean("ended", false)
                    val position = json.optLong("position", 0L)
                    val duration = json.optLong("duration", 0L)
                    
                    if (ended && !isLoopEnabled && !isNavigatingNextOnEnd) {
                        isNavigatingNextOnEnd = true
                        android.util.Log.d("Shield", "Video finalizado. Navegando al siguiente de la lista.")
                        navigatePlaylist(next = true)
                    }
                    
                    val oldVideoId = getVideoId(PlaylistRepository.currentPlayingUrl)
                    val newVideoId = getVideoId(currentUrl)
                    val urlChanged = oldVideoId != newVideoId
                    
                    if (urlChanged || PlaylistRepository.isPlaying != isPlaying) {
                        PlaylistRepository.currentPlayingUrl = currentUrl
                        PlaylistRepository.isPlaying = isPlaying
                        PlaylistWidgetProvider.refreshPlaylist(this@MainActivity)
                        
                        // Si cambió la canción o video, disparamos una consulta de la playlist
                        if (urlChanged) {
                            queryPlaylistAndRecommendations()
                        }
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

                    // Gestionar Wake Lock según el estado de reproducción
                    if (isPlaying) {
                        acquireWakeLock()
                        // Si el overlay de transición está pendiente de ocultarse y el video ya reproduce, ocultarlo
                        if (isPulseOverlayPendingHide) {
                            isPulseOverlayPendingHide = false
                            shieldPulseHandler.removeCallbacks(pulseOverlaySafetyRunnable)
                            hideTransitionOverlay()
                            android.util.Log.d("Shield", "Overlay de transición ocultado: video reproduciéndose")
                        }
                    } else {
                        releaseWakeLock()
                    }
                } catch (e: Exception) {
                    // Ignorar errores de parsing
                }
            }
        }
    }

    private fun queryPlaylistAndRecommendations() {
        val currentUrl = webView.url ?: ""
        val isWatchOrShort = currentUrl.contains("watch?v=") || currentUrl.contains("/shorts/")
        if (!isWatchOrShort) return

        val js = """
            (function() {
                var playlist = [];
                var seenUrls = new Set();

                // 1. Intentar extraer de la playlist/mix activa de YouTube
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

                // 2. Si no hay mix/playlist activa, extraer recomendaciones
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

                // 3. Fallback final
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

                return playlist.slice(0, 20);
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            if (result != null && result != "null" && result != "\"null\"") {
                try {
                    val playlistArray = org.json.JSONArray(result)
                    val newPlaylist = ArrayList<PlaylistRepository.PlaylistItem>()
                    for (i in 0 until playlistArray.length()) {
                        val itemObj = playlistArray.getJSONObject(i)
                        val itemTitle = itemObj.optString("title", "")
                        val itemUrl = itemObj.optString("url", "")
                        val itemChannel = itemObj.optString("channel", "")
                        if (itemTitle.isNotEmpty() && itemUrl.isNotEmpty()) {
                            newPlaylist.add(PlaylistRepository.PlaylistItem(itemTitle, itemUrl, itemChannel))
                        }
                    }

                    val oldUrls = PlaylistRepository.playlist.map { it.url }
                    val newUrls = newPlaylist.map { it.url }
                    
                    val shouldKeepOldPlaylist = newPlaylist.isEmpty() && isWatchOrShort
                    val playlistChanged = !shouldKeepOldPlaylist && (oldUrls != newUrls)
                    
                    if (playlistChanged) {
                        if (!shouldKeepOldPlaylist) {
                            PlaylistRepository.playlist = newPlaylist
                        }
                        PlaylistWidgetProvider.refreshPlaylist(this@MainActivity)
                    }
                } catch (e: java.lang.Exception) {
                    android.util.Log.e("Shield", "Error parsing playlist", e)
                }
            }
        }
    }


    private fun fetchThumbnail(videoId: String) {
        Thread {
            val urls = listOf(
                "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                "https://img.youtube.com/vi/$videoId/mqdefault.jpg",
                "https://img.youtube.com/vi/$videoId/default.jpg"
            )
            var bitmap: android.graphics.Bitmap? = null
            for (urlStr in urls) {
                if (bitmap != null) break
                try {
                    val url = java.net.URL(urlStr)
                    val connection = url.openConnection()
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    val input = connection.getInputStream()
                    val decoded = android.graphics.BitmapFactory.decodeStream(input)
                    input.close()
                    if (decoded != null) {
                        bitmap = cropTo16v9(decoded)
                    }
                } catch (e: Exception) {
                    // Intentar siguiente URL
                }
            }
            val finalBitmap = bitmap
            runOnUiThread {
                if (loadedThumbnailVideoId == videoId) {
                    currentThumbnail = finalBitmap
                    try {
                        if (finalBitmap != null) {
                            val palette = colorExtractor.extractColorPalette(finalBitmap)
                            applyDynamicColors(palette)
                            if (isBound && playbackService != null) {
                                playbackService?.updateThumbnail(finalBitmap)
                            }
                        } else {
                            resetDynamicColorsToDefault()
                        }
                    } catch (e: Exception) {
                        // No bloquear si falla la extracción
                    }
                }
            }
        }.start()
    }

    private fun navigatePlaylist(next: Boolean) {
        val playlist = PlaylistRepository.playlist
        val currentUrl = PlaylistRepository.currentPlayingUrl
        val currentId = getVideoId(currentUrl)

        // Intentar navegar dentro de PlaylistRepository.playlist
        if (currentId != null && playlist.isNotEmpty()) {
            val currentIndex = playlist.indexOfFirst { item -> getVideoId(item.url) == currentId }
            val targetIndex = if (next) currentIndex + 1 else currentIndex - 1
            if (currentIndex >= 0 && targetIndex in playlist.indices) {
                val targetItem = playlist[targetIndex]
                changeVideo(targetItem.url)
                return
            }
        }

        if (next) {
            // Extraer URL del "Up Next" o primera recomendación desde la página
            val js = """
                (function() {
                    // 1. Autoplay / Up Next
                    var el = document.querySelector('ytm-autoplay-renderer a[href*="watch"], [class*="autoplay"] a[href*="watch"], ytm-compact-autoplay-renderer a[href*="watch"]');
                    if (el) return el.href || el.getAttribute('href') || '';
                    // 2. Primera recomendación normal
                    el = document.querySelector('ytm-compact-video-renderer a[href*="watch"], ytm-video-with-context-renderer a[href*="watch"], .compact-media-item a[href*="watch"]');
                    if (el) return el.href || el.getAttribute('href') || '';
                    // 3. Cualquier enlace a watch (evitando list= que son mixes/playlists)
                    var links = document.querySelectorAll('a[href*="/watch"]');
                    for (var i = 0; i < links.length; i++) {
                        var href = links[i].href || links[i].getAttribute('href') || '';
                        if (href && !href.includes('list=')) return href;
                    }
                    return '';
                })()
            """.trimIndent()
            webView.evaluateJavascript(js) { urlResult ->
                if (urlResult != null && urlResult != "null" && urlResult != "\"null\"") {
                    val cleanUrl = urlResult.trim('"')
                    if (cleanUrl.isNotEmpty()) {
                        changeVideo(cleanUrl)
                    }
                }
            }
        } else {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
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
        shieldPulseHandler.removeCallbacks(pulseOverlaySafetyRunnable)

        isShieldActive = false
        isPulseActive = true
        updateShieldUI()

        if (shouldReload) {
            // Marcar que el overlay debe permanecer visible hasta que el video reproduzca
            isPulseOverlayPendingHide = true

            runOnUiThread {
                // Mostrar transitionOverlay para ocultar el destello de la recarga
                transitionOverlay.alpha = 1f
                transitionOverlay.visibility = View.VISIBLE
                try {
                    transitionVideoView.seekTo(lastTransitionPosition)
                    transitionVideoView.start()
                } catch (e: Exception) {
                    // Evitar crashes
                }
                webView.reload()
            }

            // Timeout de seguridad: ocultar overlay después de 7 segundos como máximo
            shieldPulseHandler.postDelayed(pulseOverlaySafetyRunnable, 7000)
        }

        shieldPulseHandler.postDelayed(shieldPulseRunnable, 150)
    }

    private fun hideTransitionOverlay() {
        runOnUiThread {
            if (transitionOverlay.visibility == View.VISIBLE) {
                transitionOverlay.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction {
                        transitionOverlay.visibility = View.GONE
                        transitionOverlay.alpha = 1f
                        try {
                            lastTransitionPosition = transitionVideoView.currentPosition
                            transitionVideoView.pause()
                        } catch (e: Exception) {
                            // Evitar crashes
                        }
                    }
            }
        }
    }

    private fun hideOverlaysWithFade() {
        runOnUiThread {
            if (splashOverlay.visibility == View.VISIBLE) {
                splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(350)
                    .withEndAction {
                        splashOverlay.visibility = View.GONE
                        splashOverlay.alpha = 1f
                        try {
                            splashVideoView.pause()
                        } catch (e: Exception) {
                            // Evitar crashes
                        }
                    }
            }
            // Solo ocultar el overlay de transición si NO hay un pulso pendiente de que el video reproduzca
            if (!isPulseOverlayPendingHide) {
                hideTransitionOverlay()
            }
        }
    }

    private fun applyDynamicColors(palette: DynamicColorExtractor.ColorPalette) {
        runOnUiThread {
            // 1. Aplicar degradado dinámico al background de transitionOverlay
            val startColor = palette.darkVibrant
            val endColor = palette.darkMuted
            
            val gradientDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(startColor, endColor)
            )
            transitionOverlay.background = gradientDrawable

            // 2. Aplicar color dinámico al ProgressBar (spinner)
            val vibrantColor = palette.vibrant
            transitionProgressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(vibrantColor)

            // 3. Aplicar color dinámico a la barra de estado (Status Bar)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                window.statusBarColor = startColor
            }
        }
    }

    private fun resetDynamicColorsToDefault() {
        runOnUiThread {
            // Fondo oscuro por defecto
            val defaultColor = android.graphics.Color.parseColor("#121212")
            val gradientDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(defaultColor, defaultColor)
            )
            transitionOverlay.background = gradientDrawable

            // Color del spinner por defecto (verde del escudo)
            val defaultSpinnerColor = androidx.core.content.ContextCompat.getColor(this, R.color.shield_on)
            transitionProgressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(defaultSpinnerColor)

            // Color de la barra de estado por defecto
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                window.statusBarColor = defaultColor
            }
        }
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
                // Reset dynamic shield when navigation starts; re-enabled on finish
                val isWatchOrShort = cleanUrl.contains("watch?v=") || cleanUrl.contains("/shorts/")
                if (!isWatchOrShort) {
                    isDynamicShieldActive = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isNavigatingNextOnEnd = false
                hideOverlaysWithFade()
                val cleanUrl = url ?: ""
                currentActiveUrl = cleanUrl
                updateMediaPlaybackGestureSetting(cleanUrl)
                injectVisibilityOverride()
                val isWatchOrShort = cleanUrl.contains("watch?v=") || cleanUrl.contains("/shorts/")
                // Activar bloqueo de red inmediatamente para páginas de video
                if (isShieldActive && isWatchOrShort) {
                    isDynamicShieldActive = true
                    injectAdBlockScript()
                }
                // Inyectar listener de navegación SPA de YouTube
                injectSpaNavigationListener()
            }
 
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                isNavigatingNextOnEnd = false
                val cleanUrl = url ?: ""
                currentActiveUrl = cleanUrl
                
                // Si el escudo está activo y detectamos navegación a un video o short que es nuevo,
                // disparamos el pulso del escudo (el cual mostrará el transitionOverlay y recargará el WebView).
                val isWatchOrShort = cleanUrl.contains("watch?v=") || cleanUrl.contains("/shorts/")
                val newVideoId = getVideoId(cleanUrl)
                if (isShieldActive && isWatchOrShort && newVideoId != null && newVideoId != lastPulseVideoId) {
                    triggerShieldPulse(newVideoId, shouldReload = true)
                }
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
            ytm-promoted-sparkles-web-renderer, 
            ytd-promoted-sparkles-web-renderer, 
            ytm-promoted-video-renderer, 
            ytd-promoted-video-renderer, 
            ytm-merchandise-item-renderer, 
            ytd-merchandise-shelf-renderer, 
            ytm-merchandise-shelf-renderer, 
            ytm-video-ad-card-renderer, 
            ytd-video-ad-card-renderer, 
            ytm-compact-promoted-video-renderer, 
            ytd-compact-promoted-video-renderer, 
            ytm-promoted-search-renderer, 
            ytd-search-pyv-renderer, 
            ytm-promoted-sparkles-text-search-renderer, 
            ytd-promoted-sparkles-text-search-renderer, 
            ytm-mealbar-promo-renderer, 
            ytd-mealbar-promo-renderer, 
            ytm-setting-ads-enabled-renderer, 
            .ad-container, 
            .ad-div, 
            .video-ads, 
            .ytp-ad-module, 
            .ytp-ad-overlay-container, 
            .ytp-ad-image-overlay, 
            .ytp-ad-text-overlay, 
            .companion-ad-container, 
            .ad-showing,
            .ad-interrupting,
            .ytp-ad-player-overlay,
            .ytp-ad-player-overlay-layout,
            .ytp-ad-progress,
            .ytp-ad-skip-button-container,
            .ytp-ad-preview-container,
            .ytp-ad-visit-advertiser-button,
            .ytp-ad-action-interrupt-slot,
            .ytp-ad-survey,
            .ytp-ad-simple-ad-badge,
            .ytp-ad-subtitles,
            .ytp-ad-user-indicator,
            .ytp-ad-block-list-item,
            .ytp-ad-message-overlay,
            .ytp-ad-badge,
            #player-ads,
            #masthead-ad,
            #ad_creative,
            #ad_im,
            #ad_dfp,
            #ad-banner,
            [class*="ad-container"]:not([class*="additional"]):not([class*="admin"]),
            [class*="sponsored"],
            [class*="merch-shelf"] { 
                display: none !important; 
                height: 0 !important;
                width: 0 !important;
                visibility: hidden !important;
            }
        """.trimIndent().replace("\n", " ")

        val jsScript = """
            (function() {
                if (window.shieldAdBlockInjected) return;
                window.shieldAdBlockInjected = true;

                // 1. Inyectar estilos CSS
                if (!document.getElementById('shield-adblock-styles')) {
                    var style = document.createElement('style');
                    style.id = 'shield-adblock-styles';
                    style.type = 'text/css';
                    style.innerHTML = '$cssRules';
                    document.head.appendChild(style);
                }
                // 2. MutationObserver desactivado para optimizar el rendimiento y evitar tirones de CPU
                /*
                if (!window.shieldAdObserver) {
                    var adSelectors = [
                        'ytd-companion-ad-renderer', 'ytm-companion-ad-renderer',
                        'ytd-display-ad-renderer', 'ytm-display-ad-renderer',
                        'ytm-promoted-item', 'ytm-banner-ad-renderer',
                        'ytm-inline-ad-renderer', 'ytm-carousel-ad-renderer',
                        'ytm-promoted-sparkles-web-renderer', 'ytd-promoted-sparkles-web-renderer',
                        'ytm-promoted-video-renderer', 'ytd-promoted-video-renderer',
                        '.video-ads', '.ytp-ad-module', '.ytp-ad-overlay-container',
                        '.ytp-ad-image-overlay', '.companion-ad-container',
                        '.ytp-ad-player-overlay',
                        '#player-ads', '#masthead-ad'
                    ];
                    window.shieldAdObserver = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            if (mutation.addedNodes) {
                                mutation.addedNodes.forEach(function(node) {
                                    if (node.nodeType === 1) {
                                        var match;
                                        for (var i = 0; i < adSelectors.length; i++) {
                                            try {
                                                if (node.matches && node.matches(adSelectors[i])) {
                                                    node.style.display = 'none';
                                                    node.style.height = '0';
                                                    node.style.width = '0';
                                                    node.style.visibility = 'hidden';
                                                    break;
                                                }
                                                var found = node.querySelectorAll ? node.querySelectorAll(adSelectors[i]) : [];
                                                for (var j = 0; j < found.length; j++) {
                                                    found[j].style.display = 'none';
                                                    found[j].style.height = '0';
                                                    found[j].style.width = '0';
                                                    found[j].style.visibility = 'hidden';
                                                }
                                            } catch(e) {}
                                        }
                                    }
                                });
                            }
                        });
                    });
                    window.shieldAdObserver.observe(document.body, { childList: true, subtree: true });
                }
                */

                // 3. Función para saltar un ad de video activo (se mantiene por si acaso, pero no se autoejecuta)
                window.shieldSkipAd = function() {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (!player) return;

                    // Intentar skip via API del player
                    try {
                        if (typeof player.skipAd === 'function') player.skipAd();
                    } catch(e) {}
                    try {
                        if (typeof player.cancelPlaybackAd === 'function') player.cancelPlaybackAd();
                    } catch(e) {}
                    try {
                        if (typeof player.finishAd === 'function') player.finishAd();
                    } catch(e) {}
                    try {
                        if (typeof player.setOption === 'function') player.setOption('ad', 'advancement', {advancement: 'skip'});
                    } catch(e) {}

                    // Click en botón de skip si existe
                    var skipBtns = document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot, button[class*="skip"]');
                    skipBtns.forEach(function(btn) { try { btn.click(); } catch(e) {} });

                    // Forzar salto del ad adelantando el video al final
                    var video = document.querySelector('video');
                    if (video && video.duration && isFinite(video.duration)) {
                        video.currentTime = video.duration;
                    }

                    // Remover clases de ad del player
                    player.classList.remove('ad-showing', 'ad-interrupting');

                    // Limpiar overlays de ad
                    var adOverlays = player.querySelectorAll('.ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, .ytp-ad-overlay-container, .video-ads, .ytp-ad-module');
                    adOverlays.forEach(function(el) {
                        el.style.display = 'none';
                        el.style.height = '0';
                        el.style.visibility = 'hidden';
                    });

                    console.log('Shield: Ad saltado via shieldSkipAd');
                };

                // 4. MutationObserver en el player desactivado para optimizar CPU
                /*
                if (!window.shieldPlayerObserver) {
                    var setupPlayerObserver = function() {
                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (!player) {
                            // Reintentar si el player aún no existe
                            setTimeout(setupPlayerObserver, 500);
                            return;
                        }

                        window.shieldPlayerObserver = new MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                if (mutation.type === 'attributes' && mutation.attributeName === 'class') {
                                    var target = mutation.target;
                                    if (target.classList.contains('ad-showing') || target.classList.contains('ad-interrupting')) {
                                        console.log('Shield: ad-showing detectado en player, saltando...');
                                        window.shieldSkipAd();
                                        // Reintentar skip después de un momento por si el primer intento falla
                                        setTimeout(window.shieldSkipAd, 300);
                                        setTimeout(window.shieldSkipAd, 800);
                                    }
                                }
                            });
                        });
                        window.shieldPlayerObserver.observe(player, { attributes: true, attributeFilter: ['class'] });
                        console.log('Shield: Player observer instalado');

                        // Verificar estado actual por si ya hay un ad
                        if (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')) {
                            window.shieldSkipAd();
                        }
                    };
                    setupPlayerObserver();
                }
                */

                // 5. Fallback: verificación periódica cada 500ms desactivada para conservar CPU
                /*
                if (!window.shieldAdCheckInterval) {
                    window.shieldAdCheckInterval = setInterval(function() {
                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
                            console.log('Shield: ad detectado via polling, saltando...');
                            window.shieldSkipAd();
                        }
                    }, 500);
                }
                */
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsScript, null)
        injectVisibilityOverride()
    }


    /**
     * Inyecta un listener para los eventos de navegación SPA de YouTube.
     * Cuando YouTube navega a un nuevo video sin recargar la página,
     * estos eventos disparan la limpieza de ads automáticamente.
     */
    private fun injectSpaNavigationListener() {
        val js = """
            (function() {
                if (window.shieldSpaListenerActive) return;
                window.shieldSpaListenerActive = true;
                var lastUrl = location.href;

                // Listener para eventos nativos de navegación SPA de YouTube
                var spaEvents = ['yt-navigate-finish', 'yt-page-data-updated', 'yt-navigate-start'];
                spaEvents.forEach(function(eventName) {
                    document.addEventListener(eventName, function() {
                        var newUrl = location.href;
                        if (newUrl !== lastUrl) {
                            lastUrl = newUrl;
                            if (newUrl.indexOf('watch?v=') !== -1 || newUrl.indexOf('/shorts/') !== -1) {
                                console.log('Shield: SPA navigation detectada via ' + eventName + ': ' + newUrl);
                                // Re-aplicar estilos de bloqueo CSS
                                var styleEl = document.getElementById('shield-adblock-styles');
                                if (styleEl) {
                                    document.head.removeChild(styleEl);
                                }
                                // Forzar re-creación del observer
                                if (window.shieldAdObserver) {
                                    window.shieldAdObserver.disconnect();
                                    window.shieldAdObserver = null;
                                }
                            }
                        }
                    });
                });

                // Fallback: MutationObserver en <title> para detectar cambios de video
                var titleObserver = new MutationObserver(function() {
                    var newUrl = location.href;
                    if (newUrl !== lastUrl) {
                        lastUrl = newUrl;
                        if (newUrl.indexOf('watch?v=') !== -1 || newUrl.indexOf('/shorts/') !== -1) {
                            console.log('Shield: SPA navigation detectada via title change: ' + newUrl);
                            if (window.shieldAdObserver) {
                                window.shieldAdObserver.disconnect();
                                window.shieldAdObserver = null;
                            }
                        }
                    }
                });
                var titleEl = document.querySelector('title');
                if (titleEl) {
                    titleObserver.observe(titleEl, { childList: true, characterData: true, subtree: true });
                }

                console.log('Shield: SPA navigation listener instalado');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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

                // 3. Sistema dinámico de prevención de pausas automáticas (JavaScript + Chromium Native)
                if (!window.shieldPausePreventionInstalled) {
                    window.shieldAllowPauseTimestamp = 0;
                    
                    window.shieldRegisterIntentionalPause = function() {
                        window.shieldAllowPauseTimestamp = Date.now();
                        console.log('Shield: Registro de pausa intencional a las ' + window.shieldAllowPauseTimestamp);
                    };
                    
                    // Escuchar interacciones en fase de captura para permitir pausado manual del usuario
                    var registerPauseOnInteraction = function() {
                        window.shieldRegisterIntentionalPause();
                    };
                    document.addEventListener('click', registerPauseOnInteraction, true);
                    document.addEventListener('touchend', registerPauseOnInteraction, true);
                    document.addEventListener('touchstart', registerPauseOnInteraction, true);
                    document.addEventListener('touchmove', registerPauseOnInteraction, true);
                    document.addEventListener('mousedown', registerPauseOnInteraction, true);
                    document.addEventListener('mousemove', registerPauseOnInteraction, true);
                    document.addEventListener('keydown', registerPauseOnInteraction, true);
                    
                    // Interceptar llamadas JS directas a video.pause()
                    try {
                        const originalPause = HTMLVideoElement.prototype.pause;
                        HTMLVideoElement.prototype.pause = function() {
                            var elapsed = Date.now() - (window.shieldAllowPauseTimestamp || 0);
                            if (elapsed > 2000) {
                                // Permitir la pausa si está en estado de seek, finalizado o cargando (readyState HAVE_NOTHING)
                                if (this.seeking || this.ended || this.readyState === 0 || !this.src) {
                                    return originalPause.apply(this, arguments);
                                }
                                console.log('Shield: Llamada a prototype.pause() ignorada (pausa automática no deseada).');
                                return;
                            }
                            return originalPause.apply(this, arguments);
                        };
                    } catch (e) {
                        console.error('Shield: Failed to override prototype.pause', e);
                    }
                    
                    // Escuchar el evento 'pause' en el documento para revertir pausas nativas del motor Chromium/sistema
                    document.addEventListener('pause', function(e) {
                        if (e.target && e.target.tagName === 'VIDEO') {
                            var elapsed = Date.now() - (window.shieldAllowPauseTimestamp || 0);
                            if (elapsed > 2000) {
                                // Permitir la pausa si está en estado de seek, finalizado o cargando (readyState HAVE_NOTHING)
                                if (e.target.seeking || e.target.ended || e.target.readyState === 0 || !e.target.src) {
                                    console.log('Shield: Pausa nativa permitida (estado especial: seeking/ended/loading)');
                                    return;
                                }
                                console.log('Shield: Detectada pausa nativa del motor/sistema (' + elapsed + 'ms), reanudando...');
                                e.stopImmediatePropagation();
                                e.preventDefault();
                                e.target.play().catch(function(err) {
                                    console.error('Shield auto-resume failed', err);
                                });
                            } else {
                                console.log('Shield: Pausa nativa permitida (dentro del rango de interacción: ' + elapsed + 'ms)');
                            }
                        }
                    }, true);
                    
                    window.shieldPausePreventionInstalled = true;
                    console.log('Shield: Pause prevention system installed.');
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



                // 6. Chequeo periódico antimute (cada 1s) — combate mute forzado por YouTube anti-adblock
                const isWatchOrShort = window.location.href.includes('watch?v=') || window.location.href.includes('/shorts/');
                if (isWatchOrShort) {
                    var video = document.querySelector('video');
                    if (video && video.muted) {
                        video.muted = false;
                        video.volume = 1.0;
                        console.log('Shield: Early video auto-unmuted.');
                    }
                    if (!window.shieldUnmuteInterval) {
                        window.shieldUnmuteInterval = setInterval(function() {
                            var v = document.querySelector('video');
                            if (v) {
                                if (v.muted) {
                                    v.muted = false;
                                    console.log('Shield: Periodic unmute');
                                }
                                if (v.volume === 0) {
                                    v.volume = 1.0;
                                    console.log('Shield: Periodic volume restore');
                                }
                            }
                        }, 1000);
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
        injectVisibilityOverride()
        webView.evaluateJavascript("""
            (function() {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = false;
                    v.volume = 1.0;
                    console.log('Shield: onResume forced unmute');
                }
            })();
        """.trimIndent(), null)
    }

    override fun onPause() {
        super.onPause()
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
        isNavigatingNextOnEnd = false
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