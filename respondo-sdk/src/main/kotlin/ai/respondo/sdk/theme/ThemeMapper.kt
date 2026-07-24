package ai.respondo.sdk.theme

import ai.respondo.sdk.RespondoChatSize
import ai.respondo.sdk.RespondoTheme
import ai.respondo.sdk.transport.dto.WidgetConfigDto

/**
 * Разрешает тему по правилу `themeOverride → серверный конфиг → дефолт SDK` (см. `theming.md`).
 * Игнорирует веб-поля позиционирования лончера/хрома страницы (position, launcher_size, offset_*, z_index, custom_css).
 */
object ThemeMapper {

    fun resolve(config: WidgetConfigDto?, override: RespondoTheme?): ResolvedTheme {
        val primary = override?.primaryColor
            ?: BrandColor.parseHex(config?.primaryColor, ResolvedTheme.DEFAULT_PRIMARY)

        val chatSize = resolveChatSize(override?.chatSize, config?.chatSize)
        val detents = detentsFor(chatSize)

        val radius = (override?.cornerRadiusDp ?: config?.borderRadius?.toInt() ?: ResolvedTheme.DEFAULT_BORDER_RADIUS)
            .coerceIn(ResolvedTheme.MIN_RADIUS, ResolvedTheme.MAX_RADIUS)

        return ResolvedTheme(
            primaryColorArgb = primary,
            onPrimaryArgb = BrandColor.onPrimary(primary),
            linkColorArgb = BrandColor.linkInk(primary),
            title = override?.title?.ifBlank { null }
                ?: config?.title?.ifBlank { null }
                ?: config?.name?.ifBlank { null }
                ?: ResolvedTheme.DEFAULT_TITLE,
            greeting = override?.greeting?.ifBlank { null }
                ?: config?.greeting?.ifBlank { null }
                ?: ResolvedTheme.DEFAULT_GREETING,
            greetingEnabled = config?.greetingEnabled ?: true,
            agentName = config?.name?.ifBlank { null },
            avatarUrl = override?.avatarUrl?.ifBlank { null } ?: config?.avatarUrl?.ifBlank { null },
            logoUrl = override?.logoUrl?.ifBlank { null } ?: config?.logoUrl?.ifBlank { null },
            quickQuestions = config?.quickQuestions ?: emptyList(),
            suggestedQuestionsEnabled = config?.suggestedQuestionsEnabled ?: false,
            proactiveEnabled = config?.proactiveMessagesEnabled ?: false,
            proactiveDelaySeconds = config?.proactiveDelaySeconds ?: ResolvedTheme.DEFAULT_PROACTIVE_DELAY,
            identityVerificationEnabled = config?.identityVerificationEnabled ?: false,
            officeHours = config?.officeHours,
            chatSize = chatSize,
            initialDetent = detents.first,
            maxDetent = detents.second,
            cornerRadiusDp = radius,
        )
    }

    private fun resolveChatSize(override: RespondoChatSize?, config: String?): RespondoChatSize {
        override?.let { return it }
        return when (config?.lowercase()) {
            "compact" -> RespondoChatSize.COMPACT
            "large" -> RespondoChatSize.LARGE
            else -> RespondoChatSize.DEFAULT
        }
    }

    /** Начальный и максимальный детент bottom-sheet (доля высоты экрана), см. `theming.md` §6. */
    fun detentsFor(size: RespondoChatSize): Pair<Float, Float> = when (size) {
        RespondoChatSize.COMPACT -> 0.55f to 0.92f
        RespondoChatSize.DEFAULT -> 0.75f to 0.95f
        RespondoChatSize.LARGE -> 0.92f to 0.98f
    }
}
