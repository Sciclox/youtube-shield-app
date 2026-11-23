package com.example.youtubeshield.tv

import android.content.Context
import android.util.Log

data class TvProfile(
    val id: String,
    val name: String,
    val userAgent: String,
    val description: String
)

/**
 * Gestor y selector/rotador dinámico de User-Agents de Smart TV.
 * Permite evitar bloqueos de Google ("Navegador no compatible") rotando entre perfiles de TV certificados.
 */
object TvUserAgentManager {
    private const val TAG = "TvUserAgentManager"
    private const val PREF_KEY_UA_INDEX = "tv_user_agent_index"

    const val UA_DESKTOP_AUTH = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val PROFILES = listOf(
        TvProfile(
            id = "samsung_tizen",
            name = "Samsung Smart TV (Recomendado)",
            userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/6.0 Chrome/108.0.5359.1 TV Safari/537.36",
            description = "Perfil moderno HTML5 MSE sin bloqueos DRM propietarias"
        ),
        TvProfile(
            id = "lg_webos",
            name = "LG webOS Smart TV",
            userAgent = "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.211 Safari/537.36 WebAppManager",
            description = "Perfil oficial de televisores inteligentes LG con Chromium"
        ),
        TvProfile(
            id = "android_tv",
            name = "Sony Bravia Android TV",
            userAgent = "Mozilla/5.0 (Linux; Android 10; BRAVIA 4K UR2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.211 Safari/537.36",
            description = "Perfil certificado de Google TV / Android TV Leanback"
        ),
        TvProfile(
            id = "ps4_cobalt",
            name = "PlayStation Cobalt",
            userAgent = "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible;",
            description = "Perfil oficial de consola PlayStation"
        ),
        TvProfile(
            id = "xbox_cobalt",
            name = "Xbox One Cobalt",
            userAgent = "Mozilla/5.0 (DirectFB; Linux x86_64; Xbox One) Cobalt/24.lts.4-gold (unlike Gecko) Starboard/14",
            description = "Perfil de entretenimiento de salón Xbox"
        )
    )

    fun getCurrentProfile(context: Context): TvProfile {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val index = prefs.getInt(PREF_KEY_UA_INDEX, 0)
        return if (index in PROFILES.indices) {
            PROFILES[index]
        } else {
            PROFILES[0]
        }
    }

    fun rotateNext(context: Context): TvProfile {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val currentIndex = prefs.getInt(PREF_KEY_UA_INDEX, 0)
        val nextIndex = (currentIndex + 1) % PROFILES.size
        prefs.edit().putInt(PREF_KEY_UA_INDEX, nextIndex).apply()
        val profile = PROFILES[nextIndex]
        Log.d(TAG, "Rotado a nuevo perfil TV: ${profile.name}")
        return profile
    }

    fun setProfileIndex(context: Context, index: Int): TvProfile {
        val validIndex = if (index in PROFILES.indices) index else 0
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_KEY_UA_INDEX, validIndex).apply()
        return PROFILES[validIndex]
    }
}
