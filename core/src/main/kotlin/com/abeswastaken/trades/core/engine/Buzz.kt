package com.abeswastaken.trades.core.engine

import com.abeswastaken.trades.core.market.BuzzItem
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.Quote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs

/** How well the real market backs up the chatter. */
enum class BuzzVerdict(val label: String) {
    CONFIRMED("Backed by price/volume and news"),
    PARTLY("Partly backed"),
    HYPE_ONLY("Chatter only: no move, no news"),
    NO_QUOTE("No quote to check against"),
}

/** One Reddit-buzz name after the free, plain-code cross-check against quotes and news. */
data class BuzzCheck(
    val item: BuzzItem,
    val quote: Quote?,
    /** Recent non-Reddit headlines on the ticker. */
    val news: List<Headline>,
    /** The things worth noticing, most important first. */
    val standouts: List<String>,
    val verdict: BuzzVerdict,
    /** A WSB-style lottery ticket: crowd pile-in on a big move or huge volume. */
    val longShot: Boolean,
    val longShotWhy: String?,
) {
    val ticker get() = item.ticker

    /** One line for a prompt. */
    fun promptLine(): String = buildString {
        append("${item.ticker}: #${item.rank} on Reddit, ${item.mentions} mentions")
        item.mentions24hAgo?.let { append(" (was $it a day ago)") }
        quote?.let { q ->
            append(String.format(Locale.US, ", price %.2f", q.price))
            q.changePercent?.let { append(String.format(Locale.US, " %+.1f%% today", it)) }
            q.volumeRatio?.let { append(String.format(Locale.US, ", volume %.1fx normal", it)) }
        }
        append(". Check: ${verdict.label}")
        if (longShot) append(". LONG SHOT")
        news.firstOrNull()?.let { append(". News: ${it.title}") }
    }
}

object BuzzScreen {
    const val SPIKE = 2.0

    /** Cross-checks one name. Pure, so the rules are testable. */
    fun check(item: BuzzItem, quote: Quote?, headlines: List<Headline>, nowEpochSec: Long): BuzzCheck {
        val news = headlines.filter { !it.source.startsWith("Reddit") }
            .filter { h -> h.publishedEpochSec?.let { nowEpochSec - it <= 72 * 3600 } ?: true }
        val out = mutableListOf<String>()
        val ratio = item.mentionRatio
        val spiking = (ratio != null && ratio >= SPIKE && item.mentions >= 25) || (item.rank24hAgo == null && item.rank <= 20)
        val climbing = (item.rankJump ?: 0) >= 15 && item.rank <= 30
        when {
            ratio != null && ratio >= SPIKE -> out += String.format(Locale.US, "Mentions up %.1fx in a day (%d → %d)", ratio, item.mentions24hAgo, item.mentions)
            item.rank24hAgo == null -> out += "New on Reddit's list today (#${item.rank})"
        }
        if (climbing) out += "Climbed from #${item.rank24hAgo} to #${item.rank}"

        val move = quote?.changePercent
        val vol = quote?.volumeRatio
        if (vol != null && vol >= 2) out += String.format(Locale.US, "Volume %.1fx normal", vol)
        if (move != null && abs(move) >= 5) out += String.format(Locale.US, "Price %+.1f%% today", move)
        news.firstOrNull()?.let { out += "News: ${it.title}" }
        quote?.nextEarnings?.let { out += "Earnings $it" }

        val marketBacks = (vol != null && vol >= 1.5) || (move != null && abs(move) >= 3)
        val verdict = when {
            quote == null -> BuzzVerdict.NO_QUOTE
            marketBacks && news.isNotEmpty() -> BuzzVerdict.CONFIRMED
            marketBacks || news.isNotEmpty() -> BuzzVerdict.PARTLY
            else -> BuzzVerdict.HYPE_ONLY
        }
        if (verdict == BuzzVerdict.HYPE_ONLY) out += "No price move, volume or news behind the chatter"

        // Lottery-ticket setup: the crowd is piling in AND the stock is already moving hard.
        val hot = spiking || climbing || item.rank <= 5
        val wild = quote != null && (
            (quote.price <= 20 && ((move != null && abs(move) >= 10) || (vol != null && vol >= 3))) ||
                (move != null && abs(move) >= 25)
            )
        val longShot = hot && wild
        val why = if (!longShot) null else buildList {
            if (ratio != null && ratio >= SPIKE) add(String.format(Locale.US, "mentions %.1fx", ratio))
            else add("#${item.rank} on Reddit")
            vol?.takeIf { it >= 2 }?.let { add(String.format(Locale.US, "volume %.1fx", it)) }
            move?.let { add(String.format(Locale.US, "%+.0f%% today", it)) }
        }.joinToString(", ") + if (news.isEmpty()) ". No news explains it." else "."

        return BuzzCheck(item, quote, news.take(3), out, verdict, longShot, why)
    }

    /** Long shots first, then the fastest-rising chatter. */
    fun rank(checks: List<BuzzCheck>): List<BuzzCheck> = checks.sortedWith(
        compareByDescending<BuzzCheck> { it.longShot }
            .thenByDescending { (it.item.mentionRatio ?: 1.0) * kotlin.math.ln(it.item.mentions + 1.0) }
    )
}

@Serializable
data class BuzzNote(
    val ticker: String,
    /** What the crowd is claiming. */
    val claim: String,
    /** What the price, volume and news actually show. */
    val checksOut: String,
    /** What's missing or doesn't add up. */
    val redFlags: String,
    /** SUPPORTED, MIXED or UNSUPPORTED. */
    val verdict: String,
    val longShot: Boolean = false,
)

@Serializable
data class BuzzReport(val summary: String, val notes: List<BuzzNote> = emptyList())

/** Claude reads the crowd's posts next to the real numbers and says what holds up. Opt-in, a few cents. */
class BuzzVerifier(private val llm: LlmClient) {

    suspend fun verify(model: ClaudeModel, checks: List<BuzzCheck>, posts: Map<String, List<Headline>>, risk: RiskLevel): Pair<BuzzReport, List<SpendItem>> {
        if (checks.isEmpty()) throw LlmException("No Reddit buzz to check right now.")
        val r = llm.send(
            LlmRequest(
                model = model, system = SYSTEM, user = request(checks, posts, risk), maxTokens = 12000,
                effort = if (model == ClaudeModel.OPUS) "high" else "medium", jsonSchema = SCHEMA,
            )
        )
        val spend = listOf(SpendItem("reddit check", model, r.servedModel, r.usage))
        if (r.stopReason == "max_tokens") throw LlmException("The reply was cut off. Try again.")
        val report = parse(r.text)
        val allowed = checks.map { it.ticker }.toSet()
        return report.copy(notes = report.notes.filter { it.ticker.uppercase() in allowed }) to spend
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        val SYSTEM = """
            You fact-check Reddit stock chatter for the owner of one small Schwab cash account. He speed-reads
            r/wallstreetbets and friends through you: tell him what the crowd claims, whether the real numbers
            and the news back it up, and what stands out.

            Rules:
            - Use ONLY the numbers and headlines given. Reddit post titles are claims, not facts.
            - checksOut names the concrete evidence (the price move, the volume, the headline). If nothing
              backs it, say so.
            - redFlags: missing news, a move already done, dilution or offering talk, tiny float pumps,
              claims the headlines contradict.
            - longShot = true only for lottery-ticket setups (crowd piling in on a stock already making a
              huge move). Say plainly in redFlags what makes it a gamble.
            - summary: two or three sentences on what Reddit is excited about today and how much of it is real.
            - Short, plain sentences. No disclaimers.
        """.trimIndent()

        fun request(checks: List<BuzzCheck>, posts: Map<String, List<Headline>>, risk: RiskLevel): String = buildString {
            appendLine("Owner's risk setting: ${risk.label}")
            appendLine()
            for (c in checks) {
                appendLine("## ${c.ticker}${c.item.name?.let { " ($it)" }.orEmpty()}")
                appendLine(c.promptLine())
                c.news.forEach { appendLine("- news: ${it.title}${if (it.source.isNotBlank()) " [${it.source}]" else ""}") }
                posts[c.ticker].orEmpty().take(6).forEach { appendLine("- reddit: ${it.title}") }
                appendLine()
            }
            append("Check each one.")
        }

        fun parse(text: String): BuzzReport {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) throw LlmException("Claude's reply had no report in it.")
            return try {
                json.decodeFromString(BuzzReport.serializer(), text.substring(start, end + 1))
            } catch (e: Exception) {
                throw LlmException("Couldn't read Claude's report: ${e.message}", e)
            }
        }

        fun encode(r: BuzzReport): String = json.encodeToString(BuzzReport.serializer(), r)

        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("summary", "notes"),
            "properties" to mapOf(
                "summary" to mapOf("type" to "string"),
                "notes" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to listOf("ticker", "claim", "checksOut", "redFlags", "verdict", "longShot"),
                        "properties" to mapOf(
                            "ticker" to mapOf("type" to "string"),
                            "claim" to mapOf("type" to "string"),
                            "checksOut" to mapOf("type" to "string"),
                            "redFlags" to mapOf("type" to "string"),
                            "verdict" to mapOf("type" to "string", "enum" to listOf("SUPPORTED", "MIXED", "UNSUPPORTED")),
                            "longShot" to mapOf("type" to "boolean"),
                        ),
                    ),
                ),
            ),
        )
    }
}
