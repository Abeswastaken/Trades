package com.abeswastaken.trades.core.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The trading subs read for crowd talk. */
val REDDIT_SUBS = listOf("wallstreetbets", "stocks", "options", "thetagang")

/**
 * Reddit as a news source: hot posts across the trading subs, and a week of posts naming a ticker.
 * Crowd talk, not reporting; headlines carry "Reddit" in their source so prompts can treat them as unverified.
 * Reddit sometimes refuses www for apps, so old.reddit.com is tried next.
 */
class RedditNews(
    client: OkHttpClient,
    private val bases: List<String> = listOf("https://www.reddit.com", "https://old.reddit.com"),
    private val subs: List<String> = REDDIT_SUBS,
) : HttpNews(client) {
    override val id = NewsSourceId.REDDIT

    private suspend fun feed(path: String, symbol: String, params: Map<String, String>): List<Headline> {
        var last: Exception? = null
        for (b in bases) {
            try {
                val u = "$b$path".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                return Rss.parse(symbol, fetch(u), id.label)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: MarketDataException("Reddit returned nothing")
    }

    override suspend fun ticker(symbol: String, limit: Int): List<Headline> =
        feed("/r/${subs.joinToString("+")}/search.rss", symbol, mapOf("q" to symbol, "restrict_sr" to "1", "sort" to "new", "t" to "week"))
            .filter { mentions(it.title, symbol) }
            .take(limit)

    override suspend fun market(limit: Int): List<Headline> =
        feed("/r/${subs.joinToString("+")}/hot.rss", MARKET, mapOf("limit" to "50")).take(limit)

    companion object {
        /** True when the title names the ticker as a word ($F or F), so "F" doesn't match every post. */
        fun mentions(title: String, symbol: String): Boolean =
            Regex("(^|[^A-Za-z0-9])\\$?${Regex.escape(symbol)}([^A-Za-z0-9]|$)").containsMatchIn(title)
    }
}

/** One ticker's standing in Reddit chatter, from ApeWisdom's mention counts. */
data class BuzzItem(
    val ticker: String,
    val name: String?,
    val rank: Int,
    val rank24hAgo: Int?,
    val mentions: Int,
    val mentions24hAgo: Int?,
    val upvotes: Int?,
) {
    /** Mentions now over mentions a day ago. Null when there's no earlier count. */
    val mentionRatio: Double? get() = mentions24hAgo?.takeIf { it > 0 }?.let { mentions.toDouble() / it }

    /** Places climbed in a day (positive = rising). Null when it wasn't ranked yesterday. */
    val rankJump: Int? get() = rank24hAgo?.let { it - rank }
}

/**
 * ApeWisdom counts ticker mentions across Reddit (r/wallstreetbets, r/stocks, r/options and more) and
 * compares them with 24 hours earlier. Free, no key. https://apewisdom.io/api/
 */
class ApeWisdom(private val client: OkHttpClient, private val base: String = "https://apewisdom.io") {

    /** [filter] is "all-stocks", "wallstreetbets", "stocks", "options" and so on. */
    suspend fun top(filter: String = "all-stocks", page: Int = 1): List<BuzzItem> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/v1.0/filter/$filter/page/$page").header("User-Agent", HttpNews.USER_AGENT).build()
        client.newCall(req).execute().use {
            if (!it.isSuccessful) throw MarketDataException("ApeWisdom returned ${it.code}")
            parse(it.body?.string().orEmpty())
        }
    }

    companion object {
        fun parse(body: String): List<BuzzItem> {
            val root = YahooMarketData.json.parseToJsonElement(body) as? JsonObject
                ?: throw MarketDataException("ApeWisdom sent something that isn't JSON")
            return root.arr("results").orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                val t = o.str("ticker")?.trim()?.uppercase() ?: return@mapNotNull null
                BuzzItem(
                    ticker = t,
                    name = o.str("name")?.let { n -> n.replace("&amp;", "&").trim() },
                    rank = o.long("rank")?.toInt() ?: return@mapNotNull null,
                    rank24hAgo = o.long("rank_24h_ago")?.toInt(),
                    mentions = o.long("mentions")?.toInt() ?: 0,
                    mentions24hAgo = o.long("mentions_24h_ago")?.toInt(),
                    upvotes = o.long("upvotes")?.toInt(),
                )
            }
        }
    }
}
