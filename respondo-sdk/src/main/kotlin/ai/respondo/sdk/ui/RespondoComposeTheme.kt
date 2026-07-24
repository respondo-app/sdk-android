package ai.respondo.sdk.ui

import ai.respondo.sdk.theme.BrandColor
import ai.respondo.sdk.theme.ResolvedTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** ARGB-Long → Compose [Color]. */
internal fun Long.toComposeColor(): Color = Color(this)

/**
 * Тема Compose для листа чата. Всегда светлая (в вебе тёмной темы нет — см. `theming.md` §7), не наследует
 * системную тёмную схему устройства. Primary/onPrimary берутся из [ResolvedTheme].
 */
@Composable
internal fun RespondoComposeTheme(theme: ResolvedTheme, content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = theme.primaryColorArgb.toComposeColor(),
        onPrimary = theme.onPrimaryArgb.toComposeColor(),
        background = Color.White,
        surface = Color.White,
        onBackground = BrandColor.INK.toComposeColor(),
        onSurface = BrandColor.INK.toComposeColor(),
        surfaceVariant = BrandColor.ASSISTANT_BUBBLE.toComposeColor(),
        onSurfaceVariant = BrandColor.INK.toComposeColor(),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
