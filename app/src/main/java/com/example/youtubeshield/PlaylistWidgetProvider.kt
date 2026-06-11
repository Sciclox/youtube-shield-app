package com.example.youtubeshield

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews

class PlaylistWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_PLAY = "com.example.youtubeshield.ACTION_WIDGET_PLAY"
        const val ACTION_WIDGET_TOGGLE = "com.example.youtubeshield.ACTION_WIDGET_TOGGLE"
        const val ACTION_WIDGET_NEXT = "com.example.youtubeshield.ACTION_WIDGET_NEXT"
        const val ACTION_WIDGET_PREV = "com.example.youtubeshield.ACTION_WIDGET_PREV"

        private fun getVideoId(url: String?): String? {
            if (url.isNullOrEmpty()) return null
            try {
                val parsedUri = if (url.startsWith("http://") || url.startsWith("https://")) {
                    Uri.parse(url)
                } else {
                    Uri.parse("https://m.youtube.com" + if (url.startsWith("/")) url else "/$url")
                }
                val v = parsedUri.getQueryParameter("v")
                if (!v.isNullOrEmpty()) return v
                val path = parsedUri.path
                if (path != null && path.contains("/shorts/")) return parsedUri.lastPathSegment
                if (url.contains("watch?v=")) {
                    val parts = url.split("watch?v=")
                    if (parts.size > 1) return parts[1].split("&")[0]
                }
                if (url.contains("/shorts/")) {
                    val parts = url.split("/shorts/")
                    if (parts.size > 1) return parts[1].split("?")[0].split("/")[0]
                }
            } catch (_: Exception) {
            }
            return null
        }

        private fun getPendingIntentFlags(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        }

        private fun updatePlayPauseIcon(views: RemoteViews) {
            val isPlaying = PlaylistRepository.isPlaying
            val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            views.setImageViewResource(R.id.widgetPlayPause, playIcon)
            views.setImageViewResource(R.id.widgetPlayPauseMain, playIcon)
        }

        private fun buildWidgetViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_playlist)

            val serviceIntent = Intent(context, PlaylistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widgetListView, serviceIntent)
            views.setEmptyView(R.id.widgetListView, R.id.widgetEmptyView)

            val clickIntent = Intent(context, PlaylistWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_PLAY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val clickPendingIntent = PendingIntent.getBroadcast(
                context, 0, clickIntent, getPendingIntentFlags()
            )
            views.setPendingIntentTemplate(R.id.widgetListView, clickPendingIntent)

            updateNowPlayingText(context, views)
            updatePlayPauseIcon(views)
            setupControlButtons(context, views)

            return views
        }

        private fun updateNowPlayingText(context: Context, views: RemoteViews) {
            val currentUrl = PlaylistRepository.currentPlayingUrl
            val currentId = getVideoId(currentUrl)
            if (currentId != null) {
                val match = PlaylistRepository.playlist.firstOrNull {
                    getVideoId(it.url) == currentId
                }
                if (match != null) {
                    views.setTextViewText(R.id.nowPlayingTitle, match.title)
                    val artist = if (match.channel.isNotEmpty() && match.channel != match.title) {
                        match.channel
                    } else {
                        "YouTube Shield"
                    }
                    views.setTextViewText(R.id.nowPlayingArtist, artist)
                    return
                }
            }
            views.setTextViewText(R.id.nowPlayingTitle, "No song playing")
            views.setTextViewText(R.id.nowPlayingArtist, "YouTube Shield")
        }

        private fun getCurrentPlaylistIndex(): Int {
            val currentId = getVideoId(PlaylistRepository.currentPlayingUrl)
            if (currentId == null) return -1
            return PlaylistRepository.playlist.indexOfFirst {
                getVideoId(it.url) == currentId
            }
        }

        private fun setupControlButtons(context: Context, views: RemoteViews) {
            val flags = getPendingIntentFlags()

            val toggleIntent = Intent(context, PlaylistWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TOGGLE
            }
            val togglePending = PendingIntent.getBroadcast(context, 10, toggleIntent, flags)
            views.setOnClickPendingIntent(R.id.widgetPlayPause, togglePending)
            views.setOnClickPendingIntent(R.id.widgetPlayPauseMain, togglePending)

            val prevIntent = Intent(context, PlaylistWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_PREV
            }
            val prevPending = PendingIntent.getBroadcast(context, 11, prevIntent, flags)
            views.setOnClickPendingIntent(R.id.widgetPrevious, prevPending)

            val nextIntent = Intent(context, PlaylistWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_NEXT
            }
            val nextPending = PendingIntent.getBroadcast(context, 12, nextIntent, flags)
            views.setOnClickPendingIntent(R.id.widgetNext, nextPending)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val appPending = PendingIntent.getActivity(context, 13, launchIntent, flags)
            views.setOnClickPendingIntent(R.id.widgetShuffle, appPending)
            views.setOnClickPendingIntent(R.id.widgetFavorite, appPending)
        }

        fun updateWidget(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PlaylistWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (appWidgetId in appWidgetIds) {
                val views = buildWidgetViews(context, appWidgetId)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        fun refreshPlaylist(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PlaylistWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetIds, R.id.widgetListView)

            for (appWidgetId in widgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_playlist)
                updateNowPlayingText(context, views)
                updatePlayPauseIcon(views)
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }

            loadNowPlayingThumbnail(context, widgetIds)
        }

        private fun loadNowPlayingThumbnail(context: Context, widgetIds: IntArray) {
            val currentUrl = PlaylistRepository.currentPlayingUrl
            val videoId = getVideoId(currentUrl) ?: return
            val thumbUrl = "https://img.youtube.com/vi/$videoId/default.jpg"

            Thread {
                try {
                    val url = java.net.URL(thumbUrl)
                    val connection = url.openConnection()
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    val bitmap = android.graphics.BitmapFactory.decodeStream(connection.getInputStream())
                    if (bitmap != null) {
                        for (appWidgetId in widgetIds) {
                            val views = RemoteViews(context.packageName, R.layout.widget_playlist)
                            views.setImageViewBitmap(R.id.nowPlayingThumbnail, bitmap)
                            val appWidgetManager = AppWidgetManager.getInstance(context)
                            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = buildWidgetViews(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_PLAY -> {
                val url = intent.getStringExtra("video_url")
                if (!url.isNullOrEmpty()) {
                    navigateToVideo(context, url)
                }
            }
            ACTION_WIDGET_TOGGLE -> {
                val action = if (PlaylistRepository.isPlaying) {
                    MediaPlaybackService.ACTION_PAUSE
                } else {
                    MediaPlaybackService.ACTION_PLAY
                }
                val toggleIntent = Intent(action).setPackage(context.packageName)
                context.sendBroadcast(toggleIntent)
            }
            ACTION_WIDGET_NEXT -> {
                val idx = getCurrentPlaylistIndex()
                if (idx >= 0 && idx + 1 < PlaylistRepository.playlist.size) {
                    val nextUrl = PlaylistRepository.playlist[idx + 1].url
                    navigateToVideo(context, nextUrl)
                } else {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                }
            }
            ACTION_WIDGET_PREV -> {
                val idx = getCurrentPlaylistIndex()
                if (idx > 0) {
                    val prevUrl = PlaylistRepository.playlist[idx - 1].url
                    navigateToVideo(context, prevUrl)
                } else {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                }
            }
        }
        super.onReceive(context, intent)
    }

    private fun navigateToVideo(context: Context, url: String) {
        if (MainActivity.isActivityRunning) {
            val changeIntent = Intent("com.example.youtubeshield.ACTION_CHANGE_VIDEO").apply {
                putExtra("video_url", url)
                setPackage(context.packageName)
            }
            context.sendBroadcast(changeIntent)
        } else {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("video_url", url)
            }
            context.startActivity(launchIntent)
        }
    }
}
