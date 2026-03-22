package com.example.comicreader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ComicDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun ComicReaderTheme(
    darkTheme: Boolean = true, // Force Dark Mode for Marvel/DC theme
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ComicDarkColorScheme,
        typography = Typography,
        content = content
    )
}