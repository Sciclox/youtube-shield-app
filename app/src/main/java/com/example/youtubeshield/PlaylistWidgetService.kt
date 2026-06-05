package com.example.youtubeshield

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class PlaylistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return PlaylistViewsFactory(this.applicationContext)
    }
}

class PlaylistViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<PlaylistRepository.PlaylistItem> = emptyList()

    override fun onCreate() {
        // Inicialización
    }

    override fun onDataSetChanged() {
        items = PlaylistRepository.playlist
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int {
        return items.size
    }

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= items.size) return null

        val item = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_playlist_item)

        // Comprobar si este elemento es el que se está reproduciendo actualmente
        val currentPlaying = PlaylistRepository.currentPlayingUrl
        val isCurrent = if (!currentPlaying.isNullOrEmpty() && item.url.isNotEmpty()) {
            val cleanPlaying = currentPlaying.substringAfter("youtube.com").substringAfter("youtu.be")
            val cleanItem = item.url.substringAfter("youtube.com").substringAfter("youtu.be")
            cleanPlaying.contains("watch?v=") && cleanItem.contains("watch?v=") && 
                    cleanPlaying.substringAfter("watch?v=") == cleanItem.substringAfter("watch?v=")
        } else {
            false
        }

        if (isCurrent) {
            views.setTextViewText(R.id.itemTitle, "▶ ${item.title}")
            views.setTextColor(R.id.itemTitle, android.graphics.Color.parseColor("#FF2A2A"))
            views.setViewVisibility(R.id.itemIndicator, android.view.View.VISIBLE)
        } else {
            views.setTextViewText(R.id.itemTitle, item.title)
            views.setTextColor(R.id.itemTitle, android.graphics.Color.parseColor("#F3F3F5"))
            views.setViewVisibility(R.id.itemIndicator, android.view.View.INVISIBLE)
        }

        views.setTextViewText(R.id.itemChannel, item.channel)

        // Intent de relleno para el elemento individual.
        // Cuando el elemento se cliquea, se le añade este Intent de relleno al PendingIntentTemplate
        // configurado en el WidgetProvider para lanzar o cambiar de canción.
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
