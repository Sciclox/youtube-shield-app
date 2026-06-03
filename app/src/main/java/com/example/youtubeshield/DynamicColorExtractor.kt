package com.example.youtubeshield

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

class DynamicColorExtractor {
    
    /**
     * Extrae el color dominante de un Bitmap usando Palette API
     */
    fun extractDominantColor(bitmap: Bitmap?): Int {
        if (bitmap == null) return Color.BLACK
        
        return try {
            val palette = Palette.from(bitmap).generate()
            // Intentar obtener el color vibrante más prominente
            palette.getVibrantColor(
                palette.getDominantColor(Color.BLACK)
            )
        } catch (e: Exception) {
            Color.BLACK
        }
    }
    
    /**
     * Extrae una paleta completa de colores para mayor control
     */
    fun extractColorPalette(bitmap: Bitmap?): ColorPalette {
        if (bitmap == null) return ColorPalette()
        
        return try {
            val palette = Palette.from(bitmap).generate()
            
            ColorPalette(
                dominant = palette.getDominantColor(Color.BLACK),
                vibrant = palette.getVibrantColor(Color.BLACK),
                darkVibrant = palette.getDarkVibrantColor(Color.BLACK),
                lightVibrant = palette.getLightVibrantColor(Color.BLACK),
                muted = palette.getMutedColor(Color.GRAY),
                darkMuted = palette.getDarkMutedColor(Color.DKGRAY)
            )
        } catch (e: Exception) {
            ColorPalette()
        }
    }
    
    /**
     * Data class que contiene la paleta de colores extraída
     */
    data class ColorPalette(
        val dominant: Int = Color.BLACK,
        val vibrant: Int = Color.BLACK,
        val darkVibrant: Int = Color.BLACK,
        val lightVibrant: Int = Color.WHITE,
        val muted: Int = Color.GRAY,
        val darkMuted: Int = Color.DKGRAY
    )
}
