package com.example.youtubeshield

object PlaylistRepository {
    data class PlaylistItem(val title: String, val url: String, val channel: String)

    @Volatile
    var playlist: List<PlaylistItem> = emptyList()
}
