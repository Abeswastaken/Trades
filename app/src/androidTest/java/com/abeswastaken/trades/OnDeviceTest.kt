package com.abeswastaken.trades

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.abeswastaken.trades.core.engine.AnthropicLlm
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.LlmRequest
import com.abeswastaken.trades.core.engine.Prompts
import com.abeswastaken.trades.core.engine.Recommender
import com.abeswastaken.trades.core.market.YahooMarketData
import com.abeswastaken.trades.core.model.Fill
import com.abeswastaken.trades.core.model.Instrument
import com.abeswastaken.trades.core.model.Side
import com.abeswastaken.trades.data.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Runs on a real Android runtime: proves the SDK, Yahoo client, Keystore and backup work there, not just on the JVM. */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<App>()

    @Test fun anthropicSdkWorksOnAndroid() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"m","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                   "content":[{"type":"text","text":"{\"action\":\"HOLD\",\"summary\":\"Hold\",\"rationale\":\"r\",\"risks\":\"x\"}"}],
                   "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
            )
        )
        val r = AnthropicLlm("sk-test", server.url("/").toString().trimEnd('/'))
            .send(LlmRequest(ClaudeModel.SONNET, Prompts.SYSTEM, "hi", 1000, "medium", Prompts.RECOMMENDATION_SCHEMA))
        assertEquals("Hold", Recommender.parse(r.text).summary)
        assertTrue(server.takeRequest().body.readUtf8().contains("json_schema"))
        server.shutdown()
    }

    @Test fun yahooClientWorksOnAndroid() = runBlocking {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/cookie") -> MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A3=x; Path=/")
                    request.path!!.startsWith("/v1/test/getcrumb") -> MockResponse().setBody("abc")
                    else -> MockResponse().setBody("""{"quoteResponse":{"result":[{"symbol":"F","regularMarketPrice":12.5}]}}""")
                }
            }
            start()
        }
        val base = server.url("/").toString().trimEnd('/')
        val q = YahooMarketData(queryBase = base, cookieUrl = "$base/cookie", rssBase = base).quotes(listOf("F"))
        assertEquals(12.5, q["F"]!!.price, 0.0)
        server.shutdown()
    }

    @Test fun apiKeyRoundTripsThroughKeystore() {
        val store = SettingsStore(app)
        store.setApiKey("sk-ant-test-123")
        assertEquals("sk-ant-test-123", store.apiKey())
        assertTrue(store.state.value.hasApiKey)
        store.setApiKey(null)
        assertEquals(null, store.apiKey())
    }

    @Test fun backupRestoresTheLedgerExactly() = runBlocking {
        val repo = app.repo
        repo.addFill(Fill(0, LocalDate.of(2026, 10, 6), Instrument.stock("F"), Side.BUY, 300.0, 12.08))
        repo.addFill(Fill(0, LocalDate.of(2026, 10, 6), Instrument.call("F", 13.5, LocalDate.of(2026, 10, 30)), Side.SELL, 3.0, 0.14))
        val before = repo.ledger()
        val json = repo.exportJson()
        repo.importJson(json)
        val after = repo.ledger()
        assertEquals(before.cash, after.cash, 1e-9)
        assertEquals(before.positions.map { it.instrument to it.quantity }, after.positions.map { it.instrument to it.quantity })
    }
}
