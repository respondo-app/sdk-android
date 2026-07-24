package ai.respondo.sdk.core

/** Вход арбитра оверлеев. */
data class OverlayArbiterInput(
    val surveys: List<RespondoSurvey>,
    val banners: List<RespondoBanner>,
    /** delivery_id уже показанных/закрытых/отвеченных элементов. */
    val dismissed: Set<String>,
    /** Открыт лайтбокс изображения — оверлеи подавляются. */
    val lightboxOpen: Boolean,
    /** В композере есть набранный текст — оверлеи подавляются. */
    val composerHasText: Boolean,
)

/**
 * Чистый арбитр оверлеев: один за раз, приоритет survey > banner,
 * подавление при открытом лайтбоксе или непустом композере.
 */
object OverlayArbiter {
    fun decide(input: OverlayArbiterInput): OverlayDecision {
        if (input.lightboxOpen || input.composerHasText) return OverlayDecision.None
        input.surveys.firstOrNull { !input.dismissed.contains(it.deliveryId) }?.let {
            return OverlayDecision.Survey(it)
        }
        input.banners.firstOrNull { !input.dismissed.contains(it.deliveryId) }?.let {
            return OverlayDecision.Banner(it)
        }
        return OverlayDecision.None
    }
}
