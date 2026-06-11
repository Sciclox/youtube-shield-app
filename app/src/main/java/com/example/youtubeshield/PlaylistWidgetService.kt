package com.example.youtubeshield

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class PlaylistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return PlaylistViewsFactory(this.applicationContext)
    }
}

class PlaylistViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<PlaylistRepository.PlaylistItem> = emptyList()
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()

    private fun getVideoId(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        try {
            val parsedUri = if (url.startsWith("http://") || url.startsWith("https://")) {
                android.net.Uri.parse(url)
            } else {
                android.net.Uri.parse("https://m.youtube.com" + if (url.startsWith("/")) url else "/$url")
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

    private fun loadThumbnail(videoId: String): Bitmap? {
        try {
            val url = URL("https://img.youtube.com/vi/$videoId/default.jpg")
            val connection = url.openConnection()
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val bitmap = BitmapFactory.decodeStream(connection.getInputStream())
            if (bitmap != null) {
                thumbnailCache[videoId] = bitmap
            }
            return bitmap
        } catch (_: Exception) {
            return null
        }
    }

    override fun onCreate() {
    }

    override fun onDataSetChanged() {
        items = PlaylistRepository.playlist
    }

    override fun onDestroy() {
        items = emptyList()
        thumbnailCache.clear()
    }

    override fun getCount(): Int {
        return items.size
    }

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= items.size) return null

        val item = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_playlist_item)

        val playingId = getVideoId(PlaylistRepository.currentPlayingUrl)
        val itemId = getVideoId(item.url)
        val isCurrent = playingId != null && itemId != null && playingId == itemId

        if (isCurrent) {
            views.setTextViewText(R.id.itemTitle, item.title)
            views.setTextColor(R.id.itemTitle, android.graphics.Color.parseColor("#FFFF2A2A"))
            views.setViewVisibility(R.id.itemIndicator, android.view.View.VISIBLE)
        } else {
            views.setTextViewText(R.id.itemTitle, item.title)
            views.setTextColor(R.id.itemTitle, android.graphics.Color.parseColor("#FFE5E2E1"))
            views.setViewVisibility(R.id.itemIndicator, android.view.View.INVISIBLE)
        }

        views.setTextViewText(R.id.itemChannel, item.channel)

        val videoId = getVideoId(item.url)
        if (videoId != null) {
            val cached = thumbnailCache[videoId]
            if (cached != null) {
                views.setImageViewBitmap(R.id.itemThumbnail, cached)
            } else {
                val bitmap = loadThumbnail(videoId)
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.itemThumbnail, bitmap)
                }
            }
        }

        val fillInIntent = Intent().apply {
            putExtra("video_url", item.url)
            putExtra("video_title", item.title)
        }
        views.setOnClickFillInIntent(R.id.itemContainer, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getViewTypeCount(): Int {
        return 1
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }
}
