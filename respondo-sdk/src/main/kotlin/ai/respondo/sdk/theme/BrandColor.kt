package ai.respondo.sdk.theme

import kotlin.math.pow

/**
 * Контраст бренд-цвета. Повторяет `widget/src/brand-color.ts::linkInk` (WCAG sRGB relative luminance),
 * чтобы результат не расходился с вебом. Порог `0.62` и формула — дословно из веба.
 */
object BrandColor {

    /** Тёмный ink-текст. */
    const val INK: Long = 0xFF1F2937
    /** Белый текст. */
    const val WHITE: Long = 0xFFFFFFFF
    /** Фон светлого пузыря ассистента. */
    const val ASSISTANT_BUBBLE: Long = 0xFFF3F4F6

    private const val LUMINANCE_THRESHOLD = 0.62

    /** Разбирает `#RRGGBB` (или `#AARRGGBB`) в ARGB-Long с непрозрачной альфой. При ошибке → [fallback]. */
    fun parseHex(hex: String?, fallback: Long): Long {
        if (hex.isNullOrBlank()) return fallback
        val cleaned = hex.trim().removePrefix("#")
        return try {
            when (cleaned.length) {
                6 -> 0xFF000000L or cleaned.toLong(16)
                8 -> cleaned.toLong(16)
                else -> fallback
            }
        } catch (_: NumberFormatException) {
            fallback
        }
    }

    /** Относительная яркость цвета (WCAG), для канала ARGB-Long. */
    fun relativeLuminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** Контрастный текст/иконка на заливке primary: L > 0.62 → тёмный ink, иначе белый. */
    fun onPrimary(primaryArgb: Long): Long =
        if (relativeLuminance(primaryArgb) > LUMINANCE_THRESHOLD) INK else WHITE

    /** Цвет ссылки на светлом пузыре ассистента: L > 0.62 → ink, иначе сам бренд-цвет. */
    fun linkInk(primaryArgb: Long): Long =
        if (relativeLuminance(primaryArgb) > LUMINANCE_THRESHOLD) INK else primaryArgb

    private fun lin(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
