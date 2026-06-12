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

class GlassPlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context, id))
        }
        super.onUpdate(context, manager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                val action = if (PlaylistRepository.isPlaying) MediaPlaybackService.ACTION_PAUSE
                else MediaPlaybackService.ACTION_PLAY
                context.sendBroadcast(Intent(action).setPackage(context.packageName))
            }
            ACTION_NEXT -> {
                context.sendBroadcast(Intent(MediaPlaybackService.ACTION_NEXT).setPackage(context.packageName))
            }
            ACTION_PREV -> {
                context.sendBroadcast(Intent(MediaPlaybackService.ACTION_PREV).setPackage(context.packageName))
            }
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TOGGLE = "com.example.youtubeshield.GLASS_TOGGLE"
        private const val ACTION_NEXT = "com.example.youtubeshield.GLASS_NEXT"
        private const val ACTION_PREV = "com.example.youtubeshield.GLASS_PREV"

        fun updateWidget(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, GlassPlayerWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, id))
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.glass_player_widget)

            val currentUrl = PlaylistRepository.currentPlayingUrl
            val isPlaying = PlaylistRepository.isPlaying
            val videoId = getVideoId(currentUrl)
            val match = videoId?.let { vid ->
                PlaylistRepository.playlist.firstOrNull { getVideoId(it.url) == vid }
            }
            views.setTextViewText(R.id.glassTitle, match?.title ?: "YouTube Shield")
            views.setTextViewText(R.id.glassChannel, match?.channel ?: "")

            val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            views.setImageViewResource(R.id.glassPlayPause, playIcon)

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // Control buttons
            views.setOnClickPendingIntent(R.id.glassPrev,
                PendingIntent.getBroadcast(context, 20,
                    Intent(context, GlassPlayerWidgetProvider::class.java).apply { action = ACTION_PREV }, flags))
            views.setOnClickPendingIntent(R.id.glassPlayPause,
                PendingIntent.getBroadcast(context, 21,
                    Intent(context, GlassPlayerWidgetProvider::class.java).apply { action = ACTION_TOGGLE }, flags))
            views.setOnClickPendingIntent(R.id.glassNext,
                PendingIntent.getBroadcast(context, 22,
                    Intent(context, GlassPlayerWidgetProvider::class.java).apply { action = ACTION_NEXT }, flags))

            // Open app
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(R.id.glassOpenApp,
                PendingIntent.getActivity(context, 23, openIntent, flags))

            // ListView adapter
            val serviceIntent = Intent(context, PlaylistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(Uri.EMPTY.buildUpon().appendQueryParameter("id", appWidgetId.toString()).build().toString())
            }
            views.setRemoteAdapter(R.id.glassListView, serviceIntent)
            views.setEmptyView(R.id.glassListView, R.id.glassEmptyView)

            // List item click
            val clickIntent = Intent(context, PlaylistWidgetProvider::class.java).apply {
                action = PlaylistWidgetProvider.ACTION_WIDGET_PLAY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val clickPending = PendingIntent.getBroadcast(context, 24, clickIntent, flags)
            views.setPendingIntentTemplate(R.id.glassListView, clickPending)

            return views
        }

        private fun getVideoId(url: String?): String? {
            if (url.isNullOrEmpty()) return null
            return try {
                val parsedUri = if (url.startsWith("http://") || url.startsWith("https://")) {
                    Uri.parse(url)
                } else {
                    Uri.parse("https://m.youtube.com" + if (url.startsWith("/")) url else "/$url")
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
    }
}
