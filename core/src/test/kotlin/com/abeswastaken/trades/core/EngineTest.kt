package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.LlmClient
import com.abeswastaken.trades.core.engine.LlmException
import com.abeswastaken.trades.core.engine.LlmRequest
import com.abeswastaken.trades.core.engine.LlmResponse
import com.abeswastaken.trades.core.engine.PastRec
import com.abeswastaken.trades.core.engine.Pricing
import com.abeswastaken.trades.core.engine.Prompts
import com.abeswastaken.trades.core.engine.RecAction
import com.abeswastaken.trades.core.engine.RecContext
import com.abeswastaken.trades.core.engine.Recommender
import com.abeswastaken.trades.core.engine.Usage
import com.abeswastaken.trades.core.export.Csv
import com.abeswastaken.trades.core.ledger.Ledger
import com.abeswastaken.trades.core.market.Headline
import com.abeswastaken.trades.core.market.Quote
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import com.anthropic.core.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EngineTest {
    private val today = LocalDate.of(2026, 10, 15)
    private val ledger = Ledger.compute(
        listOf(
            Fill(1, today.minusDays(9), Instrument.stock("F"), Side.BUY, 300.0, 12.08),
            Fill(2, today.minusDays(9), Instrument.call("F", 13.5, LocalDate.of(2026, 10, 30)), Side.SELL, 3.0, 0.14),
        ),
        5000.0,
    )
    private val ctx = RecContext(
        today = today, ledger = ledger, startingCash = 5000.0,
        quotes = mapOf("F" to Quote("F", 12.6, nextEarnings = LocalDate.of(2026, 10, 28))),
        candidates = emptyList(), newsDigest = null,
        history = listOf(PastRec(today.minusDays(9), "Buy 300 F", "taken", null)),
        focus = "Thinking about rolling", watchlist = listOf("F", "SOFI"),
    )

    private val sampleJson = """
        {"action":"HOLD","ticker":"F","quantity":null,"limitPrice":null,"strike":null,"expiry":null,
         "summary":"Hold F through earnings","rationale":"Calls are working.","risks":"Earnings gap.",
         "exitPlan":"Let the calls expire.","confidence":"medium",
         "math":{"cashImpact":null,"maxGain":42,"maxLoss":null,"breakeven":11.94,"annualizedYieldPercent":null}}
    """.trimIndent()

    @Test fun `prompt carries cash, positions, earnings, history and the owner's note`() {
        val p = Prompts.recommendRequest(ctx)
        assertTrue(p.contains("Cash now: $1,418.00"))
        assertTrue(p.contains("F: 300 shares, avg cost 12.08"))
        assertTrue(p.contains("short 3 contracts"))
        assertTrue(p.contains("15 days to expiry"))
        assertTrue(p.contains("earnings 2026-10-28"))
        assertTrue(p.contains("SOFI: no quote"))
        assertTrue(p.contains("Buy 300 F [taken]"))
        assertTrue(p.contains("Thinking about rolling"))
    }

    @Test fun `system prompt stays free of volatile content so it caches`() {
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(Prompts.SYSTEM))
    }

    @Test fun `parses a structured recommendation, tolerating surrounding text`() {
        val rec = Recommender.parse("Here you go:\n$sampleJson\n")
        assertEquals(RecAction.HOLD, rec.actionEnum)
        assertEquals(42.0, rec.math.maxGain!!, 0.0)
        assertEquals(rec, Recommender.parse(Recommender.encode(rec)))
    }

    @Test(expected = LlmException::class) fun `garbage reply is a clear error`() {
        Recommender.parse("sorry")
    }

    @Test fun `recommend runs a Haiku digest first, then the chosen model, and logs both spends`() = runBlocking {
        val calls = mutableListOf<LlmRequest>()
        val fake = LlmClient { req ->
            calls += req
            if (req.model == ClaudeModel.HAIKU) LlmResponse("F: earnings Oct 28", Usage(1000, 100), req.model.id, "end_turn")
            else LlmResponse(sampleJson, Usage(5000, 800, cacheReadTokens = 2000), req.model.id, "end_turn")
        }
        val news = mapOf("F" to listOf(Headline("F", "Ford to report Oct 28", null, null)))
        val r = Recommender(fake).recommend(ClaudeModel.SONNET, ctx, news, useDigest = true)
        assertEquals(listOf(ClaudeModel.HAIKU, ClaudeModel.SONNET), calls.map { it.model })
        assertTrue(calls[1].user.contains("F: earnings Oct 28"))
        assertTrue(calls[1].jsonSchema != null)
        assertEquals(2, r.spend.size)
        assertEquals("Hold F through earnings", r.recommendation.summary)
    }

    @Test fun `a failed digest doesn't block the recommendation`() = runBlocking {
        val fake = LlmClient { req ->
            if (req.model == ClaudeModel.HAIKU) throw LlmException("down")
            LlmResponse(sampleJson, Usage(1, 1), req.model.id, "end_turn")
        }
        val r = Recommender(fake).recommend(ClaudeModel.OPUS, ctx, mapOf("F" to listOf(Headline("F", "x", null, null))), true)
        assertEquals(1, r.spend.size)
    }

    @Test fun `pricing matches list prices`() {
        // 1M in + 1M out on Sonnet 5.5 = $2 + $10
        assertEquals(12.0, Pricing.cost(ClaudeModel.SONNET, Usage(1_000_000, 1_000_000)), 1e-9)
        assertEquals(0.6, Pricing.cost(ClaudeModel.HAIKU, Usage(1_000_000, 1_000_000)), 1e-9)
        assertEquals(0.4, Pricing.cost(ClaudeModel.OPUS, Usage(0, 0, cacheReadTokens = 1_000_000)), 1e-9)
    }

    @Test fun `schema converts to SDK JSON without loss`() {
        val v = JsonValue.from(Prompts.RECOMMENDATION_SCHEMA)
        assertTrue(v.toString().contains("SELL_CALL"))
    }

    @Test fun `csv escapes commas and quotes`() {
        val csv = Csv.fills(listOf(Fill(1, today, Instrument.stock("F"), Side.BUY, 1.0, 1.0, note = "a, \"b\"")))
        assertTrue(csv.lines()[1].endsWith("\"a, \"\"b\"\"\""))
    }
}
