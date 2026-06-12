package com.example.youtubeshield

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

class PlayerOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var currentThumbnail: Bitmap? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) {
            createOverlay()
        }
        updateUI()

        val filter = IntentFilter().apply {
            addAction("com.example.youtubeshield.ACTION_OVERLAY_UPDATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        return START_STICKY
    }

    private fun createOverlay() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_player, null)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            flags,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 65
            }
        }

        windowManager?.addView(overlayView, params)

        setupTouchHandler()
        setupClickHandlers()
    }

    private fun setupTouchHandler() {
        overlayView?.setOnTouchListener { _, event ->
            params?.let { p ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x.toFloat()
                        initialY = p.y.toFloat()
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (initialX + event.rawX - initialTouchX).toInt()
                        p.y = (initialY + event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, p)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (distance < 15) {
                            toggleMode()
                        }
                        true
                    }
                    else -> false
                }
            } ?: false
        }
    }

    private fun setupClickHandlers() {
        overlayView?.findViewById<ImageButton>(R.id.overlayPlayPause)?.setOnClickListener {
            val isPlaying = PlaylistRepository.isPlaying
            val action = if (isPlaying) MediaPlaybackService.ACTION_PAUSE else MediaPlaybackService.ACTION_PLAY
            sendBroadcast(Intent(action).setPackage(packageName))
        }
        overlayView?.findViewById<ImageButton>(R.id.overlayNext)?.setOnClickListener {
            sendBroadcast(Intent(MediaPlaybackService.ACTION_NEXT).setPackage(packageName))
        }
        overlayView?.findViewById<ImageButton>(R.id.overlayClose)?.setOnClickListener {
            stopSelf()
        }
    }

    private fun toggleMode() {
        isExpanded = !isExpanded
        overlayView?.findViewById<View>(R.id.overlayBubble)?.visibility =
            if (isExpanded) View.GONE else View.VISIBLE
        overlayView?.findViewById<View>(R.id.overlayPlayer)?.visibility =
            if (isExpanded) View.VISIBLE else View.GONE

        params?.let { p ->
            if (isExpanded) {
                p.width = WindowManager.LayoutParams.WRAP_CONTENT
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                p.width = WindowManager.LayoutParams.WRAP_CONTENT
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            windowManager?.updateViewLayout(overlayView, p)
        }
    }

    private fun updateUI() {
        if (overlayView == null) return

        val currentUrl = PlaylistRepository.currentPlayingUrl
        val isPlaying = PlaylistRepository.isPlaying

        // Buscar información del video actual en la playlist
        val currentId = getVideoId(currentUrl)
        val match = currentId?.let { id ->
            PlaylistRepository.playlist.firstOrNull { getVideoId(it.url) == id }
        }

        val title = match?.title ?: "YouTube Shield"
        val channel = match?.channel ?: "YouTube Shield"

        overlayView?.findViewById<TextView>(R.id.overlayTitle)?.text = title
        overlayView?.findViewById<TextView>(R.id.overlayChannel)?.text = channel

        val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        overlayView?.findViewById<ImageButton>(R.id.overlayPlayPause)?.setImageResource(playIcon)
        overlayView?.findViewById<ImageView>(R.id.overlayBubbleIcon)?.setImageResource(playIcon)

        // Cargar thumbnail
        if (currentThumbnail != null) {
            overlayView?.findViewById<ImageView>(R.id.overlayThumbnail)?.setImageBitmap(currentThumbnail)
            overlayView?.findViewById<ImageView>(R.id.overlayBubbleThumbnail)?.setImageBitmap(currentThumbnail)
        } else if (currentId != null) {
            loadThumbnail(currentId)
        }
    }

    private fun loadThumbnail(videoId: String) {
        Thread {
            try {
                val url = java.net.URL("https://img.youtube.com/vi/$videoId/default.jpg")
                val bitmap = android.graphics.BitmapFactory.decodeStream(url.openStream())
                if (bitmap != null) {
                    currentThumbnail = bitmap
                    updateUI()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun getVideoId(url: String?): String? {
        if (url == null) return null
        return try {
            val parsedUri = if (url.startsWith("http://") || url.startsWith("https://")) {
                android.net.Uri.parse(url)
            } else {
                android.net.Uri.parse("https://m.youtube.com" + if (url.startsWith("/")) url else "/$url")
            }
            val v = parsedUri.getQueryParameter("v")
            if (!v.isNullOrEmpty()) return v
            if (url.contains("watch?v=")) {
                val parts = url.split("watch?v=")
                if (parts.size > 1) return parts[1].split("&")[0]
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun updateThumbnail(bitmap: Bitmap) {
        currentThumbnail = bitmap
        updateUI()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {}
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
