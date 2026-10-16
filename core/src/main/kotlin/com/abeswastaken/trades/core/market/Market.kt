package com.abeswastaken.trades.core.market

import java.time.LocalDate

data class Quote(
    val symbol: String,
    val price: Double,
    val change: Double? = null,
    val changePercent: Double? = null,
    val avgVolume: Long? = null,
    val name: String? = null,
    val nextEarnings: LocalDate? = null,
    val source: String = "",
    /** Shares traded so far today. */
    val volume: Long? = null,
    val previousClose: Double? = null,
    val open: Double? = null,
    val high52: Double? = null,
    val low52: Double? = null,
) {
    /** Today's volume against the 3-month average, when both are known. */
    val volumeRatio: Double? get() = volume?.let { v -> avgVolume?.takeIf { it > 0 }?.let { v.toDouble() / it } }
}

/** Next ex-dividend date and the per-payment amount (annual rate / 4 when only the rate is known). */
data class Dividend(val symbol: String, val exDate: LocalDate?, val amount: Double?)

data class OptionQuote(
    val underlying: String,
    val strike: Double,
    val expiry: LocalDate,
    val bid: Double,
    val ask: Double,
    val last: Double?,
    val openInterest: Long,
    val volume: Long,
) {
    val mid get() = if (bid > 0 && ask > 0) (bid + ask) / 2 else (last ?: bid)

    /** False when there's no live bid (Yahoo shows 0 outside market hours); [premium] is then the last trade. */
    val liveBid get() = bid > 0

    /** What writing this call should bring in: the bid, or the last trade when the market is closed. */
    val premium get() = if (bid > 0) bid else (last ?: 0.0)

    /** Yahoo also zeroes open interest overnight, so with no live quote a 0 means "unknown", not "illiquid". */
    fun meetsOpenInterest(min: Long) = openInterest >= min || (!liveBid && openInterest == 0L)
}

data class Headline(
    val symbol: String,
    val title: String,
    val publishedEpochSec: Long?,
    val url: String?,
    val source: String = "",
    /** -1 (bearish) to +1 (bullish), from sources that score stories. */
    val sentiment: Double? = null,
)

interface MarketData {
    val name: String
    suspend fun quotes(symbols: List<String>): Map<String, Quote>
    /** Calls on [symbol] expiring between [minDte] and [maxDte] days from [today]. */
    suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): List<OptionQuote>
    suspend fun news(symbol: String, limit: Int = 8): List<Headline>
}

/** Every listed expiry, plus the calls for the first expiry inside the window asked for. */
data class ChainSnapshot(val expirations: List<LocalDate>, val calls: List<OptionQuote>)

/** Market-wide lists for finding names that aren't on the watchlist yet (Yahoo only). */
interface Discovery {
    /** US equities in a price band with enough volume, most traded first. */
    suspend fun screen(minPrice: Double, maxPrice: Double, minAvgVolume: Long, size: Int): List<Quote>
    /** Yahoo's saved screens: day_gainers, day_losers, most_actives, ... */
    suspend fun predefined(list: String, size: Int): List<Quote>
    suspend fun trending(size: Int): List<String>
    suspend fun nearestCalls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): ChainSnapshot
}

class MarketDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Tries each source in order and uses the first one that answers. Options chains only come from
 * sources that support them (Yahoo); quotes and news can fall back to Finnhub.
 */
class FallbackMarketData(private val sources: List<MarketData>, private val log: (String) -> Unit = {}) : MarketData {
    override val name = sources.joinToString("+") { it.name }

    override suspend fun quotes(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Quote>()
        var lastError: Exception? = null
        for (s in sources) {
            val missing = symbols.filter { it !in result }
            if (missing.isEmpty()) break
            try {
                result += s.quotes(missing)
            } catch (e: Exception) {
                lastError = e
                log("quotes via ${s.name} failed: ${e.message}")
            }
        }
        if (result.isEmpty() && lastError != null) throw MarketDataException("No quote source answered", lastError)
        return result
    }

    override suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): List<OptionQuote> {
        var lastError: Exception? = null
        for (s in sources) {
            try {
                return s.calls(symbol, today, minDte, maxDte)
            } catch (e: UnsupportedOperationException) {
                continue
            } catch (e: Exception) {
                lastError = e
                log("options via ${s.name} failed: ${e.message}")
            }
        }
        throw MarketDataException("No options source answered for $symbol", lastError)
    }

    override suspend fun news(symbol: String, limit: Int): List<Headline> {
        for (s in sources) {
            try {
                val n = s.news(symbol, limit)
                if (n.isNotEmpty()) return n
            } catch (e: Exception) {
                log("news via ${s.name} failed: ${e.message}")
            }
        }
        return emptyList()
    }
}
