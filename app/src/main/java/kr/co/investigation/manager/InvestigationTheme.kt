package kr.co.investigation.manager

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF355C7D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E7F3),
    onPrimaryContainer = Color(0xFF102C43),
    secondary = Color(0xFF4F6F6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8E3),
    onSecondaryContainer = Color(0xFF173A35),
    tertiary = Color(0xFF7A5B35),
    tertiaryContainer = Color(0xFFFFE4BD),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEF2),
    outlineVariant = Color(0xFFD7DEE3)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7DF),
    onPrimary = Color(0xFF12334B),
    primaryContainer = Color(0xFF244B67),
    secondary = Color(0xFFB7CCC7),
    onSecondary = Color(0xFF233F3A),
    tertiary = Color(0xFFE9C18F),
    background = Color(0xFF101417),
    surface = Color(0xFF171C20),
    surfaceVariant = Color(0xFF2A3136)
)

@Composable
fun InvestigationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
