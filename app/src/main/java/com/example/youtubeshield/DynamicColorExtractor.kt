package com.example.youtubeshield

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

class DynamicColorExtractor {
    
/**
 * Extrae el color dominante/representativo de un Bitmap.
 * Devuelve un int ARGB (fallback: #FF333333).
 */
fun extractDominantColor(bitmap: Bitmap?): Int {
    if (bitmap == null) return 0xFF333333.toInt()
    return try {
        val defaultColor = 0xFF333333.toInt()
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        // Preferir vibrante si existe, sino dominante, sino fallback
        palette.getVibrantColor(palette.getDominantColor(defaultColor))
    } catch (e: Exception) {
        0xFF333333.toInt()
    }
}

/**
 * Extrae una paleta completa para usos más avanzados.
 */
fun extractColorPalette(bitmap: Bitmap?): ColorPalette {
    if (bitmap == null) return ColorPalette()
    return try {
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        ColorPalette(
            dominant = palette.getDominantColor(0xFF333333.toInt()),
            vibrant = palette.getVibrantColor(0xFF333333.toInt()),
            darkVibrant = palette.getDarkVibrantColor(0xFF222222.toInt()),
            lightVibrant = palette.getLightVibrantColor(0xFFFFFFFF.toInt()),
            muted = palette.getMutedColor(0xFF888888.toInt()),
            darkMuted = palette.getDarkMutedColor(0xFF444444.toInt())
        )
    } catch (e: Exception) {
        ColorPalette()
    }
}

data class ColorPalette(
    val dominant: Int = 0xFF333333.toInt(),
    val vibrant: Int = 0xFF333333.toInt(),
    val darkVibrant: Int = 0xFF222222.toInt(),
    val lightVibrant: Int = 0xFFFFFFFF.toInt(),
    val muted: Int = 0xFF888888.toInt(),
    val darkMuted: Int = 0xFF444444.toInt()
)
}
