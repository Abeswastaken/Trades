package com.abeswastaken.trades.core.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Yahoo Finance's public JSON endpoints (the same ones yfinance scrapes). No key, but they need a
 * session cookie plus a "crumb" token, which is fetched once and refreshed on a 401.
 */
class YahooMarketData(
    baseClient: OkHttpClient = OkHttpClient(),
    private val queryBase: String = "https://query2.finance.yahoo.com",
    private val cookieUrl: String = "https://fc.yahoo.com",
    private val rssBase: String = "https://feeds.finance.yahoo.com",
) : MarketData, Discovery {
    override val name = "Yahoo"

    private val cookies = mutableListOf<Cookie>()
    private val client = baseClient.newBuilder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(this@YahooMarketData.cookies) {
                    this@YahooMarketData.cookies.removeAll { c -> cookies.any { it.name == c.name } }
                    this@YahooMarketData.cookies += cookies
                }
            }
            override fun loadForRequest(url: HttpUrl) =
                synchronized(cookies) { cookies.filter { it.matches(url) } }
        })
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val crumbLock = Mutex()
    @Volatile private var crumb: String? = null

    private suspend fun crumb(force: Boolean = false): String = crumbLock.withLock {
        crumb?.takeIf { !force }?.let { return it }
        withContext(Dispatchers.IO) {
            // Any response sets the session cookie; fc.yahoo.com answers 404 by design.
            client.newCall(Request.Builder().url(cookieUrl).build()).execute().close()
            client.newCall(Request.Builder().url("$queryBase/v1/test/getcrumb").build()).execute().use { r ->
                val body = r.body?.string().orEmpty().trim()
                if (!r.isSuccessful || body.isEmpty() || body.startsWith("<") || body.startsWith("{")) {
                    throw MarketDataException("Yahoo crumb request failed (${r.code})")
                }
                body
            }
        }.also { crumb = it }
    }

    private suspend fun getJson(path: String, params: Map<String, String>, postJson: String? = null): JsonObject {
        for (attempt in 0..1) {
            val c = crumb(force = attempt > 0)
            val url = "$queryBase$path".toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
                addQueryParameter("crumb", c)
            }.build()
            val (code, body) = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).apply {
                    if (postJson != null) post(postJson.toRequestBody("application/json".toMediaType()))
                }.build()
                client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            }
            if (code == 401 || code == 403) continue
            if (code !in 200..299) throw MarketDataException("Yahoo $path returned $code")
            return json.parseToJsonElement(body).jsonObject
        }
        throw MarketDataException("Yahoo rejected the session for $path")
    }

    override suspend fun quotes(symbols: List<String>): Map<String, Quote> {
        if (symbols.isEmpty()) return emptyMap()
        val root = getJson("/v7/finance/quote", mapOf("symbols" to symbols.joinToString(",")))
        return parseQuotes(root).associateBy { it.symbol }
    }

    override suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): List<OptionQuote> {
        val first = getJson("/v7/finance/options/$symbol", emptyMap())
        val result = first.obj("optionChain")?.arr("result")?.firstOrNull()?.jsonObject
            ?: throw MarketDataException("No options chain for $symbol")
        val wanted = result.arr("expirationDates").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            .filter { epoch ->
                val dte = ChronoUnit.DAYS.between(today, epochToDate(epoch))
                dte in minDte..maxDte
            }
            .take(MAX_EXPIRIES)
        return wanted.flatMap { epoch ->
            val page = getJson("/v7/finance/options/$symbol", mapOf("date" to epoch.toString()))
            parseCalls(symbol, page)
        }
    }

    override suspend fun screen(minPrice: Double, maxPrice: Double, minAvgVolume: Long, size: Int): List<Quote> {
        val body = """
            {"offset":0,"size":$size,"sortField":"dayvolume","sortType":"DESC","quoteType":"EQUITY",
             "query":{"operator":"AND","operands":[
               {"operator":"eq","operands":["region","us"]},
               {"operator":"btwn","operands":["intradayprice",$minPrice,$maxPrice]},
               {"operator":"gt","operands":["avgdailyvol3m",$minAvgVolume]}]},
             "userId":"","userIdType":"guid"}
        """.trimIndent()
        val root = getJson("/v1/finance/screener", mapOf("lang" to "en-US", "region" to "US", "formatted" to "false"), body)
        return parseScreener(root)
    }

    override suspend fun predefined(list: String, size: Int): List<Quote> =
        parseScreener(getJson("/v1/finance/screener/predefined/saved", mapOf("scrIds" to list, "count" to size.toString(), "formatted" to "false")))

    override suspend fun trending(size: Int): List<String> {
        val root = getJson("/v1/finance/trending/US", mapOf("count" to size.toString()))
        return root.obj("finance")?.arr("result")?.firstOrNull()?.jsonObject?.arr("quotes").orEmpty()
            .mapNotNull { it.jsonObject.str("symbol") }
    }

    override suspend fun nearestCalls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): ChainSnapshot {
        val first = getJson("/v7/finance/options/$symbol", emptyMap())
        val result = first.obj("optionChain")?.arr("result")?.firstOrNull()?.jsonObject
            ?: throw MarketDataException("No options chain for $symbol")
        val exps = result.arr("expirationDates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        val dates = exps.map(::epochToDate)
        val pick = exps.firstOrNull { ChronoUnit.DAYS.between(today, epochToDate(it)) in minDte..maxDte }
            ?: return ChainSnapshot(dates, emptyList())
        // The first page already holds the nearest expiry; only fetch again for a later one.
        val firstCalls = parseCalls(symbol, first)
        val calls = if (firstCalls.isNotEmpty() && firstCalls.first().expiry == epochToDate(pick)) firstCalls
            else parseCalls(symbol, getJson("/v7/finance/options/$symbol", mapOf("date" to pick.toString())))
        return ChainSnapshot(dates, calls)
    }

    override suspend fun news(symbol: String, limit: Int): List<Headline> {
        val url = "$rssBase/rss/2.0/headline".toHttpUrl().newBuilder()
            .addQueryParameter("s", symbol).addQueryParameter("region", "US").addQueryParameter("lang", "en-US").build()
        val xml = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use {
                if (!it.isSuccessful) throw MarketDataException("Yahoo RSS returned ${it.code}")
                it.body?.string().orEmpty()
            }
        }
        return Rss.parse(symbol, xml, "Yahoo").take(limit)
    }

    companion object {
        private const val MAX_EXPIRIES = 4
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        internal val json = Json { ignoreUnknownKeys = true }

        fun epochToDate(epoch: Long): LocalDate = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate()

        internal fun parseQuotes(root: JsonObject): List<Quote> =
            root.obj("quoteResponse")?.arr("result").orEmpty().mapNotNull { parseQuote(it.jsonObject) }

        /** Screener replies carry the same quote objects under finance.result[0].quotes. */
        internal fun parseScreener(root: JsonObject): List<Quote> {
            root.obj("finance")?.obj("error")?.let { e ->
                throw MarketDataException("Yahoo screener: ${e.str("description") ?: e.str("code") ?: "error"}")
            }
            return root.obj("finance")?.arr("result")?.firstOrNull()?.jsonObject?.arr("quotes").orEmpty()
                .mapNotNull { parseQuote(it.jsonObject) }
        }

        private fun parseQuote(o: JsonObject): Quote? {
            val sym = o.str("symbol") ?: return null
            val price = o.num("regularMarketPrice") ?: return null
            val earnings = (o.long("earningsTimestamp") ?: o.long("earningsTimestampStart"))?.let(::epochToDate)
            return Quote(
                symbol = sym, price = price,
                change = o.num("regularMarketChange"),
                changePercent = o.num("regularMarketChangePercent"),
                avgVolume = o.long("averageDailyVolume3Month") ?: o.long("averageDailyVolume10Day"),
                name = o.str("shortName") ?: o.str("longName"),
                nextEarnings = earnings,
                source = "Yahoo",
            )
        }

        internal fun parseCalls(symbol: String, root: JsonObject): List<OptionQuote> {
            val result = root.obj("optionChain")?.arr("result")?.firstOrNull()?.jsonObject ?: return emptyList()
            val options = result.arr("options")?.firstOrNull()?.jsonObject ?: return emptyList()
            return options.arr("calls").orEmpty().mapNotNull { el ->
                val o = el.jsonObject
                OptionQuote(
                    underlying = symbol,
                    strike = o.num("strike") ?: return@mapNotNull null,
                    expiry = epochToDate(o.long("expiration") ?: return@mapNotNull null),
                    bid = o.num("bid") ?: 0.0,
                    ask = o.num("ask") ?: 0.0,
                    last = o.num("lastPrice"),
                    openInterest = o.long("openInterest") ?: 0,
                    volume = o.long("volume") ?: 0,
                )
            }
        }
    }
}

internal fun JsonObject.obj(key: String) = this[key] as? JsonObject
internal fun JsonObject.arr(key: String) = this[key] as? JsonArray
internal fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(key: String): Double? = when (val v = this[key]) {
    is JsonPrimitive -> v.doubleOrNull
    is JsonObject -> (v["raw"] as? JsonPrimitive)?.doubleOrNull
    else -> null
}
internal fun JsonObject.long(key: String): Long? = when (val v = this[key]) {
    is JsonPrimitive -> v.longOrNull ?: v.doubleOrNull?.toLong()
    is JsonObject -> (v["raw"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    else -> null
}

/** Minimal RSS 2.0 item parser; avoids pulling an XML stack into the shared module. */
object Rss {
    private val item = Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private fun tag(name: String) = Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
    private val zoned = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
    private val titleRe = tag("title")
    private val linkRe = tag("link")
    private val dateRe = tag("pubDate")

    fun parse(symbol: String, xml: String, source: String): List<Headline> =
        item.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val title = titleRe.find(body)?.groupValues?.get(1)?.let(::clean) ?: return@mapNotNull null
            val date = dateRe.find(body)?.groupValues?.get(1)?.trim()?.let {
                runCatching {
                    java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                }.recoverCatching { _ -> java.time.ZonedDateTime.parse(it, zoned).toEpochSecond() }.getOrNull()
            }
            Headline(symbol, title, date, linkRe.find(body)?.groupValues?.get(1)?.let(::clean), source)
        }.toList()

    private fun clean(s: String) = s.trim()
        .removePrefix("<![CDATA[").removeSuffix("]]>")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .trim()
}
