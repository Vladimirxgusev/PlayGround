package app.pomoshnik

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

enum class AssistantThemeMode(val label: String) {
    SYSTEM("Как в системе"), DARK("Тёмная"), LIGHT("Светлая"),
}

enum class AssistantAccent(val label: String, val dark: Color, val light: Color) {
    ICE("Лёд", Color(0xFFACE2FF), Color(0xFF006087)),
    JADE("Нефрит", Color(0xFF8BE7C5), Color(0xFF00664F)),
    VIOLET("Лаванда", Color(0xFFC8BEFF), Color(0xFF5135AC)),
}

data class AssistantAppearance(
    val mode: AssistantThemeMode = AssistantThemeMode.SYSTEM,
    val accent: AssistantAccent = AssistantAccent.ICE,
    val largeAnswers: Boolean = false,
)

@Composable
internal fun AssistantTheme(appearance: AssistantAppearance, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        AssistantThemeMode.SYSTEM -> isSystemInDarkTheme()
        AssistantThemeMode.DARK -> true
        AssistantThemeMode.LIGHT -> false
    }
    val colors = if (dark) {
        darkColorScheme(
            primary = appearance.accent.dark,
            onPrimary = Color(0xFF071820),
            secondary = Color(0xFFCAD5E2),
            tertiary = Color(0xFFC7BBFF),
            primaryContainer = Color(0xFF253E50),
            onPrimaryContainer = Color(0xFFEAF6FF),
            background = Color(0xFF0E1218),
            onBackground = Color(0xFFF5F7FB),
            surface = Color(0xFF171D26),
            onSurface = Color(0xFFF5F7FB),
            surfaceVariant = Color(0xFF242D3A),
            onSurfaceVariant = Color(0xFFCBD6E4),
            outline = Color(0xFF8998AD),
            outlineVariant = Color(0xFF3C495C),
            error = Color(0xFFFFB9B1),
        )
    } else {
        lightColorScheme(
            primary = appearance.accent.light,
            onPrimary = Color.White,
            secondary = Color(0xFF3B4F64),
            tertiary = Color(0xFF5542A4),
            primaryContainer = Color(0xFFE1EEFA),
            onPrimaryContainer = Color(0xFF142738),
            background = Color(0xFFF0F4F8),
            onBackground = Color(0xFF141F2C),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF141F2C),
            surfaceVariant = Color(0xFFE8EEF5),
            onSurfaceVariant = Color(0xFF394B5E),
            outline = Color(0xFF657891),
            outlineVariant = Color(0xFFC3CFDC),
            error = Color(0xFFB2232C),
        )
    }
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = colors,
        typography = typography.copy(
            bodyLarge = typography.bodyLarge.copy(
                fontSize = if (appearance.largeAnswers) 21.sp else 17.sp,
                lineHeight = if (appearance.largeAnswers) 31.sp else 25.sp,
            ),
        ),
        content = content,
    )
}
