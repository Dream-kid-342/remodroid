package com.remodroid.windows.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object RemoColors {
    val Navy900 = Color(0xFF0B1220)
    val Navy800 = Color(0xFF111A2E)
    val Navy700 = Color(0xFF18233B)
    val Navy600 = Color(0xFF243150)
    val Blue500 = Color(0xFF2F7BF5)
    val Blue300 = Color(0xFF7FB0FF)
    val TextSecondary = Color(0xFFB4BED3)
    val Success = Color(0xFF3CCB7F)
    val Warning = Color(0xFFF2B84B)
    val Danger = Color(0xFFF2545B)
}

val MonoDigits = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = 1.sp)

@Composable
fun RemoDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = RemoColors.Blue500,
            onPrimary = Color.White,
            secondary = RemoColors.Blue300,
            background = RemoColors.Navy900,
            onBackground = Color.White,
            surface = RemoColors.Navy800,
            onSurface = Color.White,
            surfaceVariant = RemoColors.Navy700,
            onSurfaceVariant = RemoColors.TextSecondary,
            outline = RemoColors.Navy600,
            error = RemoColors.Danger,
        ),
        content = content,
    )
}
