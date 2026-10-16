package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.engine.AlertContext
import com.abeswastaken.trades.core.engine.AlertEngine
import com.abeswastaken.trades.core.engine.AlertKind
import com.abeswastaken.trades.core.engine.AlertLevel
import com.abeswastaken.trades.core.engine.AlertRules
import com.abeswastaken.trades.core.engine.AlertSchedule
import com.abeswastaken.trades.core.engine.BuzzScreen
import com.abeswastaken.trades.core.engine.CheckKind
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.LlmClient
import com.abeswastaken.trades.core.engine.LlmResponse
import com.abeswastaken.trades.core.engine.MoveContext
import com.abeswastaken.trades.core.engine.MoveExplainer
import com.abeswastaken.trades.core.engine.OpenBriefContext
import com.abeswastaken.trades.core.engine.OpenBriefer
import com.abeswastaken.trades.core.engine.RiskLevel
import com.abeswastaken.trades.core.engine.Usage
import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.market.BuzzItem
import com.abeswastaken.trades.core.market.Dividend
import com.abeswastaken.trades.core.market.Edgar
import com.abeswastaken.trades.core.market.EdgarSummary
import com.abeswastaken.trades.core.market.MarketClock
import com.abeswastaken.trades.core.market.OptionQuote
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.market.YahooMarketData
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class AlertsTest {
    // Thursday 2026-10-15; Friday 2026-10-16 is the expiry.
    private val day = LocalDate.of(2026, 10, 15)
    private val fri = LocalDate.of(2026, 10, 16)
    private fun at(d: LocalDate, t: String) = ZonedDateTime.of(d, LocalTime.parse(t), MarketClock.NY)
    private val on = AlertRules(enabled = true)

    private val shareFill = Fill(1, day.minusDays(10), Instrument.stock("F"), Side.BUY, 300.0, 12.0)
    private val callFill = Fill(2, day.minusDays(5), Instrument.call("F", 13.0, fri), Side.SELL, 3.0, 0.20)
    private val ledger = Ledger.compute(listOf(shareFill, callFill), 10_000.0)

    private fun ctx(quotes: Map<String, Quote>, check: CheckKind = CheckKind.INTRADAY, now: ZonedDateTime = at(day, "11:00"), rules: AlertRules = on, watch: List<String> = emptyList()) =
        AlertContext(now, check, rules, ledger, quotes, watch)

    @Test fun `short call in the money is critical, near the strike is a heads-up`() {
        val itm = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 13.2, changePercent = 1.0))))
        assertEquals(AlertKind.SHORT_ITM, itm.first().kind)
        assertEquals(AlertLevel.CRITICAL, itm.first().level)
        val near = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 12.85, changePercent = 1.0))))
        assertEquals(listOf(AlertKind.SHORT_NEAR), near.map { it.kind })
        val far = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 12.0, changePercent = 1.0))))
        assertTrue(far.isEmpty())
        assertTrue(AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 13.2)), rules = on.copy(shortCalls = false))).isEmpty())
    }

    @Test fun `before the close an option expiring today near the money is critical`() {
        val e = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 12.9)), CheckKind.CLOSE, at(fri, "15:30")))
        assertTrue(e.any { it.kind == AlertKind.EXPIRING && it.level == AlertLevel.CRITICAL })
        // Not in the intraday watch, and not when it's far out of the money.
        assertFalse(AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 12.9)), CheckKind.INTRADAY, at(fri, "15:30"))).any { it.kind == AlertKind.EXPIRING })
        assertFalse(AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 11.0)), CheckKind.CLOSE, at(fri, "15:30"))).any { it.kind == AlertKind.EXPIRING })
    }

    @Test fun `holding drop is critical, rise a heads-up, watchlist an opportunity, keyed per threshold step`() {
        val r = on.copy(shortCalls = false)
        val down = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 11.0, change = -0.7, changePercent = -6.0)), rules = r))
        assertEquals(AlertLevel.CRITICAL, down.single().level)
        assertTrue(down.single().body.contains("-\$210"))
        assertEquals("move:F:$day:-1", down.single().key)
        val deeper = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 10.5, changePercent = -11.0)), rules = r))
        assertEquals("move:F:$day:-2", deeper.single().key)
        val up = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 12.6, changePercent = 5.5)), rules = r))
        assertEquals(AlertLevel.NOTICE, up.single().level)
        assertTrue(up.single().body.contains("short call"))
        val watch = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 12.0), "NOK" to Quote("NOK", 6.0, changePercent = 7.0)), rules = r, watch = listOf("NOK")))
        assertEquals(AlertLevel.OPPORTUNITY, watch.single().level)
    }

    @Test fun `ex-dividend tomorrow with little time value left is early assignment risk`() {
        val c = ctx(mapOf("F" to Quote("F", 13.5))).copy(
            dividends = mapOf("F" to Dividend("F", fri, 0.15)),
            optionQuotes = mapOf(Instrument.call("F", 13.0, fri) to OptionQuote("F", 13.0, fri, 0.52, 0.56, null, 100, 10)),
        )
        val e = AlertEngine.evaluate(c).first { it.kind == AlertKind.EX_DIVIDEND }
        assertEquals(AlertLevel.CRITICAL, e.level)
        // Plenty of time value: still flagged, but not as money at risk.
        val rich = c.copy(optionQuotes = mapOf(Instrument.call("F", 13.0, fri) to OptionQuote("F", 13.0, fri, 0.9, 1.0, null, 100, 10)))
        assertEquals(AlertLevel.NOTICE, AlertEngine.evaluate(rich).first { it.kind == AlertKind.EX_DIVIDEND }.level)
        // Ex-date next week: nothing yet.
        val later = c.copy(dividends = mapOf("F" to Dividend("F", fri.plusDays(5), 0.15)))
        assertFalse(AlertEngine.evaluate(later).any { it.kind == AlertKind.EX_DIVIDEND })
    }

    @Test fun `unusual volume needs an hour of trading and options flow beats open interest`() {
        val r = on.copy(unusual = true, shortCalls = false)
        val q = mapOf("NOK" to Quote("NOK", 6.0, avgVolume = 10_000_000, volume = 12_000_000))
        assertTrue(AlertEngine.evaluate(ctx(q, now = at(day, "11:30"), rules = r, watch = listOf("NOK"))).any { it.kind == AlertKind.VOLUME })
        assertFalse(AlertEngine.evaluate(ctx(q, now = at(day, "09:50"), rules = r, watch = listOf("NOK"))).any { it.kind == AlertKind.VOLUME })
        assertFalse(AlertEngine.evaluate(ctx(q, now = at(day, "11:30"), rules = r.copy(unusual = false), watch = listOf("NOK"))).any { it.kind == AlertKind.VOLUME })
        val chain = listOf(OptionQuote("NOK", 7.0, fri, 0.1, 0.12, null, 300, 2_000), OptionQuote("NOK", 8.0, fri, 0.02, 0.03, null, 5_000, 900))
        val flow = AlertEngine.evaluate(ctx(emptyMap(), rules = r).copy(chains = mapOf("NOK" to chain)))
        assertTrue(flow.single().body.contains("7.00"))
    }

    @Test fun `long shots, insider buys and offerings`() {
        val buzz = BuzzScreen.check(BuzzItem("GME", null, 1, 9, 812, 120, null), Quote("GME", 18.0, changePercent = 32.0, avgVolume = 10_000_000, volume = 90_000_000), emptyList(), 0)
        val sec = EdgarSummary(
            "GME", listOf(com.abeswastaken.trades.core.market.Filing("424B5", day, "", "u")),
            listOf(com.abeswastaken.trades.core.market.InsiderTrade("Cohen Ryan", "CEO", "P", 1000.0, 20.0, day)),
        )
        val e = AlertEngine.evaluate(ctx(emptyMap(), rules = on.copy(insider = true)).copy(buzz = listOf(buzz), edgar = mapOf("GME" to sec)))
        assertEquals(setOf(AlertKind.LONG_SHOT, AlertKind.INSIDER, AlertKind.OFFERING), e.map { it.kind }.toSet())
        assertTrue(AlertEngine.evaluate(ctx(emptyMap()).copy(edgar = mapOf("GME" to sec))).isEmpty()) // insider off by default
        val checked = buzz.withFilings(sec.lines())
        assertTrue(checked.promptLine().contains("SEC: Insider buying"))
        assertTrue(checked.longShotWhy!!.contains("dilution"))
    }

    @Test fun `quiet mode only buzzes for money at risk`() {
        val q = on.copy(quiet = true)
        val e = AlertEngine.evaluate(ctx(mapOf("F" to Quote("F", 13.2, changePercent = 6.0)), rules = q))
        assertTrue(e.filter { q.buzzes(it) }.all { it.level == AlertLevel.CRITICAL })
        assertTrue(e.any { !q.buzzes(it) })
    }

    @Test fun `schedule runs 9-31, every interval, and 30 minutes before the close on trading days`() {
        val d = AlertSchedule.day(day, on)
        assertEquals(CheckKind.OPEN, d.first().kind)
        assertEquals(LocalTime.of(9, 31), d.first().at.toLocalTime())
        assertEquals(LocalTime.of(9, 46), d[1].at.toLocalTime())
        assertEquals(LocalTime.of(15, 30), d.single { it.kind == CheckKind.CLOSE }.at.toLocalTime())
        assertTrue(d.all { it.at.toLocalTime() < LocalTime.of(16, 0) })
        assertEquals(LocalTime.of(10, 1), AlertSchedule.day(day, on.copy(intervalMin = 30))[1].at.toLocalTime())
        // Friday after the close: next is Monday 9:31.
        val next = AlertSchedule.next(at(fri, "16:05"), on)!!
        assertEquals(LocalDate.of(2026, 10, 19), next.at.toLocalDate())
        assertEquals(CheckKind.OPEN, next.kind)
        // Day after Thanksgiving closes at 1:00, so the close check is 12:30.
        val blackFriday = LocalDate.of(2026, 11, 27)
        assertEquals(LocalTime.of(12, 30), AlertSchedule.day(blackFriday, on).single { it.kind == CheckKind.CLOSE }.at.toLocalTime())
        assertTrue(AlertSchedule.day(LocalDate.of(2026, 11, 26), on).isEmpty())
        assertNull(AlertSchedule.next(at(day, "10:00"), AlertRules()))
        assertEquals(CheckKind.INTRADAY, AlertSchedule.next(at(day, "10:00"), on)!!.kind)
    }

    private fun fake(text: String) = LlmClient { LlmResponse(text, Usage(1000, 200), it.model.id, "end_turn") }

    @Test fun `open brief and move explainer read Claude's json`() = runBlocking {
        val (b, spend) = OpenBriefer(fake("""{"title":"F short call in the money","bullets":["Roll it"],"tone":"ACT"}"""))
            .brief(ClaudeModel.SONNET, OpenBriefContext(day, ledger, mapOf("F" to Quote("F", 13.2)), listOf("NOK"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), RiskLevel.NORMAL, false))
        assertEquals("ACT", b.tone)
        assertEquals("open check", spend.single().purpose)
        val req = OpenBriefer.request(OpenBriefContext(day, ledger, mapOf("F" to Quote("F", 13.2)), listOf("NOK"), emptyList(), emptyList(), emptyList(), emptyList(), listOf("Offering filed"), RiskLevel.HIGH, true))
        assertTrue(req.contains("F 2026-10-16 13C") && req.contains("Offering filed") && req.contains("margin"))
        val (m, _) = MoveExplainer(fake("""Sure: {"why":"Recall news.","driver":"NEWS","confidence":"high","lesson":{"title":"Gaps and short calls","text":"..."},"watchFor":"Follow-through"}"""))
            .explain(ClaudeModel.SONNET, MoveContext("F", null, emptyList(), listOf("300 shares"), emptyList(), null, listOf("Old lesson")))
        assertEquals("Gaps and short calls", m.lesson.title)
        assertTrue(MoveExplainer.request(MoveContext("F", null, emptyList(), emptyList(), emptyList(), null, listOf("Old lesson"))).contains("Old lesson"))
    }

    @Test fun `edgar parsing`() {
        val tickers = Edgar.parseTickers("""{"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},"1":{"cik_str":1818874,"ticker":"SOFI","title":"SoFi"}}""")
        assertEquals(1818874L, tickers["SOFI"])
        val sub = YahooMarketData.json.parseToJsonElement(
            """{"filings":{"recent":{"accessionNumber":["0001-26-000001","0001-26-000002"],"filingDate":["2026-10-14","2026-10-01"],
               "form":["4","S-3"],"primaryDocument":["xslF345X05/wk-form4_1.xml","s3.htm"],"primaryDocDescription":["",""]}}}"""
        ).jsonObject
        val f = Edgar.parseSubmissions(1818874, sub)
        assertEquals("https://www.sec.gov/Archives/edgar/data/1818874/000126000001/wk-form4_1.xml", f[0].url)
        assertTrue(Edgar.isOffering(f[1].form))
        val xml = """<ownershipDocument><reportingOwner><reportingOwnerId><rptOwnerName>Noto Anthony</rptOwnerName></reportingOwnerId>
            <reportingOwnerRelationship><isDirector>1</isDirector><officerTitle>CEO</officerTitle></reportingOwnerRelationship></reportingOwner>
            <nonDerivativeTable><nonDerivativeTransaction><transactionDate><value>2026-10-10</value></transactionDate>
            <transactionCoding><transactionFormType>4</transactionFormType><transactionCode>P</transactionCode></transactionCoding>
            <transactionAmounts><transactionShares><value>50000</value></transactionShares><transactionPricePerShare><value>7.50</value></transactionPricePerShare>
            <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode></transactionAmounts></nonDerivativeTransaction></nonDerivativeTable></ownershipDocument>"""
        val t = Edgar.parseForm4(xml, day).single()
        assertEquals("CEO", t.role)
        assertEquals(375_000.0, t.value!!, 0.01)
        assertTrue(EdgarSummary("SOFI", f.drop(1), listOf(t)).lines().first().contains("\$375,000"))
    }

    @Test fun `yahoo dividend and quote extras parse`() {
        val d = YahooMarketData.parseDividend("KO", YahooMarketData.json.parseToJsonElement(
            """{"quoteSummary":{"result":[{"summaryDetail":{"dividendRate":{"raw":2.04},"exDividendDate":{"raw":1790985600}},"calendarEvents":{}}]}}"""
        ).jsonObject)
        assertEquals(0.51, d.amount!!, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 3), d.exDate)
        val q = YahooMarketData.parseQuotes(YahooMarketData.json.parseToJsonElement(
            """{"quoteResponse":{"result":[{"symbol":"F","regularMarketPrice":12.1,"regularMarketPreviousClose":11.9,"regularMarketOpen":12.0,"fiftyTwoWeekHigh":14.0,"fiftyTwoWeekLow":9.5}]}}"""
        ).jsonObject).single()
        assertEquals(11.9, q.previousClose!!, 1e-9)
        assertEquals(9.5, q.low52!!, 1e-9)
    }
}
