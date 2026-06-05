package com.example.youtubeshield

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

class PlaylistWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_PLAY = "com.example.youtubeshield.ACTION_WIDGET_PLAY"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_playlist)

            // Intent para enlazar el servicio ListView
            val serviceIntent = Intent(context, PlaylistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widgetListView, serviceIntent)
            views.setEmptyView(R.id.widgetListView, R.id.widgetEmptyView)

            // Intent de plantilla para capturar los clics en los elementos de la lista enviando un Broadcast a este receptor
            val clickIntent = Intent(context, PlaylistWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_PLAY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val clickPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                clickIntent,
                flags
            )
            views.setPendingIntentTemplate(R.id.widgetListView, clickPendingIntent)

            // Actualizar widget en el AppWidgetManager
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_PLAY) {
            val url = intent.getStringExtra("video_url")
            if (!url.isNullOrEmpty()) {
                if (MainActivity.isActivityRunning) {
                    // Si MainActivity ya está en ejecución (por ejemplo en segundo plano),
                    // le enviamos un Broadcast interno para cambiar de video sin traer la app al frente.
                    val changeIntent = Intent("com.example.youtubeshield.ACTION_CHANGE_VIDEO").apply {
                        putExtra("video_url", url)
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(changeIntent)
                } else {
                    // Si MainActivity no está ejecutándose, abrimos la aplicación normalmente
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("video_url", url)
                    }
                    context.startActivity(launchIntent)
                }
            }
        }
        super.onReceive(context, intent)
    }
}
