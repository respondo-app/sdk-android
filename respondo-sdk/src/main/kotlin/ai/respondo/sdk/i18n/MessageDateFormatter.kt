package ai.respondo.sdk.i18n

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Compact date/time labels for the customer-facing message thread.
 *
 * Message timestamps are RFC3339 instants from the API. Labels are rendered in
 * the device timezone so a thread follows the visitor's local calendar day.
 * This intentionally uses java.text/java.util: the SDK supports API 24 and
 * must not require java.time/desugaring in the host application.
 */
object MessageDateFormatter {
    /** Parses a message `created_at`; null means no usable timestamp. */
    fun parse(value: String?): Date? = value?.let { OfficeHoursFormatter.parseRfc3339(it) }

    /** Stable local calendar key used to decide whether a day separator is needed. */
    fun dayKey(date: Date, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone).apply { time = date }
        return "%04d-%02d-%02d".format(
            Locale.ROOT,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** A short separator label: localized “today”, otherwise a compact date. */
    fun dayLabel(
        date: Date,
        lang: String,
        strings: (String, String) -> String = { locale, key -> Strings.get(locale, key) },
        zone: TimeZone = TimeZone.getDefault(),
        now: Date = Date(),
    ): String {
        if (dayKey(date, zone) == dayKey(now, zone)) {
            return strings(lang, "today").uppercase(Locale.forLanguageTag(lang))
        }
        val locale = Locale.forLanguageTag(lang)
        val pattern = if (lang.substringBefore('-').lowercase(Locale.ROOT) == "en") {
            "MM/dd/yyyy"
        } else {
            "dd.MM.yyyy"
        }
        return SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(date)
    }

    /** Compact wall-clock label matching the Flutter/iOS SDKs. */
    fun timeLabel(date: Date, lang: String, zone: TimeZone = TimeZone.getDefault()): String {
        val base = lang.substringBefore('-').lowercase(Locale.ROOT)
        val pattern = if (base == "en") "h:mm a" else "HH:mm"
        return SimpleDateFormat(pattern, Locale.forLanguageTag(lang)).apply { timeZone = zone }.format(date)
    }

    /** Safe parse for the backend's RFC3339 `created_at` field. */
    fun timeLabel(value: String?, lang: String, zone: TimeZone = TimeZone.getDefault()): String? =
        parse(value)?.let { timeLabel(it, lang, zone) }

    /** RFC3339 value for optimistic/local system messages. */
    fun nowRfc3339(now: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
}
