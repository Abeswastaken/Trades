package com.abeswastaken.trades.core.engine

import com.abeswastaken.trades.core.ledger.LedgerState
import com.abeswastaken.trades.core.market.Dividend
import com.abeswastaken.trades.core.market.EdgarSummary
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.MarketClock
import com.abeswastaken.trades.core.market.OptionQuote
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Instrument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** How loud an alert is. Quiet mode only buzzes for [CRITICAL]; the rest go to the inbox. */
enum class AlertLevel(val label: String) {
    CRITICAL("Money at risk"),
    NOTICE("Heads-up"),
    OPPORTUNITY("Opportunity"),
}

enum class AlertKind {
    SHORT_ITM, SHORT_NEAR, EXPIRING, EX_DIVIDEND, MOVE, RANGE_BREAK, EARNINGS, LONG_SHOT, VOLUME, OPTIONS_FLOW,
    INSIDER, OFFERING, BRIEF, TEST,
}

/** Which scheduled run produced an alert. */
enum class CheckKind(val label: String) {
    OPEN("Open check"),
    INTRADAY("Intraday watch"),
    CLOSE("Before-close check"),
    MANUAL("Check now"),
}

/** One alert. [key] is stable for "the same news", so it only fires once (e.g. per ticker per day). */
data class AlertEvent(
    val key: String,
    val kind: AlertKind,
    val level: AlertLevel,
    val title: String,
    val body: String,
    val ticker: String? = null,
)

/** Settings → Alerts. Everything is off until [enabled] is switched on. */
data class AlertRules(
    val enabled: Boolean = false,
    /** 9:31 ET full market read (plain checks, then Claude). */
    val openCheck: Boolean = true,
    val openModel: ClaudeModel = ClaudeModel.SONNET,
    /** 30 minutes before the close: options expiring today. */
    val closeCheck: Boolean = true,
    /** Plain-code watch every [intervalMin] minutes while the market's open. */
    val intraday: Boolean = true,
    val intervalMin: Int = 15,
    /** Only money-at-risk alerts buzz; the rest land silently in the inbox. */
    val quiet: Boolean = false,
    val moves: Boolean = true,
    val movePercent: Double = 5.0,
    val shortCalls: Boolean = true,
    val nearPercent: Double = 2.0,
    val exDividend: Boolean = true,
    val earnings: Boolean = true,
    val longShots: Boolean = true,
    val unusual: Boolean = false,
    val volumeMultiple: Double = 3.0,
    val insider: Boolean = false,
) {
    /** Whether an alert makes the phone buzz, or just goes to the inbox. */
    fun buzzes(e: AlertEvent) = !quiet || e.level == AlertLevel.CRITICAL

    companion object {
        val INTERVALS = listOf(15, 30)
    }
}

/** Everything one check looked at. Only [now], [check], [rules], [ledger] and [quotes] are required. */
data class AlertContext(
    val now: ZonedDateTime,
    val check: CheckKind,
    val rules: AlertRules,
    val ledger: LedgerState,
    val quotes: Map<String, Quote>,
    val watchlist: List<String> = emptyList(),
    /** Current quotes for held option contracts (calls only today). */
    val optionQuotes: Map<Instrument, OptionQuote> = emptyMap(),
    val dividends: Map<String, Dividend> = emptyMap(),
    val buzz: List<BuzzCheck> = emptyList(),
    val edgar: Map<String, EdgarSummary> = emptyMap(),
    /** Nearest-expiry calls per ticker, for unusual options volume. */
    val chains: Map<String, List<OptionQuote>> = emptyMap(),
)

/** The plain-code checks. Pure and free: no tokens are spent here. */
object AlertEngine {

    fun evaluate(c: AlertContext): List<AlertEvent> {
        val r = c.rules
        val ny = c.now.withZoneSameInstant(MarketClock.NY)
        val today = ny.toLocalDate()
        val out = mutableListOf<AlertEvent>()
        val heldTickers = c.ledger.positions.map { it.instrument.ticker }.toSet()
        val watchOnly = c.watchlist.map { it.uppercase() }.filter { it !in heldTickers }.toSet()

        // ---- Options held ----
        for (p in c.ledger.positions.filter { it.instrument.isOption }) {
            val inst = p.instrument
            val k = inst.strike ?: continue
            val exp = inst.expiry ?: continue
            if (exp.isBefore(today)) continue
            val q = c.quotes[inst.ticker] ?: continue
            val call = inst.type == AssetType.CALL
            val itm = if (call) q.price >= k else q.price <= k
            // How far the stock is from the strike, on the safe side (positive = out of the money).
            val away = (if (call) k - q.price else q.price - k) / q.price * 100
            val dte = ChronoUnit.DAYS.between(today, exp)
            val short = p.quantity < 0
            val n = qty(abs(p.quantity))

            if (short && c.check == CheckKind.CLOSE && exp == today && (itm || away <= r.nearPercent)) {
                out += AlertEvent(
                    "expiring:${inst.label}:$today", AlertKind.EXPIRING, AlertLevel.CRITICAL,
                    "${inst.label} expires today${if (itm) ", in the money" else ", near the strike"}",
                    "Stock ${px(q.price)} vs the ${px(k)} strike. Decide before the close: buy back, roll, or accept " +
                        (if (call) "having the shares called away." else "being assigned ${qty(abs(p.quantity) * 100)} shares."),
                    inst.ticker,
                )
            } else if (!short && c.check == CheckKind.CLOSE && exp == today) {
                out += AlertEvent(
                    "expiring:${inst.label}:$today", AlertKind.EXPIRING, AlertLevel.NOTICE,
                    "${inst.label} (long) expires today",
                    if (itm) "It's in the money (stock ${px(q.price)}). Sell it before the close or it may be exercised."
                    else "It's out of the money (stock ${px(q.price)}) and will likely expire worthless.",
                    inst.ticker,
                )
            }

            if (short && r.shortCalls) {
                if (itm) {
                    out += AlertEvent(
                        "itm:${inst.label}:$today", AlertKind.SHORT_ITM, AlertLevel.CRITICAL,
                        "${inst.label} is in the money",
                        "Stock ${px(q.price)} vs the ${px(k)} strike, $dte day${if (dte == 1L) "" else "s"} left on $n contract${if (n == "1") "" else "s"}. " +
                            (if (call) "Assignment would sell your shares at ${px(k)}: buy back or roll if you want to keep them."
                            else "Assignment would buy shares at ${px(k)}: make sure the cash is there, or buy back."),
                        inst.ticker,
                    )
                } else if (away <= r.nearPercent) {
                    out += AlertEvent(
                        "near:${inst.label}:$today", AlertKind.SHORT_NEAR, AlertLevel.NOTICE,
                        "${inst.label} is near the strike",
                        String.format(Locale.US, "Stock %s is %.1f%% from the %s strike, %d day%s left.", px(q.price), away, px(k), dte, if (dte == 1L) "" else "s"),
                        inst.ticker,
                    )
                }
            }

            // Early assignment: a call holder exercises the day before ex-dividend when the dividend beats the call's time value.
            val div = c.dividends[inst.ticker]
            if (short && call && r.exDividend && div?.exDate != null && (itm || away <= r.nearPercent)) {
                val nextDay = nextTradingDay(today)
                if (div.exDate.isAfter(today) && !div.exDate.isAfter(nextDay) && !exp.isBefore(div.exDate)) {
                    val mark = c.optionQuotes[inst]?.mid
                    val extrinsic = mark?.let { it - max(0.0, q.price - k) }
                    val amount = div.amount
                    val atRisk = itm && (extrinsic == null || amount == null || extrinsic < amount)
                    if (itm) {
                        out += AlertEvent(
                            "exdiv:${inst.label}:${div.exDate}", AlertKind.EX_DIVIDEND, if (atRisk) AlertLevel.CRITICAL else AlertLevel.NOTICE,
                            "Early assignment risk on ${inst.label}",
                            "${inst.ticker} goes ex-dividend ${div.exDate}" + (amount?.let { " (about ${px(it)} a share)" } ?: "") + ". " +
                                (extrinsic?.let { "The call's time value is ${px(max(0.0, it))}" + (if (amount != null && it < amount) ", below the dividend, " else ", ") } ?: "With the call in the money, ") +
                                "the holder may exercise today and take the shares (and the dividend). Buy back or roll today to keep them.",
                            inst.ticker,
                        )
                    }
                }
            }
        }

        // ---- Price moves and 52-week breaks ----
        val tracked = heldTickers + watchOnly
        val sessionFraction = sessionFraction(ny)
        for (t in tracked) {
            val q = c.quotes[t] ?: continue
            val held = t in heldTickers
            val shares = c.ledger.sharesHeld(t)
            val cp = q.changePercent
            if (r.moves && cp != null && abs(cp) >= r.movePercent) {
                val bucket = floor(abs(cp) / r.movePercent).toInt()
                val level = when {
                    !held -> AlertLevel.OPPORTUNITY
                    cp < 0 -> AlertLevel.CRITICAL
                    else -> AlertLevel.NOTICE
                }
                val gap = if (c.check == CheckKind.OPEN && q.open != null && q.previousClose != null && q.previousClose > 0)
                    (q.open / q.previousClose - 1) * 100 else null
                val body = buildList {
                    add("Now ${px(q.price)}.")
                    gap?.takeIf { abs(it) >= 1 }?.let { add(String.format(Locale.US, "Opened %+.1f%% from yesterday's close.", it)) }
                    if (shares > 0) q.change?.let { add("Your ${qty(shares)} shares: ${signedMoney(it * shares)} today.") }
                    val calls = c.ledger.shortCallContracts(t)
                    if (calls > 0 && cp > 0) add("You have ${qty(calls)} short call${if (calls > 1) "s" else ""} on it.")
                    if (!held) add("On your watchlist.")
                    q.volumeRatio?.takeIf { it >= 1.5 }?.let { add(String.format(Locale.US, "Volume %.1fx normal so far.", it)) }
                }.joinToString(" ")
                out += AlertEvent(
                    "move:$t:$today:${if (cp < 0) "-" else "+"}$bucket", AlertKind.MOVE, level,
                    String.format(Locale.US, "%s %s %.1f%% today", t, if (cp < 0) "down" else "up", abs(cp)), body, t,
                )
            }
            if (r.moves && q.high52 != null && q.high52 > 0 && q.price >= q.high52 * 0.999) {
                out += AlertEvent(
                    "range:$t:$today:hi", AlertKind.RANGE_BREAK, if (held) AlertLevel.NOTICE else AlertLevel.OPPORTUNITY,
                    "$t at a 52-week high", "Now ${px(q.price)}, the highest in a year." +
                        (if (c.ledger.shortCallContracts(t) > 0) " Watch your short calls." else ""), t,
                )
            }
            if (r.moves && q.low52 != null && q.low52 > 0 && q.price <= q.low52 * 1.001) {
                out += AlertEvent(
                    "range:$t:$today:lo", AlertKind.RANGE_BREAK, if (held) AlertLevel.NOTICE else AlertLevel.OPPORTUNITY,
                    "$t at a 52-week low", "Now ${px(q.price)}, the lowest in a year.", t,
                )
            }
            if (r.earnings && q.nextEarnings == today) {
                val opts = c.ledger.positions.filter { it.instrument.ticker == t && it.instrument.isOption }
                out += AlertEvent(
                    "earnings:$t:$today", AlertKind.EARNINGS, if (held) AlertLevel.NOTICE else AlertLevel.OPPORTUNITY,
                    "$t reports earnings today",
                    "Expect a big move after the report." + if (opts.isNotEmpty()) " You have options on it: ${opts.joinToString { it.instrument.label }}." else "",
                    t,
                )
            }
            // Volume on pace for a multiple of normal. Needs an hour of trading, or the opening rush looks huge.
            val vol = q.volume
            val avg = q.avgVolume
            if (r.unusual && vol != null && avg != null && avg > 0 && sessionFraction != null && sessionFraction >= 1.0 / 6.5) {
                val pace = vol / sessionFraction / avg
                if (pace >= r.volumeMultiple) {
                    out += AlertEvent(
                        "vol:$t:$today", AlertKind.VOLUME, if (held) AlertLevel.NOTICE else AlertLevel.OPPORTUNITY,
                        String.format(Locale.US, "%s volume on pace for %.1fx normal", t, pace),
                        String.format(Locale.US, "%,d shares so far against a %,d daily average. Price %s%s. Something is up: check the news.",
                            vol, avg, px(q.price), cp?.let { String.format(Locale.US, " (%+.1f%%)", it) } ?: ""),
                        t,
                    )
                }
            }
        }

        // ---- Options volume far above open interest ----
        if (r.unusual) for ((t, chain) in c.chains) {
            val hot = chain.filter { it.volume >= max(500L, 2 * it.openInterest) }.maxByOrNull { it.volume } ?: continue
            out += AlertEvent(
                "flow:$t:$today", AlertKind.OPTIONS_FLOW, if (t in heldTickers) AlertLevel.NOTICE else AlertLevel.OPPORTUNITY,
                "Unusual options volume in $t",
                String.format(Locale.US, "The %s %s call traded %,d contracts against %,d open: someone is making a fresh bet.",
                    px(hot.strike), hot.expiry, hot.volume, hot.openInterest),
                t,
            )
        }

        // ---- Reddit long shots ----
        if (r.longShots) for (b in c.buzz.filter { it.longShot }.take(3)) {
            out += AlertEvent(
                "longshot:${b.ticker}:$today", AlertKind.LONG_SHOT, AlertLevel.OPPORTUNITY,
                "Long shot: ${b.ticker}", (b.longShotWhy ?: "Reddit is piling in on a big move.") + " A gamble: size it as one.", b.ticker,
            )
        }

        // ---- SEC: insider buys and offerings ----
        if (r.insider) for ((t, s) in c.edgar) {
            val held = t in heldTickers
            s.buys.maxByOrNull { it.date }?.let { last ->
                val total = s.buys.sumOf { it.value ?: 0.0 }
                out += AlertEvent(
                    "insider:$t:${last.date}", AlertKind.INSIDER, if (held) AlertLevel.NOTICE else AlertLevel.OPPORTUNITY,
                    "Insider buying in $t",
                    "${last.owner}${if (last.role.isNotBlank()) " (${last.role})" else ""} bought ${qty(last.shares)} shares" +
                        (last.price?.let { " at ${px(it)}" } ?: "") + " on ${last.date}" +
                        (if (s.buys.size > 1) String.format(Locale.US, ". %d buys in total, about \$%,.0f.", s.buys.size, total) else ".") +
                        " Insiders buy with their own money for one reason.",
                    t,
                )
            }
            s.offerings.firstOrNull()?.let { f ->
                out += AlertEvent(
                    "offering:$t:${f.date}", AlertKind.OFFERING, AlertLevel.NOTICE,
                    "$t filed an offering (${f.form})",
                    "Filed ${f.date}. The company may sell new shares, which often pushes the price down." + if (held) " You hold it." else "",
                    t,
                )
            }
        }

        return out.distinctBy { it.key }.sortedBy { it.level.ordinal }
    }

    /** How much of today's regular session has passed (0..1), or null outside it. */
    fun sessionFraction(ny: ZonedDateTime): Double? {
        val d = ny.toLocalDate()
        if (!MarketClock.isTradingDay(d)) return null
        val open = LocalTime.of(9, 30)
        val close = MarketClock.closeTime(d)
        val t = ny.toLocalTime()
        if (t < open || t >= close) return null
        return Duration.between(open, t).toMinutes().toDouble() / Duration.between(open, close).toMinutes()
    }

    fun nextTradingDay(d: LocalDate): LocalDate {
        var n = d.plusDays(1)
        while (!MarketClock.isTradingDay(n)) n = n.plusDays(1)
        return n
    }

    private fun px(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun qty(v: Double) = if (v == floor(v)) v.toLong().toString() else v.toString()
    private fun signedMoney(v: Double) = (if (v >= 0) "+" else "-") + String.format(Locale.US, "\$%,.0f", abs(v))
}

data class NextCheck(val at: ZonedDateTime, val kind: CheckKind)

/** When the background checks run: 9:31 ET, then every interval, and 30 minutes before the close. Trading days only. */
object AlertSchedule {
    private val FIRST = LocalTime.of(9, 31)

    fun day(d: LocalDate, r: AlertRules): List<NextCheck> {
        if (!r.enabled || !MarketClock.isTradingDay(d)) return emptyList()
        val close = MarketClock.closeTime(d)
        val closeCheck = close.minusMinutes(30)
        val out = mutableListOf<NextCheck>()
        fun at(t: LocalTime) = d.atTime(t).atZone(MarketClock.NY)
        if (r.openCheck) out += NextCheck(at(FIRST), CheckKind.OPEN)
        else if (r.intraday) out += NextCheck(at(FIRST), CheckKind.INTRADAY)
        if (r.intraday) {
            val step = r.intervalMin.coerceAtLeast(5).toLong()
            var t = FIRST.plusMinutes(step)
            while (t < close) {
                // The close check covers that slot.
                if (!(r.closeCheck && abs(Duration.between(t, closeCheck).toMinutes()) < 5)) out += NextCheck(at(t), CheckKind.INTRADAY)
                t = t.plusMinutes(step)
            }
        }
        if (r.closeCheck) out += NextCheck(at(closeCheck), CheckKind.CLOSE)
        return out.sortedBy { it.at }
    }

    /** The first check strictly after [now], or null when alerts are off. */
    fun next(now: ZonedDateTime, r: AlertRules): NextCheck? {
        if (!r.enabled) return null
        val start = now.withZoneSameInstant(MarketClock.NY).toLocalDate()
        for (i in 0..14) {
            day(start.plusDays(i.toLong()), r).firstOrNull { it.at.isAfter(now) }?.let { return it }
        }
        return null
    }
}

// ---- Claude: the 9:31 brief ---------------------------------------------------------------------

@Serializable
data class OpenBriefReply(
    /** The single most important thing, one line. */
    val title: String,
    val bullets: List<String> = emptyList(),
    /** CALM, WATCH or ACT (something held needs a decision today). */
    val tone: String = "WATCH",
)

data class OpenBriefContext(
    val today: LocalDate,
    val ledger: LedgerState,
    val quotes: Map<String, Quote>,
    val watchlist: List<String>,
    val alerts: List<AlertEvent>,
    val movers: List<Quote>,
    val headlines: List<Headline>,
    val buzz: List<String>,
    val filings: List<String>,
    val risk: RiskLevel,
    val margin: Boolean,
)

/** One Claude call a minute after the open: what matters today for this account. A few cents with Sonnet. */
class OpenBriefer(private val llm: LlmClient) {
    suspend fun brief(model: ClaudeModel, ctx: OpenBriefContext): Pair<OpenBriefReply, List<SpendItem>> {
        val r = llm.send(
            LlmRequest(model, SYSTEM, request(ctx), 8000, if (model == ClaudeModel.OPUS) "high" else "medium", SCHEMA)
        )
        if (r.stopReason == "max_tokens") throw LlmException("The open brief was cut off.")
        return LlmJson.decode(OpenBriefReply.serializer(), r.text) to listOf(SpendItem("open check", model, r.servedModel, r.usage))
    }

    companion object {
        val SYSTEM = """
            One minute after the US market opens, you give the owner of a small Schwab account a 30-second read
            on his phone. He trades stocks and covered calls by hand; this app never places orders.

            Rules:
            - Use ONLY the data given. Reddit lines are claims, not facts.
            - title: the single most important thing today, under 70 characters. If nothing he holds needs
              action, say what the market is doing.
            - bullets: 2 to 5 short lines, most important first. Positions at risk (short calls in or near the
              money, holdings falling hard, earnings, ex-dividend) come before opportunities. Name tickers and
              numbers. Say what to consider doing, not just what happened.
            - tone: ACT only when something he holds needs a decision today, WATCH when something is worth
              keeping an eye on, CALM otherwise.
            - Plain words, no disclaimers.
        """.trimIndent()

        fun request(c: OpenBriefContext): String = buildString {
            appendLine("Date: ${c.today} (${c.today.dayOfWeek})")
            appendLine("Account: ${if (c.margin) "margin" else "cash"}, cash ${String.format(Locale.US, "%.2f", c.ledger.cash)}. Risk setting: ${c.risk.label}")
            appendLine()
            appendLine("## Positions")
            if (c.ledger.positions.isEmpty()) appendLine("(none)")
            for (p in c.ledger.positions) {
                val q = c.quotes[p.instrument.ticker]
                append("- ${p.instrument.label}: ${p.quantity} @ ${String.format(Locale.US, "%.2f", p.avgCost)}")
                q?.let { append(String.format(Locale.US, "; stock %.2f", it.price)); it.changePercent?.let { cp -> append(String.format(Locale.US, " (%+.1f%%)", cp)) } }
                q?.nextEarnings?.let { append("; earnings $it") }
                appendLine()
            }
            appendLine()
            appendLine("## Watchlist")
            c.watchlist.forEach { t ->
                val q = c.quotes[t]
                appendLine("- $t" + (q?.let { String.format(Locale.US, " %.2f", it.price) + (it.changePercent?.let { cp -> String.format(Locale.US, " (%+.1f%%)", cp) } ?: "") } ?: ""))
            }
            appendLine()
            appendLine("## Alerts the plain checks raised")
            if (c.alerts.isEmpty()) appendLine("(none)")
            c.alerts.forEach { appendLine("- [${it.level.label}] ${it.title}: ${it.body}") }
            if (c.movers.isNotEmpty()) {
                appendLine()
                appendLine("## Biggest movers at the open")
                c.movers.forEach { q -> appendLine("- ${q.symbol}" + String.format(Locale.US, " %.2f", q.price) + (q.changePercent?.let { String.format(Locale.US, " (%+.1f%%)", it) } ?: "")) }
            }
            if (c.buzz.isNotEmpty()) {
                appendLine()
                appendLine("## Reddit (unverified)")
                c.buzz.forEach { appendLine("- $it") }
            }
            if (c.filings.isNotEmpty()) {
                appendLine()
                appendLine("## SEC filings")
                c.filings.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("## Headlines")
            c.headlines.take(25).forEach { appendLine("- [${it.symbol}] ${it.title}${if (it.source.isNotBlank()) " (${it.source})" else ""}") }
        }

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("title", "bullets", "tone"),
            "properties" to mapOf(
                "title" to mapOf("type" to "string"),
                "bullets" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                "tone" to mapOf("type" to "string", "enum" to listOf("CALM", "WATCH", "ACT")),
            ),
        )
    }
}

// ---- Claude: why is it moving? ------------------------------------------------------------------

@Serializable
data class Lesson(val title: String, val text: String)

@Serializable
data class MoveExplanation(
    /** Two sentences at most. */
    val why: String,
    /** NEWS, EARNINGS, FILING, SECTOR, MARKET, CROWD or UNKNOWN. */
    val driver: String = "UNKNOWN",
    /** low, medium or high. */
    val confidence: String = "medium",
    val lesson: Lesson,
    /** What would confirm or undo the move. */
    val watchFor: String = "",
)

data class MoveContext(
    val ticker: String,
    val quote: Quote?,
    val headlines: List<Headline>,
    /** His positions on it, one line each. */
    val position: List<String>,
    val filings: List<String>,
    val buzz: String?,
    /** Lesson titles already in his notebook, so a new one teaches something else. */
    val pastLessons: List<String>,
)

/** Explains a move and turns it into a short lesson tied to what he holds. A cent or two. */
class MoveExplainer(private val llm: LlmClient) {
    suspend fun explain(model: ClaudeModel, ctx: MoveContext): Pair<MoveExplanation, List<SpendItem>> {
        val r = llm.send(LlmRequest(model, SYSTEM, request(ctx), 6000, if (model == ClaudeModel.OPUS) "high" else "low", SCHEMA))
        if (r.stopReason == "max_tokens") throw LlmException("The explanation was cut off.")
        return LlmJson.decode(MoveExplanation.serializer(), r.text) to listOf(SpendItem("why moving", model, r.servedModel, r.usage))
    }

    companion object {
        val SYSTEM = """
            You explain stock moves to a self-taught trader who wants to get better over time. He trades stocks
            and covered calls in a small Schwab account.

            Rules:
            - why: at most two sentences on why the stock is moving today, from the headlines, filings and
              numbers given. If nothing given explains it, say so plainly and set driver UNKNOWN and confidence low.
            - Reddit lines are claims, not facts.
            - lesson: one idea he can reuse, tied to this move and to his position when he has one (for
              example how a gap affects a short call, what an offering does to price, why volume matters).
              title under 50 characters, text 2 to 4 sentences. Don't repeat a lesson he already has.
            - watchFor: one line on what would confirm or undo the move.
            - Plain words, no disclaimers.
        """.trimIndent()

        fun request(c: MoveContext): String = buildString {
            appendLine("Ticker: ${c.ticker}")
            c.quote?.let { q ->
                append(String.format(Locale.US, "Price %.2f", q.price))
                q.changePercent?.let { append(String.format(Locale.US, ", %+.2f%% today", it)) }
                q.previousClose?.let { append(String.format(Locale.US, ", previous close %.2f", it)) }
                q.open?.let { append(String.format(Locale.US, ", opened %.2f", it)) }
                q.volumeRatio?.let { append(String.format(Locale.US, ", volume %.1fx average so far", it)) }
                if (q.high52 != null && q.low52 != null) append(String.format(Locale.US, ", 52-week range %.2f-%.2f", q.low52, q.high52))
                q.nextEarnings?.let { append(", next earnings $it") }
                appendLine()
            }
            appendLine()
            appendLine("His position: " + if (c.position.isEmpty()) "none (watching)" else "")
            c.position.forEach { appendLine("- $it") }
            if (c.filings.isNotEmpty()) { appendLine(); appendLine("SEC filings:"); c.filings.forEach { appendLine("- $it") } }
            c.buzz?.let { appendLine(); appendLine("Reddit (unverified): $it") }
            appendLine()
            appendLine("Headlines:")
            if (c.headlines.isEmpty()) appendLine("(none found)")
            c.headlines.take(12).forEach { appendLine("- ${it.title}${if (it.source.isNotBlank()) " (${it.source})" else ""}") }
            if (c.pastLessons.isNotEmpty()) {
                appendLine()
                appendLine("Lessons already in his notebook: ${c.pastLessons.take(30).joinToString("; ")}")
            }
        }

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("why", "driver", "confidence", "lesson", "watchFor"),
            "properties" to mapOf(
                "why" to mapOf("type" to "string"),
                "driver" to mapOf("type" to "string", "enum" to listOf("NEWS", "EARNINGS", "FILING", "SECTOR", "MARKET", "CROWD", "UNKNOWN")),
                "confidence" to mapOf("type" to "string", "enum" to listOf("low", "medium", "high")),
                "lesson" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("title", "text"),
                    "properties" to mapOf("title" to mapOf("type" to "string"), "text" to mapOf("type" to "string")),
                ),
                "watchFor" to mapOf("type" to "string"),
            ),
        )
    }
}

/** Pulls the JSON object out of a reply and decodes it leniently. */
object LlmJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw LlmException("Claude's reply had no JSON in it.")
        return try {
            json.decodeFromString(serializer, text.substring(start, end + 1))
        } catch (e: Exception) {
            throw LlmException("Couldn't read Claude's reply: ${e.message}", e)
        }
    }

    fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String = json.encodeToString(serializer, value)
}
