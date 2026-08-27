package com.itantra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ItantraOrange = Color(0xFFFF6B35)
private val ItantraDark = Color(0xFF121212)

private val DarkColors = darkColorScheme(primary = ItantraOrange, background = ItantraDark)
private val LightColors = lightColorScheme(primary = ItantraOrange)

@Composable
fun ITantraTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
