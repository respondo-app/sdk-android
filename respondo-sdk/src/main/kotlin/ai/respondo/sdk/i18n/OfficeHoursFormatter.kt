package ai.respondo.sdk.i18n

import ai.respondo.sdk.transport.dto.OfficeHoursDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Разобранная плашка офис-часов для UI. */
data class OfficeHoursDisplay(
    val open: Boolean,
    val text: String,
)

/**
 * Форматирует плашку доступности из `office_hours` (см. `behavior.md` §8). Открыто → строка времени ответа
 * по `reply_time`; закрыто → «Мы офлайн. Вернёмся {time}», где {time} = сегодня `HH:MM` / завтра `HH:MM` /
 * `<день недели> HH:MM` в локали устройства.
 */
object OfficeHoursFormatter {

    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

    fun format(hours: OfficeHoursDto?, lang: String, zone: ZoneId = ZoneId.systemDefault()): OfficeHoursDisplay? {
        if (hours == null) return null
        return if (hours.open) {
            OfficeHoursDisplay(open = true, text = replyTimeText(hours.replyTime, lang))
        } else {
            val time = hours.nextOpenAt?.let { formatReturnTime(it, lang, zone) }
            val template = Strings.get(lang, "awayBack")
            val text = if (time != null) template.replace("{time}", time) else template.replace("{time}", "").trim()
            OfficeHoursDisplay(open = false, text = text)
        }
    }

    /** reply_time может быть кодом (few_minutes/few_hours/a_day) или готовой строкой сервера. */
    private fun replyTimeText(replyTime: String?, lang: String): String = when (replyTime) {
        "few_minutes" -> Strings.get(lang, "replyTimeFewMinutes")
        "few_hours" -> Strings.get(lang, "replyTimeFewHours")
        "a_day" -> Strings.get(lang, "replyTimeADay")
        null, "" -> Strings.get(lang, "replyTimeAsSoonAsPossible")
        else -> replyTime
    }

    private fun formatReturnTime(rfc3339: String, lang: String, zone: ZoneId): String? {
        return try {
            val instant = OffsetDateTime.parse(rfc3339).toInstant()
            val zoned = instant.atZone(zone)
            val date = zoned.toLocalDate()
            val today = LocalDate.now(zone)
            val hhmm = zoned.format(TIME_FMT)
            when (date) {
                today -> hhmm
                today.plusDays(1) -> "${Strings.get(lang, "tomorrow")} $hhmm"
                else -> {
                    val locale = Locale.forLanguageTag(lang)
                    val weekday = zoned.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                    "$weekday $hhmm"
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
