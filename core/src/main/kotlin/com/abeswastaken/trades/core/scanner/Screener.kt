package com.abeswastaken.trades.core.scanner

import com.abeswastaken.trades.core.market.ChainSnapshot
import com.abeswastaken.trades.core.market.Discovery
import com.abeswastaken.trades.core.market.MarketData
import com.abeswastaken.trades.core.market.OptionQuote
import com.abeswastaken.trades.core.market.Quote
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** "Which stocks between $x and $y have weekly calls worth writing?" Defaults suit a small account. */
data class ScreenerRules(
    val minPrice: Double = 5.0,
    val maxPrice: Double = 20.0,
    val minAvgVolume: Long = 1_000_000,
    val weekliesOnly: Boolean = true,
    /** Expiry window for the call to write. 1–10 days means "this week's or next week's". */
    val minDte: Int = 1,
    val maxDte: Int = 10,
    val minOtmPercent: Double = 0.0,
    val minOpenInterest: Long = 50,
    val minBid: Double = 0.05,
    /** How many names to pull from the market screen before checking chains. */
    val universe: Int = 60,
    val maxResults: Int = 25,
)

data class ScreenerHit(
    val quote: Quote,
    /** The nearest out-of-the-money call that passes the liquidity rules. */
    val option: OptionQuote,
    val dte: Long,
    val premiumPercent: Double,
    val annualizedPercent: Double,
    val otmPercent: Double,
    val weeklies: Boolean,
    /** Cash for 100 shares less the premium collected. */
    val netCostPer100: Double,
) {
    /** Return if the shares are called away at the strike: premium plus the gain to the strike. */
    val ifCalledPercent get() = (option.bid + option.strike - quote.price) / quote.price * 100
}

data class ScreenerResult(
    val hits: List<ScreenerHit>,
    val scanned: Int,
    val noWeeklies: Int,
    val errors: Map<String, String>,
    /** Where the list of names came from (Yahoo screener, or the built-in list if that failed). */
    val universeSource: String,
)

object CoveredCallScreener {

    /** Weekly options list an expiry nearly every week: at least four inside the next five weeks. */
    fun hasWeeklies(expirations: List<LocalDate>, today: LocalDate): Boolean =
        expirations.count { ChronoUnit.DAYS.between(today, it) in 0..35 } >= 4

    /** Picks the call to write: the nearest strike at or above the OTM floor that passes OI and bid. */
    fun best(q: Quote, snap: ChainSnapshot, today: LocalDate, rules: ScreenerRules): ScreenerHit? {
        val weeklies = hasWeeklies(snap.expirations, today)
        if (rules.weekliesOnly && !weeklies) return null
        val floor = q.price * (1 + rules.minOtmPercent / 100)
        val o = snap.calls
            .filter { ChronoUnit.DAYS.between(today, it.expiry) in rules.minDte..rules.maxDte }
            .filter { it.strike >= floor && it.openInterest >= rules.minOpenInterest && it.bid >= rules.minBid }
            .minByOrNull { it.strike } ?: return null
        val dte = ChronoUnit.DAYS.between(today, o.expiry).coerceAtLeast(1)
        val prem = o.bid / q.price * 100
        return ScreenerHit(
            quote = q, option = o, dte = dte, premiumPercent = prem, annualizedPercent = prem * 365.0 / dte,
            otmPercent = (o.strike / q.price - 1) * 100, weeklies = weeklies, netCostPer100 = (q.price - o.bid) * 100,
        )
    }

    fun passesUnderlying(q: Quote, rules: ScreenerRules) =
        q.price in rules.minPrice..rules.maxPrice && (q.avgVolume ?: 0) >= rules.minAvgVolume

    suspend fun run(
        discovery: Discovery,
        market: MarketData,
        today: LocalDate,
        rules: ScreenerRules,
        progress: (String) -> Unit = {},
    ): ScreenerResult = coroutineScope {
        progress("Finding stocks between $${rules.minPrice} and $${rules.maxPrice}")
        val (names, source) = runCatching {
            discovery.screen(rules.minPrice, rules.maxPrice, rules.minAvgVolume, rules.universe) to "Yahoo screener"
        }.getOrNull()?.takeIf { it.first.isNotEmpty() }
            ?: (runCatching { market.quotes(FALLBACK_UNIVERSE).values.toList() }.getOrDefault(emptyList()) to "built-in list")
        val universe = names.filter { passesUnderlying(it, rules) }.distinctBy { it.symbol }.take(rules.universe)

        progress("Checking option chains on ${universe.size} stocks")
        val gate = Semaphore(4)
        val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
        val noWeeklies = java.util.concurrent.atomic.AtomicInteger()
        val hits = universe.map { q ->
            async {
                gate.withPermit {
                    try {
                        val snap = discovery.nearestCalls(q.symbol, today, rules.minDte, rules.maxDte)
                        if (rules.weekliesOnly && !hasWeeklies(snap.expirations, today)) {
                            noWeeklies.incrementAndGet()
                            null
                        } else best(q, snap, today, rules)
                    } catch (e: Exception) {
                        errors[q.symbol] = e.message ?: e.javaClass.simpleName
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
        ScreenerResult(
            hits = hits.sortedByDescending { it.annualizedPercent }.take(rules.maxResults),
            scanned = universe.size, noWeeklies = noWeeklies.get(), errors = errors.toMap(), universeSource = source,
        )
    }

    /** Liquid, optionable US names across sectors, used only when the Yahoo screener won't answer. */
    val FALLBACK_UNIVERSE = listOf(
        "F", "T", "SOFI", "PFE", "INTC", "BAC", "KMI", "VZ", "WBD", "NU", "AAL", "CCL", "NCLH", "SNAP", "PLTR",
        "RIVN", "LCID", "HOOD", "AMC", "GME", "NIO", "XPEV", "LYFT", "UBER", "PINS", "CSCO", "KO", "WFC", "C",
        "GM", "HPQ", "HPE", "CMCSA", "MO", "KHC", "PARA", "VALE", "ITUB", "BBD", "ABEV", "GOLD", "KGC", "AG",
        "CLF", "X", "AA", "FCX", "OXY", "DVN", "MRO", "RIG", "SLB", "HAL", "ET", "EPD", "BTG", "MARA", "RIOT",
        "CLSK", "AFRM", "UPST", "DKNG", "CHPT", "PLUG", "RUN", "ENPH", "QS", "JBLU", "UAL", "DAL", "M", "GPS",
        "KSS", "WBA", "CVS", "BMY", "GILD", "MRNA", "TEVA", "VTRS", "SIRI", "ROKU", "PYPL", "SQ", "COIN", "BABA",
        "JD", "PDD", "TSM", "AMD", "MU", "SMCI", "ARM", "SHOP", "DIS", "NKE", "SBUX", "TGT", "WMT", "CSX", "UNP",
        "SPY", "QQQ", "IWM", "XLF", "XLE", "SLV", "GDX", "TLT", "SQQQ", "TQQQ", "SOXL", "ARKK", "KRE", "EEM",
    )
}
