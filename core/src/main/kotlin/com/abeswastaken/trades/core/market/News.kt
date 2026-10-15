package com.abeswastaken.trades.core.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Symbol used on market-wide headlines (not about one ticker). */
const val MARKET = "MARKET"

/** Every news source the app knows. Keyed ones are skipped until a key is saved. */
enum class NewsSourceId(
    val label: String,
    val needsKey: Boolean,
    /** Where to get a key (or the site itself for keyless feeds). */
    val url: String,
    val note: String,
    val defaultOn: Boolean,
) {
    YAHOO("Yahoo Finance", false, "https://finance.yahoo.com", "Per-ticker and market headlines. No key.", true),
    GOOGLE("Google News", false, "https://news.google.com", "Searches every major outlet (Reuters, Bloomberg, WSJ, Barron's…). No key.", true),
    CNBC("CNBC", false, "https://www.cnbc.com/rss-feeds/", "Top market and finance stories. Market-wide only. No key.", true),
    MARKETWATCH("MarketWatch", false, "https://www.marketwatch.com/site/rss", "Top stories and market pulse. Market-wide only. No key.", true),
    SEEKING_ALPHA("Seeking Alpha", false, "https://seekingalpha.com/page/feeds", "Per-ticker analysis and market currents. No key.", false),
    FINNHUB("Finnhub", true, "https://finnhub.io/register", "Company news and general market news. Free key, 60 calls a minute.", true),
    ALPHA_VANTAGE("Alpha Vantage", true, "https://www.alphavantage.co/support/#api-key", "News with a sentiment score per story. Free key, 25 calls a day.", true),
    MARKETAUX("Marketaux", true, "https://www.marketaux.com/register", "Global financial news tagged by ticker, with sentiment. Free key, 100 calls a day.", true);

    companion object {
        val defaults: Set<NewsSourceId> get() = entries.filter { it.defaultOn }.toSet()
        fun parse(names: Collection<String>) = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()
    }
}

/** One news source. Market-only feeds return nothing for a ticker, and vice versa. */
interface NewsProvider {
    val id: NewsSourceId
    suspend fun ticker(symbol: String, limit: Int): List<Headline>
    suspend fun market(limit: Int): List<Headline>
}

/** Fetches from every enabled source at once, then merges, de-duplicates and sorts newest first. */
class NewsHub(private val providers: List<NewsProvider>, private val log: (String) -> Unit = {}) {
    val sources get() = providers.map { it.id }

    suspend fun ticker(symbol: String, limit: Int = 10): List<Headline> =
        gather(limit) { it.ticker(symbol, PER_SOURCE) }

    suspend fun market(limit: Int = 30): List<Headline> =
        gather(limit) { it.market(PER_SOURCE_MARKET) }

    private suspend fun gather(limit: Int, fetch: suspend (NewsProvider) -> List<Headline>): List<Headline> = coroutineScope {
        val lists = providers.map { p ->
            async {
                runCatching { fetch(p) }.getOrElse { e ->
                    log("news via ${p.id.label} failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()
        merge(lists, limit)
    }

    companion object {
        private const val PER_SOURCE = 6
        private const val PER_SOURCE_MARKET = 12

        /** Interleaves sources so one chatty feed can't crowd out the rest, drops repeats, newest first. */
        fun merge(lists: List<List<Headline>>, limit: Int): List<Headline> {
            val seen = HashSet<String>()
            val out = mutableListOf<Headline>()
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) {
                val h = l.getOrNull(i) ?: continue
                if (seen.add(key(h.title))) out += h
            }
            return out.sortedByDescending { it.publishedEpochSec ?: 0 }.take(limit)
        }

        /** Same story from two outlets usually shares its first words; Google appends " - Publisher". */
        internal fun key(title: String) = title.substringBeforeLast(" - ").lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' }.split(' ').filter { it.isNotBlank() }.take(8).joinToString(" ")
    }
}

/** Shared HTTP plumbing for the news providers. */
abstract class HttpNews(protected val client: OkHttpClient) : NewsProvider {
    protected suspend fun fetch(url: HttpUrl, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw MarketDataException("${id.label} returned ${it.code}")
            it.body?.string().orEmpty()
        }
    }

    protected suspend fun rss(url: String, symbol: String, limit: Int, extra: Map<String, String> = emptyMap()): List<Headline> {
        val u = url.toHttpUrl().newBuilder().apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Rss.parse(symbol, fetch(u), id.label).take(limit)
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

class YahooNews(client: OkHttpClient, private val base: String = "https://feeds.finance.yahoo.com") : HttpNews(client) {
    override val id = NewsSourceId.YAHOO
    override suspend fun ticker(symbol: String, limit: Int) =
        rss("$base/rss/2.0/headline", symbol, limit, mapOf("s" to symbol, "region" to "US", "lang" to "en-US"))
    override suspend fun market(limit: Int) =
        rss("$base/rss/2.0/headline", MARKET, limit, mapOf("s" to "^GSPC,^DJI,^IXIC", "region" to "US", "lang" to "en-US"))
}

class GoogleNews(client: OkHttpClient, private val base: String = "https://news.google.com") : HttpNews(client) {
    override val id = NewsSourceId.GOOGLE
    private suspend fun search(q: String, symbol: String, limit: Int) =
        rss("$base/rss/search", symbol, limit, mapOf("q" to q, "hl" to "en-US", "gl" to "US", "ceid" to "US:en"))
    override suspend fun ticker(symbol: String, limit: Int) = search("$symbol stock when:7d", symbol, limit)
    override suspend fun market(limit: Int) = search("stock market when:1d", MARKET, limit)
}

class CnbcNews(client: OkHttpClient, private val base: String = "https://search.cnbc.com") : HttpNews(client) {
    override val id = NewsSourceId.CNBC
    override suspend fun ticker(symbol: String, limit: Int) = emptyList<Headline>()
    override suspend fun market(limit: Int): List<Headline> = NewsHub.merge(
        // 100003114 = top news, 10000664 = finance
        listOf("100003114", "10000664").map { feed ->
            rss("$base/rs/search/combinedcms/view.xml", MARKET, limit, mapOf("partnerId" to "wrss01", "id" to feed))
        },
        limit,
    )
}

class MarketWatchNews(client: OkHttpClient, private val base: String = "https://feeds.content.dowjones.io") : HttpNews(client) {
    override val id = NewsSourceId.MARKETWATCH
    override suspend fun ticker(symbol: String, limit: Int) = emptyList<Headline>()
    override suspend fun market(limit: Int): List<Headline> = NewsHub.merge(
        listOf("mw_topstories", "mw_marketpulse").map { rss("$base/public/rss/$it", MARKET, limit) },
        limit,
    )
}

class SeekingAlphaNews(client: OkHttpClient, private val base: String = "https://seekingalpha.com") : HttpNews(client) {
    override val id = NewsSourceId.SEEKING_ALPHA
    override suspend fun ticker(symbol: String, limit: Int) = rss("$base/api/sa/combined/$symbol.xml", symbol, limit)
    override suspend fun market(limit: Int) = rss("$base/market_currents.xml", MARKET, limit)
}

class FinnhubNews(
    client: OkHttpClient,
    private val key: String,
    private val base: String = "https://finnhub.io/api/v1",
    private val today: () -> LocalDate = { LocalDate.now() },
) : HttpNews(client) {
    override val id = NewsSourceId.FINNHUB
    private suspend fun get(path: String, params: Map<String, String>) = fetch(
        "$base$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build(),
        mapOf("X-Finnhub-Token" to key),
    )

    override suspend fun ticker(symbol: String, limit: Int): List<Headline> {
        val to = today()
        return parse(symbol, get("/company-news", mapOf("symbol" to symbol, "from" to to.minusDays(7).toString(), "to" to to.toString())), limit)
    }

    override suspend fun market(limit: Int) = parse(MARKET, get("/news", mapOf("category" to "general")), limit)

    companion object {
        internal fun parse(symbol: String, body: String, limit: Int): List<Headline> {
            val el = YahooMarketData.json.parseToJsonElement(body)
            if (el !is JsonArray) throw MarketDataException("Finnhub: " + ((el as? JsonObject)?.str("error") ?: "unexpected reply"))
            return el.mapNotNull { e ->
                val o = e.jsonObject
                Headline(symbol, o.str("headline")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null, o.long("datetime"), o.str("url"), sourceName("Finnhub", o.str("source")))
            }.take(limit)
        }
    }
}

class AlphaVantageNews(
    client: OkHttpClient,
    private val key: String,
    private val base: String = "https://www.alphavantage.co",
) : HttpNews(client) {
    override val id = NewsSourceId.ALPHA_VANTAGE
    private suspend fun query(symbol: String, params: Map<String, String>, limit: Int): List<Headline> {
        val url = "$base/query".toHttpUrl().newBuilder()
            .addQueryParameter("function", "NEWS_SENTIMENT").addQueryParameter("sort", "LATEST")
            .addQueryParameter("limit", "50").addQueryParameter("apikey", key)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return parse(symbol, fetch(url), limit)
    }

    override suspend fun ticker(symbol: String, limit: Int) = query(symbol, mapOf("tickers" to symbol), limit)
    override suspend fun market(limit: Int) = query(MARKET, mapOf("topics" to "financial_markets"), limit)

    companion object {
        private val AV_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        internal fun parse(symbol: String, body: String, limit: Int): List<Headline> {
            val root = YahooMarketData.json.parseToJsonElement(body).jsonObject
            val feed = root.arr("feed") ?: throw MarketDataException(
                "Alpha Vantage: " + (root.str("Information") ?: root.str("Note") ?: root.str("Error Message") ?: "no feed")
            )
            return feed.mapNotNull { e ->
                val o = e.jsonObject
                val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val time = o.str("time_published")?.let {
                    runCatching { LocalDateTime.parse(it, AV_TIME).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
                }
                // Prefer the score for this ticker; fall back to the story's overall score.
                val tickerScore = o.arr("ticker_sentiment")?.map { it.jsonObject }
                    ?.firstOrNull { it.str("ticker") == symbol }?.let { it.num("ticker_sentiment_score") ?: it.str("ticker_sentiment_score")?.toDoubleOrNull() }
                Headline(symbol, title, time, o.str("url"), sourceName("Alpha Vantage", o.str("source")), tickerScore ?: o.num("overall_sentiment_score"))
            }.take(limit)
        }
    }
}

class MarketauxNews(
    client: OkHttpClient,
    private val key: String,
    private val base: String = "https://api.marketaux.com",
) : HttpNews(client) {
    override val id = NewsSourceId.MARKETAUX
    private suspend fun query(symbol: String, params: Map<String, String>, limit: Int): List<Headline> {
        val url = "$base/v1/news/all".toHttpUrl().newBuilder()
            .addQueryParameter("language", "en").addQueryParameter("api_token", key)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return parse(symbol, fetch(url), limit)
    }

    override suspend fun ticker(symbol: String, limit: Int) =
        query(symbol, mapOf("symbols" to symbol, "filter_entities" to "true"), limit)
    override suspend fun market(limit: Int) = query(MARKET, mapOf("countries" to "us", "filter_entities" to "true"), limit)

    companion object {
        internal fun parse(symbol: String, body: String, limit: Int): List<Headline> {
            val root = YahooMarketData.json.parseToJsonElement(body).jsonObject
            val data = root.arr("data") ?: throw MarketDataException(
                "Marketaux: " + (root.obj("error")?.str("message") ?: "no data")
            )
            return data.mapNotNull { e ->
                val o = e.jsonObject
                val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val time = o.str("published_at")?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }
                val score = o.arr("entities")?.map { it.jsonObject }?.let { ents ->
                    (ents.firstOrNull { it.str("symbol") == symbol } ?: ents.firstOrNull())?.num("sentiment_score")
                }
                Headline(symbol, title, time, o.str("url"), sourceName("Marketaux", o.str("source")), score)
            }.take(limit)
        }
    }
}

private fun sourceName(via: String, outlet: String?) = if (outlet.isNullOrBlank() || outlet == via) via else "$outlet via $via"

/** Builds the providers for the sources that are switched on and have a key where one is needed. */
object NewsSources {
    fun build(
        client: OkHttpClient,
        enabled: Set<NewsSourceId>,
        keys: Map<NewsSourceId, String>,
    ): List<NewsProvider> = enabled.sortedBy { it.ordinal }.mapNotNull { id ->
        val key = keys[id]?.trim().orEmpty()
        if (id.needsKey && key.isEmpty()) return@mapNotNull null
        when (id) {
            NewsSourceId.YAHOO -> YahooNews(client)
            NewsSourceId.GOOGLE -> GoogleNews(client)
            NewsSourceId.CNBC -> CnbcNews(client)
            NewsSourceId.MARKETWATCH -> MarketWatchNews(client)
            NewsSourceId.SEEKING_ALPHA -> SeekingAlphaNews(client)
            NewsSourceId.FINNHUB -> FinnhubNews(client, key)
            NewsSourceId.ALPHA_VANTAGE -> AlphaVantageNews(client, key)
            NewsSourceId.MARKETAUX -> MarketauxNews(client, key)
        }
    }
}
