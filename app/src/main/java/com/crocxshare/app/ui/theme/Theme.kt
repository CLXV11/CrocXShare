package com.crocxshare.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Premium dark-first palette. Light theme mirrors the same hues on bright surfaces.
private data class Scheme(val light: androidx.compose.material3.ColorScheme,
                          val dark: androidx.compose.material3.ColorScheme)

private fun mk(p: Long, pD: Long, ter: Long, terD: Long): Scheme {
    val pc = Color(p); val pcd = Color(pD)
    return Scheme(
        light = lightColorScheme(
            primary = pc, onPrimary = Color.White,
            secondary = Color(0xFF5A6B85), onSecondary = Color.White,
            tertiary = Color(ter),
            background = Color(0xFFF4F6FA), onBackground = Color(0xFF10151D),
            surface = Color(0xFFFFFFFF), onSurface = Color(0xFF10151D),
            surfaceVariant = Color(0xFFE8EDF5), onSurfaceVariant = Color(0xFF4A5568),
            outline = Color(0xFFC4CDDA)
        ),
        dark = darkColorScheme(
            primary = pcd, onPrimary = Color(0xFF0B0F14),
            secondary = Color(0xFF8DA2C0), onSecondary = Color(0xFF0B0F14),
            tertiary = Color(terD),
            background = Color(0xFF0B0F14), onBackground = Color(0xFFE6ECF5),
            surface = Color(0xFF12181F), onSurface = Color(0xFFE6ECF5),
            surfaceVariant = Color(0xFF1C2531), onSurfaceVariant = Color(0xFF9AA8BC),
            outline = Color(0x33334052)
        )
    )
}

private val SCHEMES = mapOf(
    "GREEN" to mk(0xFF2E7D32, 0xFF7CE7A2, 0xFFF5A623, 0xFFFFC46B),
    "BLUE" to mk(0xFF1B6FE0, 0xFF6FB4FF, 0xFFF5A623, 0xFFFFC46B),
    "PURPLE" to mk(0xFF7C3AED, 0xFFB79CFF, 0xFFF5A623, 0xFFFFC46B),
    "ORANGE" to mk(0xFFE8590C, 0xFFFFA26B, 0xFF2E7D32, 0xFF7CE7A2),
    "RED" to mk(0xFFDC2F2F, 0xFFFF8A8A, 0xFFF5A623, 0xFFFFC46B)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun CrocXTheme(theme: String, colorName: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = SCHEMES[colorName] ?: SCHEMES.getValue("BLUE")
    MaterialTheme(
        colorScheme = if (dark) scheme.dark else scheme.light,
        shapes = AppShapes,
        content = content
    )
}
