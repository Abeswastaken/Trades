package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.engine.BuzzScreen
import com.abeswastaken.trades.core.engine.BuzzVerdict
import com.abeswastaken.trades.core.engine.Prompts
import com.abeswastaken.trades.core.engine.RiskLevel
import com.abeswastaken.trades.core.export.SchwabImport
import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.market.ApeWisdom
import com.abeswastaken.trades.core.market.BuzzItem
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.MarketClock
import com.abeswastaken.trades.core.market.MarketSession
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.market.RedditNews
import com.abeswastaken.trades.core.market.Rss
import com.abeswastaken.trades.core.model.AssetType
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class V12Test {

    // ---- Schwab CSV import -------------------------------------------------------------------

    private val schwab = """
        "Transactions  for account Individual ...123 as of 10/15/2026 18:00:00 ET"
        "Date","Action","Symbol","Description","Quantity","Price","Fees & Comm","Amount"
        "10/31/2026","Expired","F 10/30/2026 13.50 C","CALL FORD MOTOR CO ${'$'}13.5 EXP 10/30/26","3","","",""
        "10/20/2026","Qualified Dividend","F","FORD MOTOR CO","","","","${'$'}45.00"
        "10/14/2026 as of 10/13/2026","Sell to Open","F 10/30/2026 13.50 C","CALL FORD MOTOR CO","3","${'$'}0.14","${'$'}1.98","${'$'}40.02"
        "10/13/2026","Buy","F","FORD MOTOR CO","300","${'$'}12.08","","-${'$'}3,624.00"
        "10/12/2026","MoneyLink Transfer","","Tfr BANK","","","","${'$'}5,000.00"
        "10/12/2026","Buy","BADROW","","x","","",""
        "Transactions Total","","","","","","","${'$'}1,461.02"
    """.trimIndent()

    @Test fun `schwab export becomes fills oldest first, with expirations facing the right way`() {
        val p = SchwabImport.preview(schwab, emptyList())
        assertEquals(3, p.fills.size)
        val (buy, sell, exp) = p.fills
        assertEquals(Instrument.stock("F"), buy.instrument)
        assertEquals(300.0, buy.quantity, 1e-9)
        assertEquals(12.08, buy.price, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 13), sell.date) // the "as of" date
        assertEquals(Side.SELL, sell.side)
        assertEquals(AssetType.CALL, sell.instrument.type)
        assertEquals(13.5, sell.instrument.strike!!, 1e-9)
        assertEquals(1.98, sell.fees, 1e-9)
        assertEquals(Side.BUY, exp.side) // the short call expired, so it's bought back at 0
        assertEquals(0.0, exp.price, 1e-9)
        assertEquals(mapOf("MoneyLink Transfer" to 1, "Qualified Dividend" to 1), p.ignored)
        assertEquals(1, p.problems.size)
        val s = Ledger.compute(p.fills.mapIndexed { i, f -> f.copy(id = i + 1L, seq = i + 1L) }, 5000.0)
        assertEquals(1, s.positions.size)
        assertEquals(42.0 - 1.98, s.realizedTotal, 1e-9)
    }

    @Test fun `importing the same export twice adds nothing`() {
        val first = SchwabImport.preview(schwab, emptyList()).fills.mapIndexed { i, f -> f.copy(id = i + 1L, seq = i + 1L) }
        val again = SchwabImport.preview(schwab, first)
        assertTrue(again.fills.isEmpty())
        assertEquals(3, again.duplicates)
    }

    @Test fun `a fill logged by hand is matched even with a different note`() {
        val mine = Fill(9, LocalDate.of(2026, 10, 13), Instrument.stock("F"), Side.BUY, 300.0, 12.08, note = "from rec")
        val p = SchwabImport.preview(schwab, listOf(mine))
        assertEquals(1, p.duplicates)
        assertEquals(2, p.fills.size)
    }

    @Test fun `an expiry already marked in the app (on the Friday) isn't added again`() {
        val call = Instrument.call("F", 13.5, LocalDate.of(2026, 10, 30))
        val mine = listOf(
            Fill(1, LocalDate.of(2026, 10, 13), Instrument.stock("F"), Side.BUY, 300.0, 12.08),
            Fill(2, LocalDate.of(2026, 10, 13), call, Side.SELL, 3.0, 0.14),
            Fill(3, LocalDate.of(2026, 10, 30), call, Side.BUY, 3.0, 0.0, note = "Expired"),
        )
        val p = SchwabImport.preview(schwab, mine)
        assertTrue(p.fills.isEmpty())
        // and an expiry for something never held doesn't open a phantom short
        val stray = "\"Date\",\"Action\",\"Symbol\",\"Quantity\",\"Price\"\n\"10/31/2026\",\"Expired\",\"T 10/30/2026 30.00 C\",\"1\",\"\"\n"
        assertTrue(SchwabImport.preview(stray, emptyList()).fills.isEmpty())
    }

    @Test fun `selling shares bought before the export is flagged`() {
        val csv = "\"Date\",\"Action\",\"Symbol\",\"Quantity\",\"Price\"\n\"10/14/2026\",\"Sell\",\"F\",\"100\",\"\$12.50\"\n"
        val p = SchwabImport.preview(csv, emptyList())
        assertEquals(1, p.fills.size)
        assertTrue(p.problems.single().contains("short"))
    }

    @Test fun `not a schwab file says so`() {
        val e = runCatching { SchwabImport.preview("a,b,c\n1,2,3", emptyList()) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("Schwab"))
    }

    @Test fun `numbers and csv quoting`() {
        assertEquals(-3624.0, SchwabImport.number("-$3,624.00")!!, 1e-9)
        assertEquals(-12.0, SchwabImport.number("(12.00)")!!, 1e-9)
        assertNull(SchwabImport.number(""))
        assertEquals(listOf(listOf("a,b", "say \"hi\"", "")), SchwabImport.rows("\"a,b\",\"say \"\"hi\"\"\",\n"))
        assertEquals(AssetType.PUT, SchwabImport.instrument("SOFI 1/9/2027 7.00 P")!!.type)
    }

    // ---- Market clock ------------------------------------------------------------------------

    private fun at(date: String, time: String) = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), MarketClock.NY)

    @Test fun `market phases on a normal day`() {
        assertEquals(MarketSession.Phase.PRE, MarketClock.session(at("2026-10-15", "08:00")).phase)
        val open = MarketClock.session(at("2026-10-15", "10:00"))
        assertEquals(MarketSession.Phase.OPEN, open.phase)
        assertEquals(at("2026-10-15", "16:00"), open.nextChange)
        assertEquals(MarketSession.Phase.AFTER, MarketClock.session(at("2026-10-15", "17:00")).phase)
        // Friday night: next open is Monday
        val fri = MarketClock.session(at("2026-10-16", "21:00"))
        assertEquals(MarketSession.Phase.CLOSED, fri.phase)
        assertEquals(at("2026-10-19", "09:30"), fri.nextChange)
    }

    @Test fun `holidays and early closes`() {
        assertEquals("Thanksgiving", MarketClock.holiday(LocalDate.of(2026, 11, 26)))
        assertEquals("Good Friday", MarketClock.holiday(LocalDate.of(2026, 4, 3)))
        assertEquals("Independence Day", MarketClock.holiday(LocalDate.of(2026, 7, 3))) // July 4 is a Saturday
        assertEquals("Christmas", MarketClock.holiday(LocalDate.of(2027, 12, 24)))
        assertNull(MarketClock.holiday(LocalDate.of(2027, 12, 31))) // New Year's on Saturday isn't made up
        assertEquals(LocalTime.of(13, 0), MarketClock.closeTime(LocalDate.of(2026, 11, 27)))
        assertEquals(LocalTime.of(13, 0), MarketClock.closeTime(LocalDate.of(2026, 12, 24)))
        val tg = MarketClock.session(at("2026-11-26", "11:00"))
        assertEquals(MarketSession.Phase.CLOSED, tg.phase)
        assertEquals(at("2026-11-27", "09:30"), tg.nextChange)
        assertEquals(LocalDate.of(2027, 3, 28), MarketClock.easter(2027))
        assertEquals(MarketSession.Phase.AFTER, MarketClock.session(at("2026-11-27", "14:00")).phase)
        assertEquals(MarketSession.Phase.CLOSED, MarketClock.session(at("2026-11-27", "17:30")).phase)
    }

    // ---- Reddit and buzz ---------------------------------------------------------------------

    @Test fun `apewisdom parses numbers given as strings`() {
        val body = """{"count":2,"pages":1,"currentPage":1,"results":[
            {"rank":1,"ticker":"GME","name":"GameStop","mentions":"812","upvotes":"4410","rank_24h_ago":"9","mentions_24h_ago":"120"},
            {"rank":2,"ticker":"nvda","name":"NVIDIA &amp; Co","mentions":300,"upvotes":900,"rank_24h_ago":null,"mentions_24h_ago":null}]}"""
        val items = ApeWisdom.parse(body)
        assertEquals(listOf("GME", "NVDA"), items.map { it.ticker })
        assertEquals(812.0 / 120, items[0].mentionRatio!!, 1e-9)
        assertEquals(8, items[0].rankJump)
        assertEquals("NVIDIA & Co", items[1].name)
        assertNull(items[1].rankJump)
    }

    @Test fun `reddit atom feed parses with subreddit in the source`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom">
            <entry><author><name>/u/x</name></author><category term="wallstreetbets" label="r/wallstreetbets"/>
            <id>t3_1</id><link href="https://www.reddit.com/r/wallstreetbets/comments/1/gme/" />
            <updated>2026-10-15T14:00:00+00:00</updated><title>GME squeeze is back &amp; it&#39;s real</title></entry></feed>"""
        val h = Rss.parse("MARKET", xml, "Reddit").single()
        assertEquals("GME squeeze is back & it's real", h.title)
        assertEquals("Reddit r/wallstreetbets", h.source)
        assertEquals(1792072800L, h.publishedEpochSec)
        assertTrue(h.url!!.contains("/comments/1/"))
    }

    @Test fun `ticker mentions match whole words only`() {
        assertTrue(RedditNews.mentions("Loading up on \$F calls", "F"))
        assertTrue(RedditNews.mentions("F is cheap", "F"))
        assertFalse(RedditNews.mentions("Fed day tomorrow", "F"))
        assertFalse(RedditNews.mentions("SOFIX fund", "SOFI"))
    }

    private val now = 1792072800L
    private fun item(t: String, rank: Int, was: Int?, m: Int, m24: Int?) = BuzzItem(t, null, rank, was, m, m24, null)
    private fun quote(t: String, px: Double, chg: Double, vol: Long, avg: Long) =
        Quote(t, px, changePercent = chg, avgVolume = avg, volume = vol)

    @Test fun `crowd pile-in on a big move is a long shot`() {
        val c = BuzzScreen.check(item("GME", 1, 9, 812, 120), quote("GME", 18.0, 32.0, 90_000_000, 10_000_000), emptyList(), now)
        assertTrue(c.longShot)
        assertEquals(BuzzVerdict.PARTLY, c.verdict)
        assertTrue(c.longShotWhy!!.contains("No news"))
        assertTrue(c.standouts.first().startsWith("Mentions up"))
    }

    @Test fun `chatter with no move or news is hype only`() {
        val c = BuzzScreen.check(item("XYZ", 12, 14, 60, 50), quote("XYZ", 40.0, 0.4, 900_000, 1_000_000), emptyList(), now)
        assertFalse(c.longShot)
        assertEquals(BuzzVerdict.HYPE_ONLY, c.verdict)
    }

    @Test fun `news plus volume confirms, and reddit headlines don't count as news`() {
        val news = listOf(
            Headline("AMD", "AMD wins data-center deal", now - 3600, null, "Google News"),
            Headline("AMD", "AMD to the moon", now - 60, null, "Reddit r/wallstreetbets"),
        )
        val c = BuzzScreen.check(item("AMD", 3, 4, 200, 180), quote("AMD", 160.0, 4.0, 80_000_000, 40_000_000), news, now)
        assertEquals(BuzzVerdict.CONFIRMED, c.verdict)
        assertEquals(1, c.news.size)
        assertFalse(c.longShot) // big name, modest move
        assertEquals("GME", BuzzScreen.rank(listOf(c, BuzzScreen.check(item("GME", 1, 9, 812, 120), quote("GME", 18.0, 32.0, 90_000_000, 10_000_000), emptyList(), now))).first().ticker)
    }

    @Test fun `risk setting and reddit lines reach the recommendation prompt`() {
        val ctx = com.abeswastaken.trades.core.engine.RecContext(
            today = LocalDate.of(2026, 10, 15), ledger = Ledger.compute(emptyList(), 1000.0), startingCash = 1000.0,
            quotes = emptyMap(), candidates = emptyList(), newsDigest = null, history = emptyList(), focus = null,
            watchlist = emptyList(), risk = RiskLevel.HIGH, buzz = listOf("GME: #1 on Reddit"),
        )
        val text = Prompts.recommendRequest(ctx)
        assertTrue(text.contains(RiskLevel.HIGH.rules))
        assertTrue(text.contains("- GME: #1 on Reddit"))
    }

    @Test fun `long-shot ticket is the nearest OTM call with an offer`() {
        val exp = LocalDate.of(2026, 10, 23)
        fun c(k: Double, bid: Double, ask: Double) = com.abeswastaken.trades.core.market.OptionQuote("GME", k, exp, bid, ask, null, 10, 10)
        val t = BuzzScreen.ticket(18.0, listOf(c(17.5, 1.2, 1.3), c(19.0, 0.0, 0.0), c(20.0, 0.40, 0.45), c(25.0, 0.05, 0.06)), 1000.0)!!
        assertEquals(20.0, t.option.strike, 1e-9)
        assertEquals(45.0, t.cost, 1e-9)
        assertEquals(20.45, t.breakeven, 1e-9)
        assertEquals((20.45 / 18 - 1) * 100, t.movePercent, 1e-9)
        assertEquals(4.5, t.cashPercent!!, 1e-9)
    }

    @Test fun `roll target is the same strike or the next one up, next expiry`() {
        val cur = Instrument.call("F", 13.0, LocalDate.of(2026, 10, 16))
        val next = LocalDate.of(2026, 10, 23)
        fun c(k: Double, bid: Double) = com.abeswastaken.trades.core.market.OptionQuote("F", k, next, bid, bid + 0.02, null, 100, 10)
        assertEquals(13.5, com.abeswastaken.trades.core.scanner.CoveredCallScreener.rollTo(cur, listOf(c(12.5, 0.6), c(13.0, 0.0), c(13.5, 0.2)))!!.strike, 1e-9)
    }
}
