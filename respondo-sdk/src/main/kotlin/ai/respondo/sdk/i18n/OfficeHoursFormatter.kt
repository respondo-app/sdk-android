package ai.respondo.sdk.i18n

import ai.respondo.sdk.transport.dto.OfficeHoursDto
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Разобранная плашка офис-часов для UI. */
data class OfficeHoursDisplay(
    val open: Boolean,
    val text: String,
)

/**
 * Форматирует плашку доступности из `office_hours` (см. `behavior.md` §8). Открыто → строка времени ответа
 * по `reply_time`; закрыто → «Мы офлайн. Вернёмся {time}», где {time} = сегодня `HH:MM` / завтра `HH:MM` /
 * `<день недели> HH:MM` в локали устройства.
 *
 * Только java.util/java.text: minSdk 24, а java.time появился в API 26 — на Android 7 обращение к нему бросало
 * NoClassDefFoundError (Error, не Exception) и роняло host-приложение. Desugaring тут не выход: библиотека с ним
 * требует включить desugaring и в каждом приложении клиента.
 */
object OfficeHoursFormatter {

    fun format(hours: OfficeHoursDto?, lang: String, zone: TimeZone = TimeZone.getDefault()): OfficeHoursDisplay? {
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

    private fun formatReturnTime(rfc3339: String, lang: String, zone: TimeZone): String? {
        val instant = parseRfc3339(rfc3339) ?: return null
        val at = Calendar.getInstance(zone).apply { time = instant }
        val today = Calendar.getInstance(zone)
        val hhmm = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }.format(instant)
        return when (daysBetween(today, at)) {
            0 -> hhmm
            1 -> "${Strings.get(lang, "tomorrow")} $hhmm"
            else -> {
                val weekday = SimpleDateFormat("EEEE", Locale.forLanguageTag(lang)).apply { timeZone = zone }.format(instant)
                "$weekday $hhmm"
            }
        }
    }

    /** Разница в календарных днях зоны (по дате, не по 24-часовым интервалам). */
    private fun daysBetween(from: Calendar, to: Calendar): Int {
        val a = Calendar.getInstance(from.timeZone).apply {
            clear(); set(from.get(Calendar.YEAR), from.get(Calendar.MONTH), from.get(Calendar.DAY_OF_MONTH))
        }
        val b = Calendar.getInstance(to.timeZone).apply {
            clear(); set(to.get(Calendar.YEAR), to.get(Calendar.MONTH), to.get(Calendar.DAY_OF_MONTH))
        }
        return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
    }

    /** RFC 3339 (`2026-09-30T09:00:00Z`, `…+03:00`, с долями секунды или без). null — не разобрать. */
    internal fun parseRfc3339(value: String): Date? {
        val m = RFC3339.matchEntire(value.trim()) ?: return null
        val (base, fraction, offset) = m.destructured
        val millis = fraction.take(3).padEnd(3, '0').ifEmpty { "000" }
        val normalized = "$base.$millis" + if (offset.equals("Z", ignoreCase = true)) "+00:00" else offset
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).parse(normalized)
        } catch (_: ParseException) {
            null
        }
    }

    private val RFC3339 = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:\d{2})""")
}
