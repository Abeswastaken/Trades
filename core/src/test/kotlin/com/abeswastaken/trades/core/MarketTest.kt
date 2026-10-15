package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.market.FallbackMarketData
import com.abeswastaken.trades.core.market.FinnhubMarketData
import com.abeswastaken.trades.core.market.Rss
import com.abeswastaken.trades.core.market.YahooMarketData
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class MarketTest {
    private lateinit var server: MockWebServer
    private var crumbCalls = 0
    private var rejectFirstQuote = false

    private val quoteJson = """
        {"quoteResponse":{"result":[{"symbol":"F","regularMarketPrice":12.61,"regularMarketChangePercent":1.2,
        "averageDailyVolume3Month":51000000,"shortName":"Ford Motor","earningsTimestamp":1793203200}]}}
    """.trimIndent()

    // expirations: 2026-10-23 (8 DTE), 2026-12-18 (64 DTE)
    private val chainIndex = """
        {"optionChain":{"result":[{"expirationDates":[1792713600,1797552000],"options":[{"calls":[]}]}]}}
    """.trimIndent()
    private val chainPage = """
        {"optionChain":{"result":[{"options":[{"calls":[
          {"strike":13.0,"expiration":1792713600,"bid":0.12,"ask":0.14,"lastPrice":0.13,"openInterest":4000,"volume":120},
          {"strike":14.0,"expiration":1792713600,"bid":0.03,"ask":0.05,"openInterest":900}
        ]}]}]}}
    """.trimIndent()
    private val rss = """
        <rss><channel><item><title><![CDATA[Ford &amp; partners expand EV plant]]></title>
        <link>https://example.com/a</link><pubDate>Wed, 14 Oct 2026 12:00:00 +0000</pubDate></item>
        <item><title>Second</title></item></channel></rss>
    """.trimIndent()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/cookie") -> MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=abc; Path=/")
                    path.startsWith("/v1/test/getcrumb") -> { crumbCalls++; MockResponse().setBody("crumb$crumbCalls") }
                    path.startsWith("/v7/finance/quote") -> {
                        if (rejectFirstQuote && path.contains("crumb=crumb1")) MockResponse().setResponseCode(401)
                        else if (request.getHeader("Cookie")?.contains("A3=abc") != true) MockResponse().setResponseCode(403)
                        else MockResponse().setBody(quoteJson)
                    }
                    path.startsWith("/v7/finance/options/F?crumb") -> MockResponse().setBody(chainIndex)
                    path.startsWith("/v7/finance/options/F?date=") -> MockResponse().setBody(chainPage)
                    path.startsWith("/rss/2.0/headline") -> MockResponse().setBody(rss)
                    path.startsWith("/fh/quote") -> MockResponse().setBody("""{"c":12.7,"d":0.1,"dp":0.8}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun yahoo(): YahooMarketData {
        val base = server.url("/").toString().trimEnd('/')
        return YahooMarketData(queryBase = base, cookieUrl = "$base/cookie", rssBase = base)
    }

    @Test fun `quotes use the session cookie and crumb, and parse earnings`() = runBlocking {
        val q = yahoo().quotes(listOf("F"))["F"]!!
        assertEquals(12.61, q.price, 0.0)
        assertEquals(51_000_000L, q.avgVolume)
        assertEquals(LocalDate.of(2026, 10, 28), q.nextEarnings)
        assertEquals(1, crumbCalls)
    }

    @Test fun `a rejected crumb is refreshed once`() = runBlocking {
        rejectFirstQuote = true
        val q = yahoo().quotes(listOf("F"))
        assertEquals(12.61, q["F"]!!.price, 0.0)
        assertEquals(2, crumbCalls)
    }

    @Test fun `calls only fetch expiries inside the DTE window`() = runBlocking {
        val calls = yahoo().calls("F", LocalDate.of(2026, 10, 15), 7, 45)
        assertEquals(2, calls.size)
        assertEquals(LocalDate.of(2026, 10, 23), calls[0].expiry)
        assertEquals(0.13, calls[0].mid, 1e-9)
        assertEquals(4, server.requestCount) // cookie + crumb + index + one page
    }

    @Test fun `rss headlines are decoded`() = runBlocking {
        val n = yahoo().news("F", 5)
        assertEquals("Ford & partners expand EV plant", n[0].title)
        assertEquals("https://example.com/a", n[0].url)
        assertEquals(2, Rss.parse("F", rss, "x").size)
    }

    @Test fun `fallback uses Finnhub for quotes when Yahoo fails`() = runBlocking {
        val brokenYahoo = YahooMarketData(queryBase = server.url("/nope").toString().trimEnd('/'), cookieUrl = server.url("/cookie").toString())
        val fh = FinnhubMarketData("k", base = server.url("/fh").toString().trimEnd('/'))
        val logs = mutableListOf<String>()
        val q = FallbackMarketData(listOf(brokenYahoo, fh)) { logs += it }.quotes(listOf("F"))
        assertEquals("Finnhub", q["F"]!!.source)
        assertTrue(logs.single().contains("Yahoo"))
    }
}
