package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.MarketData
import com.abeswastaken.trades.core.market.OptionQuote
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.core.scanner.CandidateKind
import com.abeswastaken.trades.core.scanner.CoveredCallScanner
import com.abeswastaken.trades.core.scanner.ScanRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScannerTest {
    private val today = LocalDate.of(2026, 10, 15)
    private val rules = ScanRules()

    private fun call(sym: String, strike: Double, days: Long, bid: Double, oi: Long = 500) =
        OptionQuote(sym, strike, today.plusDays(days), bid, bid + 0.02, null, oi, 10)

    @Test fun `contract filter applies DTE, OTM, open interest, bid and premium rules`() {
        val q = Quote("F", 12.5, avgVolume = 50_000_000)
        assertTrue(CoveredCallScanner.passesContract(q, call("F", 13.5, 15, 0.14), today, rules))
        assertTrue(!CoveredCallScanner.passesContract(q, call("F", 13.5, 3, 0.14), today, rules)) // too soon
        assertTrue(!CoveredCallScanner.passesContract(q, call("F", 12.6, 15, 0.30), today, rules)) // not 2% OTM
        assertTrue(!CoveredCallScanner.passesContract(q, call("F", 13.5, 15, 0.14, oi = 10), today, rules)) // thin
        assertTrue(!CoveredCallScanner.passesContract(q, call("F", 13.5, 15, 0.04), today, rules)) // bid too small
    }

    @Test fun `underlying filter skips penny stocks and thin volume but never held shares`() {
        assertEquals("price below 2.0", CoveredCallScanner.rejectUnderlying(Quote("X", 1.0, avgVolume = 9_000_000), rules, false))
        assertTrue(CoveredCallScanner.rejectUnderlying(Quote("X", 10.0, avgVolume = 50_000), rules, false)!!.contains("volume"))
        assertEquals(null, CoveredCallScanner.rejectUnderlying(Quote("X", 1.0, avgVolume = 0), rules, true))
    }

    @Test fun `held shares rank first, then by annualized yield, with buy-writes sized to cash`() {
        val ledger = Ledger.compute(listOf(Fill(1, today, Instrument.stock("F"), Side.BUY, 300.0, 12.08)), 5000.0)
        val quotes = mapOf("F" to Quote("F", 12.5, avgVolume = 1), "T" to Quote("T", 10.0, avgVolume = 1))
        val chains = mapOf(
            "F" to listOf(call("F", 13.0, 30, 0.10), call("F", 13.5, 15, 0.14)),
            "T" to listOf(call("T", 10.5, 15, 0.30)),
        )
        val ranked = CoveredCallScanner.rank(quotes, chains, ledger, today, rules)
        assertEquals(listOf("F", "F", "T"), ranked.map { it.quote.symbol })
        assertEquals(CandidateKind.COVERED_CALL, ranked[0].kind)
        assertEquals(13.5, ranked[0].option.strike, 0.0) // 0.14/15d beats 0.10/30d
        assertEquals(3, ranked[0].contracts)
        val bw = ranked.last()
        assertEquals(CandidateKind.BUY_WRITE, bw.kind)
        assertEquals(1, bw.contracts) // $1,376 cash buys one round lot at $10
    }

    @Test fun `scan skips stocks it can't afford and reports source errors`() = runBlocking {
        val market = object : MarketData {
            override val name = "fake"
            override suspend fun quotes(symbols: List<String>) = mapOf(
                "CHEAP" to Quote("CHEAP", 10.0, avgVolume = 1_000_000),
                "PRICEY" to Quote("PRICEY", 55.0, avgVolume = 1_000_000),
                "BROKEN" to Quote("BROKEN", 10.0, avgVolume = 1_000_000),
            )
            override suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int) =
                if (symbol == "BROKEN") error("chain down") else listOf(call(symbol, 10.5, 20, 0.20))
            override suspend fun news(symbol: String, limit: Int) = emptyList<Headline>()
        }
        val ledger = Ledger.compute(emptyList(), 2000.0)
        val r = CoveredCallScanner.scan(market, listOf("CHEAP", "PRICEY", "BROKEN", "GONE"), ledger, today, rules)
        assertEquals(listOf("CHEAP"), r.candidates.map { it.quote.symbol })
        assertTrue(r.rejectedUnderlyings.containsKey("PRICEY"))
        assertEquals(setOf("BROKEN", "GONE"), r.errors.keys)
    }
}

class OptionMarksTest {
    @Test fun `marks open short calls and reports premium captured, and the prompt shows it`() = runBlocking {
        val today = LocalDate.of(2026, 10, 15)
        val exp = LocalDate.of(2026, 10, 30)
        val call = Instrument.call("F", 13.5, exp)
        val ledger = Ledger.compute(
            listOf(Fill(1, today, Instrument.stock("F"), Side.BUY, 300.0, 12.08), Fill(2, today, call, Side.SELL, 3.0, 0.14)),
            5000.0,
        )
        var asked: Pair<Int, Int>? = null
        val market = object : MarketData {
            override val name = "fake"
            override suspend fun quotes(symbols: List<String>) = emptyMap<String, Quote>()
            override suspend fun calls(symbol: String, today: LocalDate, minDte: Int, maxDte: Int): List<OptionQuote> {
                asked = minDte to maxDte
                return listOf(OptionQuote("F", 13.5, exp, 0.03, 0.05, null, 900, 10), OptionQuote("F", 14.0, exp, 0.01, 0.02, null, 9, 1))
            }
            override suspend fun news(symbol: String, limit: Int) = emptyList<Headline>()
        }
        val marks = com.abeswastaken.trades.core.market.OptionMarks.fetch(market, ledger.positions, today)
        assertEquals(0 to 15, asked)
        val m = marks[call]!!
        assertEquals(0.04, m.mark, 1e-9)
        assertEquals(30.0, m.unrealized, 1e-9) // (0.04 - 0.14) * -3 * 100
        assertEquals(71.43, m.premiumCapturedPercent!!, 0.01)

        val ctx = com.abeswastaken.trades.core.engine.RecContext(
            today, ledger, 5000.0, emptyMap(), emptyList(), null, emptyList(), null, emptyList(), marks,
        )
        assertTrue(com.abeswastaken.trades.core.engine.Prompts.recommendRequest(ctx).contains("71.4% of premium captured"))
    }
}
