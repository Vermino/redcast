package com.oak404.redcast.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.oak404.redcast.data.AppTheme

private val Red = Color(0xFFE5383B)

private fun dark(primary: Color, background: Color) = darkColorScheme(
    primary = primary,
    onPrimary = Color.White,
    background = background,
    surface = background,
)

fun colorsFor(theme: AppTheme, systemDark: Boolean): ColorScheme = when (theme) {
    AppTheme.System -> if (systemDark) dark(Red, Color(0xFF0E0E11)) else lightColorScheme(primary = Red)
    AppTheme.Light -> lightColorScheme(primary = Red)
    AppTheme.Dark -> dark(Red, Color(0xFF0E0E11))
    AppTheme.Amoled -> dark(Red, Color.Black)
    AppTheme.Midnight -> dark(Color(0xFF6C8CFF), Color(0xFF0B1026))
    AppTheme.Sunset -> dark(Color(0xFFFF7A45), Color(0xFF1E0F1C))
    AppTheme.Forest -> dark(Color(0xFF4CAF7A), Color(0xFF0F1A14))
}
