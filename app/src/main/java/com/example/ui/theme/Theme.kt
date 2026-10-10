package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val PlayerColorScheme = darkColorScheme(
    primary = PlayerPrimary,
    onPrimary = PlayerOnPrimary,
    secondary = PlayerSecondary,
    onSecondary = Color(0xFF00363F),
    tertiary = PlayerTertiary,
    onTertiary = Color.White,
    background = PlayerDarkBackground,
    onBackground = PlayerTextPrimary,
    surface = PlayerSurface,
    onSurface = PlayerTextPrimary,
    surfaceVariant = PlayerSurfaceVariant,
    onSurfaceVariant = PlayerTextSecondary,
    outline = PlayerBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Player operates as a dark camera interface
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PlayerColorScheme,
        typography = Typography(),
        content = content
    )
}
