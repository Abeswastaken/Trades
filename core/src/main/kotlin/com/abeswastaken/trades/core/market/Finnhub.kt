package com.abeswastaken.trades.core.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

/** Finnhub free tier: fallback for quotes and company news when Yahoo is down. Needs a free API key. */
class FinnhubMarketData(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val base: String = "https://finnhub.io/api/v1",
    private val today: () -> LocalDate = { LocalDate.now() },
) : MarketData {
    override val name = "Finnhub"

    private suspend fun get(path: String, params: Map<String, String>): String {
        val url = "$base$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).header("X-Finnhub-Token", apiKey).build()).execute().use {
                if (!it.isSuccessful) throw MarketDataException("Finnhub $path returned ${it.code}")
                it.body?.string().orEmpty()
            }
        }
    }

    override suspend fun quotes(symbols: List<String>): Map<String, Quote> =
        symbols.mapNotNull { sym ->
            val o = YahooMarketData.json.parseToJsonElement(get("/quote", mapOf("symbol" to sym))).jsonObject
            parseQuote(sym, o)?.let { sym to it }
        }.toMap()

    override suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): List<OptionQuote> =
        throw UnsupportedOperationException("Finnhub free tier has no options chains")

    override suspend fun news(symbol: String, limit: Int): List<Headline> {
        val to = today()
        val body = get("/company-news", mapOf("symbol" to symbol, "from" to to.minusDays(7).toString(), "to" to to.toString()))
        return YahooMarketData.json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            Headline(symbol, o.str("headline") ?: return@mapNotNull null, o.long("datetime"), o.str("url"), "Finnhub")
        }.take(limit)
    }

    companion object {
        internal fun parseQuote(symbol: String, o: JsonObject): Quote? {
            val price = o.num("c")?.takeIf { it > 0 } ?: return null
            return Quote(symbol, price, change = o.num("d"), changePercent = o.num("dp"), source = "Finnhub")
        }
    }
}
