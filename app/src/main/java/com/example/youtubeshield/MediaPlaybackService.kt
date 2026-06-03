package com.example.youtubeshield

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

class MediaPlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    private var callback: PlaybackCallback? = null

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
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setPlaybackCallback(callback: PlaybackCallback) {
        this.callback = callback
    }

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
        mediaSession?.isActive = true
    }

    fun updateMetadata(title: String, isPlaying: Boolean, isLooping: Boolean) {
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

        val loopIcon = R.drawable.ic_refresh
        val loopActionName = if (isLooping) "Repetir: ON" else "Repetir: OFF"
        stateBuilder.addCustomAction(
            PlaybackState.CustomAction.Builder(
                "ACTION_TOGGLE_LOOP",
                loopActionName,
                loopIcon
            ).build()
        )

        mediaSession?.setPlaybackState(stateBuilder.build())

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "YouTube Shield")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, BitmapFactory.decodeResource(resources, R.drawable.ic_app_icon))
            .build()
        mediaSession?.setMetadata(metadata)

        showNotification(title, isPlaying, isLooping)
    }

    private fun showNotification(title: String, isPlaying: Boolean, isLooping: Boolean) {
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

        val prevIntent = Intent(this, MediaPlaybackService::class.java).setAction("ACTION_PREV")
        val playIntent = Intent(this, MediaPlaybackService::class.java).setAction("ACTION_PLAY_PAUSE")
        val nextIntent = Intent(this, MediaPlaybackService::class.java).setAction("ACTION_NEXT")
        val loopIntent = Intent(this, MediaPlaybackService::class.java).setAction("ACTION_LOOP")

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pPrev = PendingIntent.getService(this, 1, prevIntent, flags)
        val pPlay = PendingIntent.getService(this, 2, playIntent, flags)
        val pNext = PendingIntent.getService(this, 3, nextIntent, flags)
        val pLoop = PendingIntent.getService(this, 4, loopIntent, flags)

        val loopIndicatorText = if (isLooping) "🔂 Repetir canción activa" else "➡️ Reproducción normal"

        val notificationBuilder = Notification.Builder(this, channelId)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(loopIndicatorText)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_app_icon))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Anterior", pPrev).build())
            .addAction(Notification.Action.Builder(playPauseIcon, playPauseText, pPlay).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Siguiente", pNext).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_rotate, if (isLooping) "Bucle: SI" else "Bucle: NO", pLoop).build())

        val mainIntent = Intent(this, MainActivity::class.java)
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
        val action = intent?.action
        when (action) {
            "ACTION_PLAY_PAUSE" -> {
                if (mediaSession?.controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    callback?.onPause()
                } else {
                    callback?.onPlay()
                }
            }
            "ACTION_PREV" -> callback?.onPrevious()
            "ACTION_NEXT" -> callback?.onNext()
            "ACTION_LOOP" -> callback?.onToggleLoop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
    }
}
