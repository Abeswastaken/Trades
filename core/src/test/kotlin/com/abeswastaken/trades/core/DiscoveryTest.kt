package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.LlmClient
import com.abeswastaken.trades.core.engine.LlmRequest
import com.abeswastaken.trades.core.engine.LlmResponse
import com.abeswastaken.trades.core.engine.MarketCandidate
import com.abeswastaken.trades.core.engine.MarketCandidates
import com.abeswastaken.trades.core.engine.MarketScanContext
import com.abeswastaken.trades.core.engine.MarketScanner
import com.abeswastaken.trades.core.engine.Usage
import com.abeswastaken.trades.core.market.AlphaVantageNews
import com.abeswastaken.trades.core.market.ChainSnapshot
import com.abeswastaken.trades.core.market.Discovery
import com.abeswastaken.trades.core.market.FinnhubNews
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.MarketData
import com.abeswastaken.trades.core.market.MarketDataException
import com.abeswastaken.trades.core.market.MarketauxNews
import com.abeswastaken.trades.core.market.NewsHub
import com.abeswastaken.trades.core.market.NewsProvider
import com.abeswastaken.trades.core.market.NewsSourceId
import com.abeswastaken.trades.core.market.NewsSources
import com.abeswastaken.trades.core.market.OptionQuote
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.market.Rss
import com.abeswastaken.trades.core.market.YahooMarketData
import com.abeswastaken.trades.core.scanner.CoveredCallScreener
import com.abeswastaken.trades.core.scanner.ScanRules
import com.abeswastaken.trades.core.scanner.ScreenerRules
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class DiscoveryTest {
    private val today = LocalDate.of(2026, 10, 15) // Thursday

    // ---- News ------------------------------------------------------------------------------------

    @Test fun `merge interleaves sources, drops repeats across outlets, newest first`() {
        val a = listOf(Headline("F", "Ford beats on earnings - Reuters", 300, null, "Google"), Headline("F", "Old A", 100, null))
        val b = listOf(Headline("F", "Ford beats on earnings", 200, null, "Yahoo"), Headline("F", "Fresh B", 400, null))
        val m = NewsHub.merge(listOf(a, b), 10)
        assertEquals(listOf("Fresh B", "Ford beats on earnings - Reuters", "Old A"), m.map { it.title })
    }

    @Test fun `hub keeps going when one source fails`() = runBlocking {
        val ok = object : NewsProvider {
            override val id = NewsSourceId.YAHOO
            override suspend fun ticker(symbol: String, limit: Int) = listOf(Headline(symbol, "Works", 1, null))
            override suspend fun market(limit: Int) = emptyList<Headline>()
        }
        val broken = object : NewsProvider {
            override val id = NewsSourceId.CNBC
            override suspend fun ticker(symbol: String, limit: Int): List<Headline> = throw MarketDataException("down")
            override suspend fun market(limit: Int) = emptyList<Headline>()
        }
        val logs = mutableListOf<String>()
        val n = NewsHub(listOf(broken, ok)) { logs += it }.ticker("F")
        assertEquals("Works", n.single().title)
        assertTrue(logs.single().contains("CNBC"))
    }

    @Test fun `keyed sources are skipped until a key is saved`() {
        val built = NewsSources.build(OkHttpClient(), NewsSourceId.entries.toSet(), mapOf(NewsSourceId.FINNHUB to "k"))
        val ids = built.map { it.id }
        assertTrue(NewsSourceId.FINNHUB in ids)
        assertFalse(NewsSourceId.ALPHA_VANTAGE in ids)
        assertFalse(NewsSourceId.MARKETAUX in ids)
        assertTrue(NewsSourceId.GOOGLE in ids)
    }

    @Test fun `alpha vantage parses sentiment and reports the rate-limit note`() {
        val body = """{"feed":[{"title":"Ford raises guidance","url":"https://x/1","time_published":"20261014T133000",
            "source":"Benzinga","overall_sentiment_score":0.1,
            "ticker_sentiment":[{"ticker":"F","ticker_sentiment_score":"0.42"}]}]}"""
        val h = AlphaVantageNews.parse("F", body, 5).single()
        assertEquals(0.42, h.sentiment!!, 1e-9)
        assertEquals("Benzinga via Alpha Vantage", h.source)
        assertEquals(1791984600L, h.publishedEpochSec) // 2026-10-14T13:30Z
        try {
            AlphaVantageNews.parse("F", """{"Information":"25 requests per day"}""", 5); fail()
        } catch (e: MarketDataException) { assertTrue(e.message!!.contains("25 requests")) }
    }

    @Test fun `marketaux and finnhub parse`() {
        val mx = MarketauxNews.parse(
            "F", """{"data":[{"title":"Ford recalls","url":"u","published_at":"2026-10-14T12:00:00.000000Z","source":"reuters.com",
            "entities":[{"symbol":"F","sentiment_score":-0.3}]}]}""", 5,
        ).single()
        assertEquals(-0.3, mx.sentiment!!, 1e-9)
        val fh = FinnhubNews.parse("MARKET", """[{"headline":"Stocks rise","datetime":1792935000,"url":"u","source":"CNBC"}]""", 5).single()
        assertEquals("CNBC via Finnhub", fh.source)
        try { FinnhubNews.parse("F", """{"error":"Invalid API key"}""", 5); fail() } catch (e: MarketDataException) {
            assertTrue(e.message!!.contains("Invalid API key"))
        }
    }

    @Test fun `rss handles attributes on items and zone abbreviations`() {
        val xml = """<rss><channel><title>Feed</title><item rdf:about="x"><title>A &amp; B</title>
            <pubDate>Wed, 14 Oct 2026 08:00:00 EDT</pubDate><link>https://x</link></item></channel></rss>"""
        val h = Rss.parse("MARKET", xml, "MarketWatch").single()
        assertEquals("A & B", h.title)
        val curly = Rss.parse("M", "<item><title>Anthropic&#x2019;s growth &#8212; slows</title></item>", "x").single()
        assertEquals("Anthropic\u2019s growth \u2014 slows", curly.title)
        assertEquals(1791979200L, h.publishedEpochSec) // 08:00 EDT = 12:00Z
    }

    // ---- Covered-call screener -------------------------------------------------------------------

    private fun call(sym: String, strike: Double, expiry: LocalDate, bid: Double, oi: Long = 500) =
        OptionQuote(sym, strike, expiry, bid, bid + 0.02, null, oi, 10)

    private val weekly = listOf(16L, 23L, 30L, 37L).map { today.plusDays(it - 15) } // Oct 16, 23, 30, Nov 6
    private val monthly = listOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 18))

    @Test fun `weeklies need four expiries inside five weeks`() {
        assertTrue(CoveredCallScreener.hasWeeklies(weekly, today))
        assertFalse(CoveredCallScreener.hasWeeklies(monthly, today))
    }

    @Test fun `best picks the nearest OTM strike that passes OI and bid`() {
        val q = Quote("F", 12.20, avgVolume = 50_000_000)
        val exp = today.plusDays(8)
        val snap = ChainSnapshot(weekly, listOf(
            call("F", 12.0, exp, 0.40), // in the money
            call("F", 12.5, exp, 0.15, oi = 10), // thin
            call("F", 13.0, exp, 0.08),
            call("F", 14.0, exp, 0.02),
        ))
        val hit = CoveredCallScreener.best(q, snap, today, ScreenerRules())!!
        assertEquals(13.0, hit.option.strike, 0.0)
        assertEquals(0.08 / 12.20 * 100, hit.premiumPercent, 1e-9)
        assertEquals((0.08 + 13.0 - 12.20) / 12.20 * 100, hit.ifCalledPercent, 1e-9)
        assertNull(CoveredCallScreener.best(q, ChainSnapshot(monthly, snap.calls), today, ScreenerRules()))
    }

    @Test fun `with the market closed the last trade stands in for the missing bid`() {
        val q = Quote("F", 12.20, avgVolume = 50_000_000)
        val closed = OptionQuote("F", 12.5, today.plusDays(8), 0.0, 0.0, 0.11, 900, 0)
        val hit = CoveredCallScreener.best(q, ChainSnapshot(weekly, listOf(closed)), today, ScreenerRules())!!
        assertFalse(hit.option.liveBid)
        assertEquals(0.11 / 12.20 * 100, hit.premiumPercent, 1e-9)
    }

    private class FakeDiscovery(
        val screenResult: List<Quote>?,
        val chains: Map<String, ChainSnapshot>,
    ) : Discovery {
        override suspend fun screen(minPrice: Double, maxPrice: Double, minAvgVolume: Long, size: Int) =
            screenResult ?: throw MarketDataException("screener down")
        override suspend fun predefined(list: String, size: Int) = when (list) {
            "most_actives" -> listOf(Quote("F", 12.0, changePercent = 1.0, avgVolume = 50_000_000), Quote("NVDA", 180.0, avgVolume = 200_000_000))
            "day_gainers" -> listOf(Quote("SOFI", 15.0, changePercent = 9.0, avgVolume = 60_000_000), Quote("F", 12.0, avgVolume = 50_000_000))
            "day_losers" -> listOf(Quote("TINY", 3.0, changePercent = -20.0, avgVolume = 1_000))
            else -> emptyList()
        }
        override suspend fun trending(size: Int) = listOf("^GSPC", "BTC-USD", "PFE")
        override suspend fun nearestCalls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int) =
            chains[symbol] ?: throw MarketDataException("no chain")
    }

    private class FakeMarket(val q: Map<String, Quote>) : MarketData {
        override val name = "fake"
        var asked = emptyList<String>()
        override suspend fun quotes(symbols: List<String>): Map<String, Quote> { asked = symbols; return q.filterKeys { it in symbols } }
        override suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int) = emptyList<OptionQuote>()
        override suspend fun news(symbol: String, limit: Int) = emptyList<Headline>()
    }

    @Test fun `screener ranks by annualized premium and counts names without weeklies`() = runBlocking {
        val exp = today.plusDays(8)
        val d = FakeDiscovery(
            listOf(Quote("F", 12.0, avgVolume = 5_000_000), Quote("T", 18.0, avgVolume = 5_000_000), Quote("PFE", 19.0, avgVolume = 5_000_000)),
            mapOf(
                "F" to ChainSnapshot(weekly, listOf(call("F", 12.5, exp, 0.10))),
                "T" to ChainSnapshot(weekly, listOf(call("T", 18.5, exp, 0.30))),
                "PFE" to ChainSnapshot(monthly, emptyList()),
            ),
        )
        val r = CoveredCallScreener.run(d, FakeMarket(emptyMap()), today, ScreenerRules())
        assertEquals(listOf("T", "F"), r.hits.map { it.quote.symbol })
        assertEquals(1, r.noWeeklies)
        assertEquals("Yahoo screener", r.universeSource)
    }

    @Test fun `screener falls back to the built-in list when Yahoo's screener fails`() = runBlocking {
        val market = FakeMarket(mapOf("F" to Quote("F", 12.0, avgVolume = 5_000_000), "AMD" to Quote("AMD", 150.0, avgVolume = 5_000_000)))
        val d = FakeDiscovery(null, mapOf("F" to ChainSnapshot(weekly, listOf(call("F", 12.5, today.plusDays(8), 0.10)))))
        val r = CoveredCallScreener.run(d, market, today, ScreenerRules())
        assertEquals("built-in list", r.universeSource)
        assertEquals(1, r.scanned) // AMD is outside $5-$20
        assertEquals("F", r.hits.single().quote.symbol)
        assertTrue(market.asked.size > 50)
    }

    // ---- Market scan ---------------------------------------------------------------------------

    @Test fun `market candidates drop indexes, the illiquid, the unaffordable and the watched`() = runBlocking {
        val market = FakeMarket(mapOf("PFE" to Quote("PFE", 25.0, avgVolume = 30_000_000)))
        val c = MarketCandidates.gather(FakeDiscovery(null, emptyMap()), market, ScanRules(), cash = 5000.0, exclude = setOf("SOFI"))
        assertEquals(listOf("F", "PFE"), c.map { it.quote.symbol }) // F is on two lists, so first
        assertEquals(listOf("most active", "top gainer"), c.first().tags)
        assertEquals(listOf("PFE"), market.asked) // only trending names were re-quoted, and only real tickers
    }

    @Test fun `market scan drops picks that were not candidates`() = runBlocking {
        var sent: LlmRequest? = null
        val llm = LlmClient { req ->
            sent = req
            LlmResponse(
                """{"marketRead":"Risk-on.","picks":[
                  {"ticker":"f","idea":"COVERED_CALL","thesis":"Cheap premium","catalyst":null,"risk":"Earnings","confidence":"medium"},
                  {"ticker":"TSLA","idea":"BUY","thesis":"x","catalyst":null,"risk":"y","confidence":"low"}]}""",
                Usage(1000, 300), "claude-sonnet-5-5", "end_turn",
            )
        }
        val ctx = MarketScanContext(
            today, 5000.0, listOf(MarketCandidate(Quote("F", 12.0, changePercent = 1.0, avgVolume = 50_000_000), listOf("most active"))),
            listOf(Headline("MARKET", "Stocks rally on rate-cut hopes", 1, null, "CNBC", 0.3)), focus = "Prefer autos",
        )
        val r = MarketScanner(llm).scan(ClaudeModel.SONNET, ctx)
        assertEquals(listOf("F"), r.reply.picks.map { it.ticker })
        assertEquals(listOf("TSLA"), r.dropped)
        assertEquals("market scan", r.spend.single().purpose)
        val user = sent!!.user
        assertTrue(user.contains("Stocks rally on rate-cut hopes [CNBC] (sentiment +0.30)"))
        assertTrue(user.contains("- F: 12.00, +1.0% today"))
        assertTrue(user.contains("Prefer autos"))
        assertEquals(MarketScanner.SCHEMA, sent!!.jsonSchema)
    }

    // ---- Yahoo discovery endpoints ---------------------------------------------------------------

    @Test fun `yahoo screener posts the price band with the crumb and parses quotes`() = runBlocking {
        val server = MockWebServer()
        var posted = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: ""
                return when {
                    p.startsWith("/cookie") -> MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=abc; Path=/")
                    p.startsWith("/v1/test/getcrumb") -> MockResponse().setBody("crumbX")
                    p.startsWith("/v1/finance/screener?") && request.method == "POST" && p.contains("crumb=crumbX") -> {
                        posted = request.body.readUtf8()
                        MockResponse().setBody("""{"finance":{"result":[{"quotes":[{"symbol":"F","regularMarketPrice":12.1,"averageDailyVolume3Month":50000000}]}],"error":null}}""")
                    }
                    p.startsWith("/v1/finance/trending/US") -> MockResponse().setBody("""{"finance":{"result":[{"quotes":[{"symbol":"PFE"},{"symbol":"F"}]}]}}""")
                    p.startsWith("/v1/finance/screener/predefined/saved") ->
                        MockResponse().setBody("""{"finance":{"result":null,"error":{"code":"Not Found","description":"No screener"}}}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val y = YahooMarketData(queryBase = base, cookieUrl = "$base/cookie", rssBase = base)
            val q = y.screen(5.0, 20.0, 1_000_000, 60)
            assertEquals("F", q.single().symbol)
            assertTrue(posted.contains("\"btwn\",\"operands\":[\"intradayprice\",5.0,20.0]"))
            assertEquals(listOf("PFE", "F"), y.trending(10))
            try { y.predefined("nope", 5); fail() } catch (e: MarketDataException) { assertTrue(e.message!!.contains("No screener")) }
        } finally {
            server.shutdown()
        }
    }
}
