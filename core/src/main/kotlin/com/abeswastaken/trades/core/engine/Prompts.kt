package com.abeswastaken.trades.core.engine

import com.abeswastaken.trades.core.ledger.LedgerState
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.scanner.CallCandidate
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A past recommendation and what came of it, fed back so Claude can learn from the record. */
data class PastRec(
    val date: LocalDate,
    val summary: String,
    val status: String,
    val realized: Double?,
)

data class RecContext(
    val today: LocalDate,
    val ledger: LedgerState,
    val startingCash: Double,
    val quotes: Map<String, Quote>,
    val candidates: List<CallCandidate>,
    val newsDigest: String?,
    val history: List<PastRec>,
    val focus: String?,
    val watchlist: List<String>,
    val optionMarks: Map<com.abeswastaken.trades.core.model.Instrument, com.abeswastaken.trades.core.market.OptionMark> = emptyMap(),
)

object Prompts {

    /** Stable across calls so it stays cached. Never put dates or numbers in here. */
    val SYSTEM = """
        You are the trade picker for one small Schwab cash account. The owner places every order by hand,
        exactly as you recommend it, and compares this account at month-end with one where he picks his own
        trades. Your job: give ONE recommendation for what to do right now, with the math shown.

        Rules:
        - Use only the data in the message. If a number you need is missing, say so in the risks and lean to HOLD.
        - Cash account: no margin, no shorting stock, no naked options. Calls may only be sold against shares
          already held (SELL_CALL, 100 shares per contract) or as part of a buy-write you recommend as BUY first.
        - Never recommend spending more cash than is available. Keep quantities whole.
        - Prefer the pre-screened call candidates when recommending SELL_CALL; they already pass liquidity and
          premium filters. Consider earnings dates: an earnings event before expiry raises assignment and gap risk.
        - For open short calls, consider BUY_TO_CLOSE when most of the premium is captured or the stock is
          running through the strike.
        - HOLD is a good answer when nothing clears the bar. Do not force a trade.
        - Look at the outcome of past recommendations and avoid repeating what lost money.
        - limitPrice is per share (option premium per share, not per contract). For SELL_CALL give strike and
          expiry (YYYY-MM-DD). math.cashImpact is positive when cash goes out and negative when it comes in.
        - summary is one line the owner can read at a glance, like "Sell 3 F Nov 21 13.50 calls at 0.18".
        - Keep rationale and risks to a few plain sentences each. No disclaimers; the owner knows this is
          an experiment and not financial advice.
    """.trimIndent()

    val DIGEST_SYSTEM = """
        You condense stock news for a trader. For each ticker, write at most three short bullets of what could
        move the price in the next few weeks (earnings, guidance, deals, legal, macro). Skip fluff and
        duplicates. If nothing matters for a ticker, write "nothing material". Plain text, no preamble.
    """.trimIndent()

    fun digestRequest(headlines: Map<String, List<Headline>>): String = buildString {
        for ((sym, list) in headlines) {
            if (list.isEmpty()) continue
            appendLine("## $sym")
            list.forEach { appendLine("- ${it.title}") }
        }
    }.trim()

    fun recommendRequest(c: RecContext): String = buildString {
        val l = c.ledger
        appendLine("Today: ${c.today} (${c.today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})")
        appendLine()
        appendLine("## Account")
        appendLine("Starting cash: ${money(c.startingCash)}")
        appendLine("Cash now: ${money(l.cash)}")
        appendLine("Realized P&L to date: ${money(l.realizedTotal)}")
        appendLine()
        appendLine("## Open positions")
        if (l.positions.isEmpty()) appendLine("None.")
        for (p in l.positions) {
            val inst = p.instrument
            if (inst.type == AssetType.STOCK) {
                val q = c.quotes[inst.ticker]
                val mark = q?.price
                val unreal = mark?.let { (it - p.avgCost) * p.quantity }
                appendLine(
                    "- ${inst.ticker}: ${num(p.quantity)} shares, avg cost ${px(p.avgCost)}" +
                        (mark?.let { ", last ${px(it)}, unrealized ${money(unreal!!)}" } ?: ", no quote") +
                        (q?.nextEarnings?.let { ", next earnings $it" } ?: "") +
                        ", calls still writable: ${l.coverableContracts(inst.ticker)}"
                )
            } else {
                val dte = inst.expiry?.let { ChronoUnit.DAYS.between(c.today, it) }
                val side = if (p.quantity < 0) "short" else "long"
                appendLine(
                    "- ${inst.label}: $side ${num(kotlin.math.abs(p.quantity))} contracts at ${px(p.avgCost)}" +
                        (dte?.let { ", $it days to expiry" } ?: "") +
                        (c.quotes[inst.ticker]?.let { ", stock last ${px(it.price)}" } ?: "") +
                        (c.optionMarks[inst]?.let { m ->
                            ", now bid ${px(m.quote.bid)} ask ${px(m.quote.ask)}" +
                                (m.premiumCapturedPercent?.let { ", ${pct(it)} of premium captured" } ?: "")
                        } ?: ", no current option quote")
                )
            }
        }
        appendLine()
        appendLine("## Watchlist quotes")
        val shown = (c.watchlist + l.positions.map { it.instrument.ticker }).distinct()
        if (shown.isEmpty()) appendLine("Watchlist is empty.")
        for (sym in shown) {
            val q = c.quotes[sym]
            if (q == null) { appendLine("- $sym: no quote"); continue }
            appendLine(
                "- $sym: ${px(q.price)}" +
                    (q.changePercent?.let { " (${pct(it)} today)" } ?: "") +
                    (q.avgVolume?.let { ", avg vol ${it}" } ?: "") +
                    (q.nextEarnings?.let { ", earnings $it" } ?: "")
            )
        }
        appendLine()
        appendLine("## Pre-screened call candidates")
        if (c.candidates.isEmpty()) appendLine("None passed the filters today.")
        for (k in c.candidates) {
            val o = k.option
            appendLine(
                "- ${k.kind}: ${k.quote.symbol} ${o.expiry} ${px(o.strike)}C, " +
                    (if (o.liveBid) "bid ${px(o.bid)} ask ${px(o.ask)}, " else "last ${px(o.premium)} (no live bid, market closed), ") +
                    (if (o.liveBid) "OI ${o.openInterest}, " else "") + "${k.dte} DTE, ${pct(k.otmPercent)} OTM, premium ${pct(k.premiumPercent)} " +
                    "(${pct(k.annualizedPercent)} annualized), up to ${k.contracts} contracts" +
                    (k.quote.nextEarnings?.takeIf { !it.isAfter(o.expiry) }?.let { ", EARNINGS $it before expiry" } ?: "")
            )
        }
        appendLine()
        appendLine("## News digest")
        appendLine(c.newsDigest?.takeIf { it.isNotBlank() } ?: "Not available.")
        appendLine()
        appendLine("## Past recommendations (newest first)")
        if (c.history.isEmpty()) appendLine("None yet.")
        for (h in c.history) {
            appendLine("- ${h.date}: ${h.summary} [${h.status}${h.realized?.let { ", realized ${money(it)}" } ?: ""}]")
        }
        if (!c.focus.isNullOrBlank()) {
            appendLine()
            appendLine("## Owner's note for this call")
            appendLine(c.focus.trim())
        }
        appendLine()
        append("Give one recommendation now.")
    }

    private fun money(v: Double) = String.format(Locale.US, "%s$%,.2f", if (v < 0) "-" else "", kotlin.math.abs(v))
    private fun px(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v)
    private fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** JSON schema for structured output; mirrors [Recommendation]. */
    val RECOMMENDATION_SCHEMA: Map<String, Any> = run {
        // anyOf with null is the most widely supported way to express "optional" in structured outputs.
        fun nullable(type: String, desc: String) =
            mapOf("anyOf" to listOf(mapOf("type" to type), mapOf("type" to "null")), "description" to desc)
        mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf(
                "action", "ticker", "quantity", "limitPrice", "strike", "expiry",
                "summary", "rationale", "risks", "exitPlan", "confidence", "math",
            ),
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to RecAction.entries.map { it.name }),
                "ticker" to nullable("string", "Stock symbol; null only for HOLD with no ticker"),
                "quantity" to nullable("number", "Shares for stock, contracts for options"),
                "limitPrice" to nullable("number", "Limit price per share"),
                "strike" to nullable("number", "Option strike, options only"),
                "expiry" to nullable("string", "Option expiry YYYY-MM-DD, options only"),
                "summary" to mapOf("type" to "string"),
                "rationale" to mapOf("type" to "string"),
                "risks" to mapOf("type" to "string"),
                "exitPlan" to nullable("string", "When to take profit, cut, or roll"),
                "confidence" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high")),
                "math" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("cashImpact", "maxGain", "maxLoss", "breakeven", "annualizedYieldPercent"),
                    "properties" to mapOf(
                        "cashImpact" to nullable("number", "Dollars out (+) or in (-) at the limit"),
                        "maxGain" to nullable("number", "Dollars"),
                        "maxLoss" to nullable("number", "Dollars"),
                        "breakeven" to nullable("number", "Stock price"),
                        "annualizedYieldPercent" to nullable("number", "For option income trades"),
                    ),
                ),
            ),
        )
    }
}
