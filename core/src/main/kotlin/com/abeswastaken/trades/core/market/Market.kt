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
)

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
}

data class Headline(
    val symbol: String,
    val title: String,
    val publishedEpochSec: Long?,
    val url: String?,
    val source: String = "",
)

interface MarketData {
    val name: String
    suspend fun quotes(symbols: List<String>): Map<String, Quote>
    /** Calls on [symbol] expiring between [minDte] and [maxDte] days from [today]. */
    suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): List<OptionQuote>
    suspend fun news(symbol: String, limit: Int = 8): List<Headline>
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
