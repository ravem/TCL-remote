package it.paolostefani.tclremote.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF7C3AED),
    secondary = Color(0xFF3DDC84),
    tertiary = Color(0xFF00B0FF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB388FF),
    secondary = Color(0xFF57F0A0),
    tertiary = Color(0xFF69D6FF)
)

/**
 * Applies Material 3 with Material You dynamic color where available
 * (Android 12+), falling back to a custom scheme otherwise.
 */
@Composable
fun TclRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && dynamicColorAvailable(context) ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun dynamicColorAvailable(@Suppress("UNUSED_PARAMETER") context: android.content.Context): Boolean = true
