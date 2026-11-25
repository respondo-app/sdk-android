package ai.respondo.sdk

import ai.respondo.sdk.i18n.MessageDateFormatter
import ai.respondo.sdk.i18n.OfficeHoursFormatter
import ai.respondo.sdk.transport.dto.OfficeHoursDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.TimeZone

/** Офис-часы без java.time (minSdk 24). */
class OfficeHoursFormatterTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun parsesRfc3339Variants() {
        val a = OfficeHoursFormatter.parseRfc3339("2026-09-30T09:00:00Z")!!
        val b = OfficeHoursFormatter.parseRfc3339("2026-09-30T12:00:00.123456+03:00")!!
        assertEquals(a.time, b.time - 123)
        assertNull(OfficeHoursFormatter.parseRfc3339("30.09.2026 09:00"))
    }

    @Test
    fun closedTodayShowsTimeOnly() {
        val soon = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
            .apply { timeZone = utc }.format(java.util.Date())
        val out = OfficeHoursFormatter.format(OfficeHoursDto(open = false, nextOpenAt = soon), "en", utc)
        assertNotNull(out)
        assertTrue(out!!.text, Regex("""\d{2}:\d{2}""").containsMatchIn(out.text))
    }

    @Test
    fun messageDateFormatterUsesDeviceCalendarAndCompactClock() {
        val at = MessageDateFormatter.parse("2026-10-07T21:30:00Z")!!
        assertEquals("2026-10-07", MessageDateFormatter.dayKey(at, utc))
        assertEquals("9:30 PM", MessageDateFormatter.timeLabel(at, "en", utc))
        assertEquals("21:30", MessageDateFormatter.timeLabel(at, "ru", utc))
        assertEquals("07.10.2026", MessageDateFormatter.dayLabel(at, "ru", zone = utc, now = Date(at.time - 86_400_000)))
    }

    @Test
    fun messageDateFormatterRejectsMalformedTimestamp() {
        assertNull(MessageDateFormatter.parse("not-a-date"))
        assertNull(MessageDateFormatter.timeLabel(null, "en", utc))
    }
}
