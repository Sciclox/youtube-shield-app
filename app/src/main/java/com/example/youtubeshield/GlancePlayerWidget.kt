package com.example.youtubeshield

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionRunAsync
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.Color
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class GlancePlayerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val currentUrl = PlaylistRepository.currentPlayingUrl
        val isPlaying = PlaylistRepository.isPlaying
        val videoId = getVideoId(currentUrl)
        val match = videoId?.let { vid ->
            PlaylistRepository.playlist.firstOrNull { getVideoId(it.url) == vid }
        }
        val title = match?.title ?: "YouTube Shield"
        val channel = match?.channel ?: ""

        provideContent {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(ImageProvider(R.drawable.glance_glass_bg))
                    .padding(16.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "REPRODUCIENDO",
                        style = TextStyle(
                            fontSize = 10.dp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF9A9AB0)
                        )
                    )

                    Spacer(modifier = GlanceModifier.height(6.dp))

                    Text(
                        text = title,
                        style = TextStyle(
                            fontSize = 16.dp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFFFFF)
                        ),
                        modifier = GlanceModifier.fillMaxWidth()
                    )

                    Text(
                        text = channel,
                        style = TextStyle(
                            fontSize = 12.dp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFC0C0D0)
                        ),
                        modifier = GlanceModifier.fillMaxWidth()
                    )

                    Spacer(modifier = GlanceModifier.height(16.dp))

                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = GlanceModifier
                                .size(44.dp)
                                .clickable(actionRunAsync { ctx ->
                                    ctx.sendBroadcast(
                                        Intent(MediaPlaybackService.ACTION_PREV)
                                            .setPackage(ctx.packageName)
                                    )
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_skip_previous),
                                contentDescription = "Anterior",
                                modifier = GlanceModifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(24.dp))

                        val playIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                        Box(
                            modifier = GlanceModifier
                                .size(48.dp)
                                .clickable(actionRunAsync { ctx ->
                                    val action = if (PlaylistRepository.isPlaying)
                                        MediaPlaybackService.ACTION_PAUSE
                                    else MediaPlaybackService.ACTION_PLAY
                                    ctx.sendBroadcast(Intent(action).setPackage(ctx.packageName))
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(playIcon),
                                contentDescription = "Play/Pause",
                                modifier = GlanceModifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(24.dp))

                        Box(
                            modifier = GlanceModifier
                                .size(44.dp)
                                .clickable(actionRunAsync { ctx ->
                                    ctx.sendBroadcast(
                                        Intent(MediaPlaybackService.ACTION_NEXT)
                                            .setPackage(ctx.packageName)
                                    )
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_skip_next),
                                contentDescription = "Siguiente",
                                modifier = GlanceModifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = GlanceModifier.height(10.dp))

                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .clickable(actionRunAsync { ctx ->
                                val launch = Intent(ctx, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                ctx.startActivity(launch)
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Abrir YouTube Shield",
                            style = TextStyle(
                                fontSize = 11.dp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                color = Color(0xFF7A7AE0)
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
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

class GlancePlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlancePlayerWidget()

    companion object {
        fun updateAll(context: Context) {
            MainScope().launch {
                GlanceAppWidgetManager(context).updateAll(GlancePlayerWidget())
            }
        }
    }
}
