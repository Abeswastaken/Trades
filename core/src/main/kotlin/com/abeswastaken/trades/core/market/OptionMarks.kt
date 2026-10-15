package com.abeswastaken.trades.core.market

import com.abeswastaken.trades.core.ledger.Position
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Instrument
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Current market for an open option position, plus what that means for the position. */
data class OptionMark(val quote: OptionQuote, val position: Position) {
    val mark get() = quote.mid
    /** Dollars gained (+) or lost (-) if closed at the mid now. */
    val unrealized get() = (mark - position.avgCost) * position.quantity * 100
    /** For a short option: share of the premium already kept. */
    val premiumCapturedPercent: Double?
        get() = if (position.quantity < 0 && position.avgCost > 0) (1 - mark / position.avgCost) * 100 else null
}

object OptionMarks {
    /**
     * Fetches marks for open call positions, one chain request per ticker and expiry window.
     * Puts and expired contracts are skipped (Yahoo's chain call here only returns calls).
     */
    suspend fun fetch(market: MarketData, positions: List<Position>, today: LocalDate, log: (String) -> Unit = {}): Map<Instrument, OptionMark> {
        val calls = positions.filter {
            it.instrument.type == AssetType.CALL && it.instrument.expiry?.isBefore(today) == false
        }
        val out = mutableMapOf<Instrument, OptionMark>()
        for ((ticker, group) in calls.groupBy { it.instrument.ticker }) {
            val maxDte = group.maxOf { ChronoUnit.DAYS.between(today, it.instrument.expiry!!) }.toInt()
            val chain = try {
                market.calls(ticker, today, 0, maxDte)
            } catch (e: Exception) {
                log("Option marks for $ticker failed: ${e.message}"); continue
            }
            for (p in group) {
                val q = chain.firstOrNull { it.expiry == p.instrument.expiry && abs(it.strike - (p.instrument.strike ?: -1.0)) < 0.001 }
                if (q != null) out[p.instrument] = OptionMark(q, p)
            }
        }
        return out
    }
}
