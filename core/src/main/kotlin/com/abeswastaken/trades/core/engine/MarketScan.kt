package com.abeswastaken.trades.core.engine

import com.abeswastaken.trades.core.market.Discovery
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.MarketData
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.scanner.ScanRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.Locale

/** A stock that showed up on a market-wide list and passed the price and volume rules. */
data class MarketCandidate(val quote: Quote, val tags: List<String>)

data class MarketScanContext(
    val today: LocalDate,
    val cash: Double,
    val candidates: List<MarketCandidate>,
    val headlines: List<Headline>,
    val focus: String?,
    val risk: RiskLevel = RiskLevel.NORMAL,
    /** Plain-code cross-checks of Reddit buzz on the candidates. */
    val buzz: List<String> = emptyList(),
    /** True when [cash] is a margin account's buying power rather than cash. */
    val margin: Boolean = false,
)

@Serializable
data class MarketPick(
    val ticker: String,
    /** COVERED_CALL (buy 100 and write calls), BUY (stock only) or WATCH (wait for a setup). */
    val idea: String,
    val thesis: String,
    val catalyst: String? = null,
    val risk: String,
    val confidence: String = "medium",
)

@Serializable
data class MarketScanReply(val marketRead: String, val picks: List<MarketPick> = emptyList())

data class MarketScanResult(
    val reply: MarketScanReply,
    /** Picks Claude made that weren't in the candidate list (dropped). */
    val dropped: List<String>,
    val spend: List<SpendItem>,
)

/**
 * Market-wide idea finder. Plain code gathers movers, most-active and trending names and filters them by
 * price and volume; Claude reads the market headlines and picks the few worth watching.
 */
class MarketScanner(private val llm: LlmClient) {

    suspend fun scan(model: ClaudeModel, ctx: MarketScanContext): MarketScanResult {
        if (ctx.candidates.isEmpty()) throw LlmException("No stocks passed the price and volume rules. Widen them in Settings.")
        val r = llm.send(
            LlmRequest(
                model = model, system = SYSTEM, user = request(ctx), maxTokens = 16000,
                effort = if (model == ClaudeModel.OPUS) "high" else "medium", jsonSchema = SCHEMA,
            )
        )
        val spend = listOf(SpendItem("market scan", model, r.servedModel, r.usage))
        if (r.stopReason == "max_tokens") throw LlmException("The reply was cut off. Try again.")
        val reply = parse(r.text)
        val allowed = ctx.candidates.map { it.quote.symbol }.toSet()
        val (keep, drop) = reply.picks.map { it.copy(ticker = it.ticker.trim().uppercase()) }.partition { it.ticker in allowed }
        return MarketScanResult(reply.copy(picks = keep.distinctBy { it.ticker }), drop.map { it.ticker }, spend)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        val SYSTEM = """
            You scan the US market for the owner of one small Schwab account. He sells covered calls (buys
            100 shares and writes calls against them) and buys stock he would be happy to hold. He uses your
            scan to build his watchlist; a separate step later decides the exact trade.

            Rules:
            - Pick ONLY from the candidate list. Every candidate already passed price, volume and options rules.
            - Pick the few names (at most 6) with a real reason to watch now: a catalyst in the headlines, a
              sharp move worth fading or following, or rich premium on a name he'd be fine owning. Fewer is fine.
            - Follow the owner's risk setting given in the request.
            - Skip pure pump moves (unless the risk setting is HIGH, and then call it a gamble), pending buyouts with no upside left, and anything 100 shares of he can't afford.
            - Earnings inside the next two weeks raise covered-call risk; say so.
            - idea: COVERED_CALL, BUY, or WATCH (interesting, but wait for a better entry).
            - thesis is one or two plain sentences; catalyst names the headline or event; risk is the main way
              it goes wrong. marketRead is two or three sentences on today's tone and what's driving it.
            - No disclaimers; the owner knows this is an experiment.
        """.trimIndent()

        fun request(c: MarketScanContext): String = buildString {
            appendLine("Today: ${c.today} (${c.today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})")
            if (c.margin) appendLine("Buying power (margin account, Reg T estimate): ${money(c.cash)} (100 shares must cost less than this for COVERED_CALL; borrowing costs interest)")
            else appendLine("Cash available: ${money(c.cash)} (100 shares must cost less than this for COVERED_CALL)")
            appendLine()
            appendLine("## Market headlines")
            if (c.headlines.isEmpty()) appendLine("None available.")
            c.headlines.forEach { h ->
                append("- ${h.title}")
                if (h.source.isNotBlank()) append(" [${h.source}]")
                h.sentiment?.let { append(String.format(Locale.US, " (sentiment %+.2f)", it)) }
                appendLine()
            }
            appendLine()
            appendLine("## Candidates")
            c.candidates.forEach { k ->
                val q = k.quote
                append("- ${q.symbol}")
                q.name?.let { append(" ($it)") }
                append(": ${px(q.price)}")
                q.changePercent?.let { append(String.format(Locale.US, ", %+.1f%% today", it)) }
                q.avgVolume?.let { append(", avg vol ${String.format(Locale.US, "%,d", it)}") }
                q.nextEarnings?.takeIf { !it.isBefore(c.today) }?.let { append(", earnings $it") }
                append(", on: ${k.tags.joinToString(", ")}")
                appendLine()
            }
            if (c.buzz.isNotEmpty()) {
                appendLine()
                appendLine("## Reddit chatter (unverified; trust it only where price, volume or news agree)")
                c.buzz.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("## Owner's risk setting")
            appendLine(c.risk.rules)
            if (!c.focus.isNullOrBlank()) {
                appendLine()
                appendLine("## Owner's note")
                appendLine(c.focus.trim())
            }
            appendLine()
            append("Scan these and give your picks.")
        }

        fun parse(text: String): MarketScanReply {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) throw LlmException("Claude's reply had no scan in it.")
            return try {
                json.decodeFromString(MarketScanReply.serializer(), text.substring(start, end + 1))
            } catch (e: Exception) {
                throw LlmException("Couldn't read Claude's scan: ${e.message}", e)
            }
        }

        fun encode(r: MarketScanReply): String = json.encodeToString(MarketScanReply.serializer(), r)

        val SCHEMA: Map<String, Any> = run {
            fun nullable(type: String, desc: String) =
                mapOf("anyOf" to listOf(mapOf("type" to type), mapOf("type" to "null")), "description" to desc)
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("marketRead", "picks"),
                "properties" to mapOf(
                    "marketRead" to mapOf("type" to "string"),
                    "picks" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "additionalProperties" to false,
                            "required" to listOf("ticker", "idea", "thesis", "catalyst", "risk", "confidence"),
                            "properties" to mapOf(
                                "ticker" to mapOf("type" to "string"),
                                "idea" to mapOf("type" to "string", "enum" to listOf("COVERED_CALL", "BUY", "WATCH")),
                                "thesis" to mapOf("type" to "string"),
                                "catalyst" to nullable("string", "The headline or event behind the pick"),
                                "risk" to mapOf("type" to "string"),
                                "confidence" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high")),
                            ),
                        ),
                    ),
                ),
            )
        }

        private fun money(v: Double) = String.format(Locale.US, "%s$%,.2f", if (v < 0) "-" else "", kotlin.math.abs(v))
        private fun px(v: Double) = String.format(Locale.US, "%.2f", v)
    }
}

/** The free, plain-code half of the market scan: which names are moving, and are they tradeable here? */
object MarketCandidates {
    private val LISTS = listOf("most_actives" to "most active", "day_gainers" to "top gainer", "day_losers" to "top loser")

    suspend fun gather(
        discovery: Discovery,
        market: MarketData,
        rules: ScanRules,
        cash: Double,
        exclude: Set<String>,
        max: Int = 40,
        /** More symbols to consider, with the tag to show (e.g. Reddit buzz). */
        extra: Map<String, String> = emptyMap(),
        log: (String) -> Unit = {},
    ): List<MarketCandidate> = coroutineScope {
        val lists = LISTS.map { (id, tag) ->
            async { runCatching { discovery.predefined(id, 50) }.onFailure { log("Yahoo $id failed: ${it.message}") }.getOrDefault(emptyList()) to tag }
        }
        val trending = async {
            runCatching { discovery.trending(25) }.onFailure { log("Yahoo trending failed: ${it.message}") }.getOrDefault(emptyList())
        }
        val tagged = linkedMapOf<String, Pair<Quote?, MutableList<String>>>()
        lists.awaitAll().forEach { (quotes, tag) ->
            quotes.forEach { q -> tagged.getOrPut(q.symbol) { q to mutableListOf() }.second += tag }
        }
        val trend = trending.await()
        trend.forEach { s -> tagged.getOrPut(s) { null to mutableListOf() }.second += "trending" }
        extra.forEach { (s, tag) -> tagged.getOrPut(s) { null to mutableListOf() }.second += tag }
        // Trending only gives symbols; quote the ones the screens didn't already price.
        val missing = tagged.filterValues { it.first == null }.keys.filter { looksLikeStock(it) }
        val extra = if (missing.isEmpty()) emptyMap() else runCatching { market.quotes(missing) }.getOrDefault(emptyMap())
        select(tagged.mapNotNull { (s, v) -> (v.first ?: extra[s])?.let { MarketCandidate(it, v.second) } }, rules, cash, exclude, max)
    }

    /** Pure filter, separated for tests. Names on several lists first, then the biggest movers. */
    fun select(all: List<MarketCandidate>, rules: ScanRules, cash: Double, exclude: Set<String>, max: Int): List<MarketCandidate> =
        all.asSequence()
            .filter { looksLikeStock(it.quote.symbol) && it.quote.symbol !in exclude }
            .filter { it.quote.price in rules.minPrice..rules.maxPrice }
            .filter { (it.quote.avgVolume ?: 0) >= rules.minAvgVolume }
            .filter { cash <= 0 || it.quote.price * 100 <= cash * 1.5 } // a little headroom for BUY ideas
            .sortedWith(compareByDescending<MarketCandidate> { it.tags.size }.thenByDescending { kotlin.math.abs(it.quote.changePercent ?: 0.0) })
            .take(max)
            .toList()

    /** Drops indexes, futures, crypto pairs and foreign listings (^GSPC, ES=F, BTC-USD, 7203.T). */
    fun looksLikeStock(s: String) = s.matches(Regex("[A-Z]{1,5}"))
}
