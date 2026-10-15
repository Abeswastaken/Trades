package com.abeswastaken.trades.core.market

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** US stock market session at a moment, with the next change. Times are in New York; show them in any zone. */
data class MarketSession(val phase: Phase, val nextChange: ZonedDateTime, val earlyClose: Boolean, val holiday: String?) {
    enum class Phase(val label: String) { OPEN("Open"), PRE("Pre-market"), AFTER("After hours"), CLOSED("Closed") }
}

/** NYSE/Nasdaq regular hours (9:30 to 16:00 ET, 13:00 on early-close days) with the holiday rules. */
object MarketClock {
    val NY: ZoneId = ZoneId.of("America/New_York")
    private val OPEN = LocalTime.of(9, 30)
    private val CLOSE = LocalTime.of(16, 0)
    private val EARLY = LocalTime.of(13, 0)
    private val PRE = LocalTime.of(4, 0)

    fun session(now: ZonedDateTime): MarketSession {
        val t = now.withZoneSameInstant(NY)
        val day = t.toLocalDate()
        val time = t.toLocalTime()
        val holiday = holiday(day)
        if (!isTradingDay(day)) {
            return MarketSession(MarketSession.Phase.CLOSED, nextOpen(day.plusDays(1)), false, holiday)
        }
        val close = closeTime(day)
        val early = close != CLOSE
        return when {
            time < PRE -> MarketSession(MarketSession.Phase.CLOSED, day.atTime(OPEN).atZone(NY), early, null)
            time < OPEN -> MarketSession(MarketSession.Phase.PRE, day.atTime(OPEN).atZone(NY), early, null)
            time < close -> MarketSession(MarketSession.Phase.OPEN, day.atTime(close).atZone(NY), early, null)
            // After-hours runs four hours past the close (to 17:00 on early-close days).
            time < close.plusHours(4) -> MarketSession(MarketSession.Phase.AFTER, nextOpen(day.plusDays(1)), early, null)
            else -> MarketSession(MarketSession.Phase.CLOSED, nextOpen(day.plusDays(1)), early, null)
        }
    }

    fun isTradingDay(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && holiday(d) == null

    private fun nextOpen(from: LocalDate): ZonedDateTime {
        var d = from
        while (!isTradingDay(d)) d = d.plusDays(1)
        return d.atTime(OPEN).atZone(NY)
    }

    fun closeTime(d: LocalDate): LocalTime {
        val thanksgiving = nth(d.year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)
        val early = d == thanksgiving.plusDays(1) ||
            (d.month == Month.DECEMBER && d.dayOfMonth == 24) ||
            (d.month == Month.JULY && d.dayOfMonth == 3 && d.dayOfWeek != DayOfWeek.FRIDAY)
        return if (early && isTradingDay(d)) EARLY else CLOSE
    }

    /** The holiday's name if the market is closed for one that day. */
    fun holiday(d: LocalDate): String? {
        val y = d.year
        // New Year's on a Saturday is not made up on the Friday before.
        val newYear = LocalDate.of(y, 1, 1).let { if (it.dayOfWeek == DayOfWeek.SUNDAY) it.plusDays(1) else it }
        val fixed = mapOf(
            newYear to "New Year's Day",
            observed(LocalDate.of(y, 6, 19)) to "Juneteenth",
            observed(LocalDate.of(y, 7, 4)) to "Independence Day",
            observed(LocalDate.of(y, 12, 25)) to "Christmas",
            nth(y, Month.JANUARY, DayOfWeek.MONDAY, 3) to "Martin Luther King Jr. Day",
            nth(y, Month.FEBRUARY, DayOfWeek.MONDAY, 3) to "Presidents' Day",
            easter(y).minusDays(2) to "Good Friday",
            LocalDate.of(y, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to "Memorial Day",
            nth(y, Month.SEPTEMBER, DayOfWeek.MONDAY, 1) to "Labor Day",
            nth(y, Month.NOVEMBER, DayOfWeek.THURSDAY, 4) to "Thanksgiving",
        )
        return fixed[d]
    }

    private fun observed(d: LocalDate) = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    private fun nth(y: Int, m: Month, dow: DayOfWeek, n: Int) =
        LocalDate.of(y, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    /** Gregorian Easter Sunday (anonymous algorithm). */
    fun easter(y: Int): LocalDate {
        val a = y % 19; val b = y / 100; val c = y % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }
}
