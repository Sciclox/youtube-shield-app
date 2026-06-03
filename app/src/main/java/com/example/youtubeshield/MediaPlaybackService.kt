package com.example.youtubeshield

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class MediaPlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    private var callback: PlaybackCallback? = null

    // Acciones de intent para el BroadcastReceiver
    companion object {
        const val ACTION_PLAY = "com.example.youtubeshield.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.youtubeshield.ACTION_PAUSE"
        const val ACTION_PREV = "com.example.youtubeshield.ACTION_PREV"
        const val ACTION_NEXT = "com.example.youtubeshield.ACTION_NEXT"
        const val ACTION_LOOP = "com.example.youtubeshield.ACTION_LOOP"
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_PLAY -> callback?.onPlay()
                ACTION_PAUSE -> callback?.onPause()
                ACTION_PREV -> callback?.onPrevious()
                ACTION_NEXT -> callback?.onNext()
                ACTION_LOOP -> callback?.onToggleLoop()
            }
        }
    }

    interface PlaybackCallback {
        fun onPlay()
        fun onPause()
        fun onNext()
        fun onPrevious()
        fun onToggleLoop()
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        setupMediaSession()

        // Registrar receptor de transmisiones local para evitar restricciones de Android 12+
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_PREV)
            addAction(ACTION_NEXT)
            addAction(ACTION_LOOP)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }

        // Mostrar de inmediato una notificación por defecto para cumplir con Android 14
        showNotification("YouTube Shield", false, false, BitmapFactory.decodeResource(resources, R.drawable.ic_app_icon))
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setPlaybackCallback(callback: PlaybackCallback) {
        this.callback = callback
    }

    @Suppress("DEPRECATION")
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "YouTubeShieldSession")
        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                super.onPlay()
                callback?.onPlay()
            }

            override fun onPause() {
                super.onPause()
                callback?.onPause()
            }

            override fun onSkipToNext() {
                super.onSkipToNext()
                callback?.onNext()
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                callback?.onPrevious()
            }

            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                super.onCustomAction(action, extras)
                if (action == "ACTION_TOGGLE_LOOP") {
                    callback?.onToggleLoop()
                }
            }
        })
        
        // Configurar banderas para compatibilidad con dispositivos más antiguos
        mediaSession?.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession?.isActive = true
    }

    fun updateMetadata(title: String, isPlaying: Boolean, isLooping: Boolean, thumbnail: android.graphics.Bitmap? = null) {
        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1.0f
            )

        val loopIcon = if (isLooping) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        val loopActionName = if (isLooping) "Repetir: 1" else "Repetir: Todo"
        stateBuilder.addCustomAction(
            PlaybackState.CustomAction.Builder(
                "ACTION_TOGGLE_LOOP",
                loopActionName,
                loopIcon
            ).build()
        )

        mediaSession?.setPlaybackState(stateBuilder.build())

        val displayBitmap = thumbnail ?: BitmapFactory.decodeResource(resources, R.drawable.ic_app_icon)

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "YouTube Shield")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, displayBitmap)
            .build()
        mediaSession?.setMetadata(metadata)

        showNotification(title, isPlaying, isLooping, displayBitmap)
    }

    private fun showNotification(title: String, isPlaying: Boolean, isLooping: Boolean, thumbnail: android.graphics.Bitmap) {
        val channelId = "youtube_shield_playback"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Reproductor en Segundo Plano",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pausar" else "Reproducir"

        // Crear intents explícitos para el BroadcastReceiver
        val prevIntent = Intent(ACTION_PREV).setPackage(packageName)
        val playIntent = Intent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY).setPackage(packageName)
        val nextIntent = Intent(ACTION_NEXT).setPackage(packageName)
        val loopIntent = Intent(ACTION_LOOP).setPackage(packageName)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pPrev = PendingIntent.getBroadcast(this, 1, prevIntent, flags)
        val pPlay = PendingIntent.getBroadcast(this, 2, playIntent, flags)
        val pNext = PendingIntent.getBroadcast(this, 3, nextIntent, flags)
        val pLoop = PendingIntent.getBroadcast(this, 4, loopIntent, flags)

        val loopIndicatorText = if (isLooping) "🔂 Repetir canción activa" else "➡️ Reproducción normal"
        val notificationLoopIcon = if (isLooping) R.drawable.ic_repeat_one else R.drawable.ic_repeat

        val notificationBuilder = Notification.Builder(this, channelId)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(loopIndicatorText)
            .setLargeIcon(thumbnail)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", pPrev).build())
            .addAction(Notification.Action.Builder(playPauseIcon, playPauseText, pPlay).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Siguiente", pNext).build())
            .addAction(Notification.Action.Builder(notificationLoopIcon, if (isLooping) "Bucle: 1" else "Bucle: Normal", pLoop).build())

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pMain = PendingIntent.getActivity(this, 0, mainIntent, flags)
        notificationBuilder.setContentIntent(pMain)

        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1001, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enlazar los comandos que se envían directamente por intent al receptor
        val action = intent?.action
        if (action != null) {
            val localBroadcastIntent = Intent(action).setPackage(packageName)
            sendBroadcast(localBroadcastIntent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        try {
            unregisterReceiver(mediaReceiver)
        } catch (e: Exception) {
            // Ignorar si no estaba registrado
        }
    }
}
