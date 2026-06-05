package com.example.youtubeshield

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.HashSet

object AdBlocker {
    private const val TAG = "AdBlocker"
    private val blockedDomains = HashSet<String>()
    private var isLoaded = false
    private val hostCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
            Log.d(TAG, "AdBlocker cargado de forma síncrona con ${blockedDomains.size} dominios.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando dominios de AdBlocker", e)
        }
    }

    fun isAd(url: String): Boolean {
        if (!isLoaded) return false
        try {
            val lowerUrl = url.lowercase()
            // Detectar endpoints de publicidad y tracking internos de YouTube y Google
            if (lowerUrl.contains("youtube.com/pagead") ||
                lowerUrl.contains("youtube.com/ptracking") ||
                lowerUrl.contains("youtube.com/api/stats/ads") ||
                lowerUrl.contains("youtube.com/api/stats/qoe") ||
                lowerUrl.contains("/pagead/gen_204") ||
                lowerUrl.contains("/pagead/interaction") ||
                lowerUrl.contains("doubleclick.net") ||
                lowerUrl.contains("googleads") ||
                lowerUrl.contains("googlesyndication") ||
                lowerUrl.contains("googletagservices.com") ||
                lowerUrl.contains("adservice.google") ||
                lowerUrl.contains("/pagead/")
            ) {
                Log.d(TAG, "Bloqueado por patrón URL: $url")
                return true
            }

            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            
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
}
