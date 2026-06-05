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
        views.setTextViewText(R.id.itemTitle, item.title)
        views.setTextViewText(R.id.itemChannel, item.channel)

        // Intent de relleno para el elemento individual.
        // Cuando el elemento se cliquea, se le añade este Intent de relleno al PendingIntentTemplate
        // configurado en el WidgetProvider para lanzar MainActivity.
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
