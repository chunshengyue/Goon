package com.example.goon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与 AgentWorkbench 的调色板保持一致，避免 Material 组件与自绘组件出现两套视觉。
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFE7EEFF),
    onPrimaryContainer = Color(0xFF14213D), secondary = Coral, background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF14171C), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFEDF0F4), onSurfaceVariant = Color(0xFF4B5563), outline = Color(0xFFD0D5DD)
)

@Composable
fun GoonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = Typography, content = content)
}
