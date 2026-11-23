package com.example.youtubeshield

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.HashSet

object AdBlocker {
    private const val TAG = "AdBlocker"
    private val blockedDomains = HashSet<String>()
    @Volatile
    private var isLoaded = false
    private val hostCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Synchronized
    fun init(context: Context) {
        if (isLoaded) return
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("hosts.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val cleanLine = line!!.trim()
                if (cleanLine.isNotEmpty() && !cleanLine.startsWith("#")) {
                    blockedDomains.add(cleanLine.lowercase())
                }
            }
            reader.close()
            inputStream.close()
            isLoaded = true
            Log.d(TAG, "AdBlocker cargado con ${blockedDomains.size} dominios.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando dominios de AdBlocker", e)
        }
    }

    fun isAd(url: String): Boolean {
        try {
            val lowerUrl = url.lowercase()

            // 1. Detección inmediata por patrones de URL y endpoints publicitarios conocidos
            if (lowerUrl.contains("youtube.com/pagead") ||
                lowerUrl.contains("youtube.com/ptracking") ||
                lowerUrl.contains("youtube.com/api/stats/ads") ||
                lowerUrl.contains("youtube.com/api/stats/atr") ||
                lowerUrl.contains("youtube.com/pcs/activeview") ||
                lowerUrl.contains("ads.youtube.com") ||
                lowerUrl.contains("get_midroll_info") ||
                lowerUrl.contains("stats/ads") ||
                lowerUrl.contains("/pagead/gen_204") ||
                lowerUrl.contains("/pagead/interaction") ||
                lowerUrl.contains("/pagead/") ||
                lowerUrl.contains("doubleclick.net") ||
                lowerUrl.contains("googleads") ||
                lowerUrl.contains("googlesyndication") ||
                lowerUrl.contains("googletagservices.com") ||
                lowerUrl.contains("googleadservices.com") ||
                lowerUrl.contains("adservice.google") ||
                lowerUrl.contains("play.google.com/log") ||
                lowerUrl.contains("innovid.com") ||
                lowerUrl.contains("serving-sys.com")
            ) {
                Log.d(TAG, "Bloqueado por patrón URL publicitario: $url")
                return true
            }

            // 2. Detección de streams publicitarios en googlevideo.com
            if (lowerUrl.contains("googlevideo.com/videoplayback") &&
                (lowerUrl.contains("adformat=") ||
                 lowerUrl.contains("oad=") ||
                 lowerUrl.contains("&ad=") ||
                 lowerUrl.contains("?ad=") ||
                 lowerUrl.contains("ad_type=") ||
                 lowerUrl.contains("ad_break=") ||
                 lowerUrl.contains("adunit") ||
                 lowerUrl.contains("ctier=l"))
            ) {
                Log.d(TAG, "Bloqueado stream de video publicitario en googlevideo: $url")
                return true
            }

            val host = getHost(url) ?: return false

            // Proteger dominios esenciales de YouTube, Google Auth y reproducción de video
            if (host == "m.youtube.com" ||
                host == "www.youtube.com" ||
                host == "youtube.com" ||
                host.contains("googlevideo.com") ||
                host.contains("accounts.google.com")
            ) {
                return false
            }

            if (!isLoaded) return false

            var isHostBlocked = hostCache[host]
            if (isHostBlocked == null) {
                isHostBlocked = isHostAd(host)
                hostCache[host] = isHostBlocked
            }

            if (isHostBlocked) {
                Log.d(TAG, "Bloqueado por host: $host")
            }
            return isHostBlocked
        } catch (e: Exception) {
            return false
        }
    }

    private fun isHostAd(host: String): Boolean {
        var tempHost = host.lowercase()
        while (tempHost.contains(".")) {
            if (blockedDomains.contains(tempHost)) {
                return true
            }
            val nextDotIndex = tempHost.indexOf('.')
            if (nextDotIndex == -1 || nextDotIndex == tempHost.length - 1) {
                break
            }
            tempHost = tempHost.substring(nextDotIndex + 1)
        }
        return blockedDomains.contains(tempHost)
    }

    private fun getHost(url: String): String? {
        var temp = url
        val doubleSlash = temp.indexOf("//")
        if (doubleSlash != -1) {
            temp = temp.substring(doubleSlash + 2)
        }
        val slash = temp.indexOf('/')
        if (slash != -1) {
            temp = temp.substring(0, slash)
        }
        val colon = temp.indexOf(':')
        if (colon != -1) {
            temp = temp.substring(0, colon)
        }
        val question = temp.indexOf('?')
        if (question != -1) {
            temp = temp.substring(0, question)
        }
        return if (temp.isNotEmpty()) temp.lowercase() else null
    }
}
