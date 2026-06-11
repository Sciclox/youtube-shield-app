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
                    views.setTextViewText(R.id.nowPlayingArtist, match.channel)
                    return
                }
            }
            views.setTextViewText(R.id.nowPlayingTitle, "No song playing")
            views.setTextViewText(R.id.nowPlayingArtist, "YouTube Shield")
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
                appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
            }
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
            ACTION_WIDGET_TOGGLE -> {
                val playIntent = Intent(MediaPlaybackService.ACTION_PLAY).setPackage(context.packageName)
                context.sendBroadcast(playIntent)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
            }
            ACTION_WIDGET_NEXT -> {
                val nextIntent = Intent(MediaPlaybackService.ACTION_NEXT).setPackage(context.packageName)
                context.sendBroadcast(nextIntent)
                if (!MainActivity.isActivityRunning) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                }
            }
            ACTION_WIDGET_PREV -> {
                val prevIntent = Intent(MediaPlaybackService.ACTION_PREV).setPackage(context.packageName)
                context.sendBroadcast(prevIntent)
                if (!MainActivity.isActivityRunning) {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                }
            }
        }
        super.onReceive(context, intent)
    }
}
