package com.abeswastaken.trades.core

import com.abeswastaken.trades.core.engine.AnthropicLlm
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.LlmException
import com.abeswastaken.trades.core.engine.LlmRequest
import com.abeswastaken.trades.core.engine.Prompts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Drives the real Anthropic SDK against a local server to check the exact request we send. */
class ClaudeClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun reply(text: String, stop: String = "end_turn", model: String = "claude-sonnet-5-5") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"$model",
               "content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}],
               "stop_reason":"$stop","stop_sequence":null,
               "usage":{"input_tokens":1200,"output_tokens":300,"cache_read_input_tokens":800,"cache_creation_input_tokens":0}}"""
        )

    private fun llm() = AnthropicLlm("sk-test", server.url("/").toString().trimEnd('/'))

    @Test fun `recommendation request carries schema, effort, caching and the refusal fallback`() = runBlocking {
        server.enqueue(reply("""{"action":"HOLD"}"""))
        val r = llm().send(
            LlmRequest(ClaudeModel.SONNET, Prompts.SYSTEM, "hello", 16000, "medium", Prompts.RECOMMENDATION_SCHEMA)
        )
        assertEquals("""{"action":"HOLD"}""", r.text)
        assertEquals(1200L, r.usage.inputTokens)
        assertEquals(800L, r.usage.cacheReadTokens)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertTrue(req.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        val out = body["output_config"]!!.jsonObject
        assertEquals("medium", out["effort"]!!.jsonPrimitive.content)
        val format = out["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertTrue(format["schema"]!!.toString().contains("SELL_CALL"))
        val sys = body["system"]!!.jsonArray[0].jsonObject
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("thinking must not be disabled", body["thinking"] == null)
    }

    @Test fun `haiku calls skip the fallback and the schema`() = runBlocking {
        server.enqueue(reply("digest", model = "claude-haiku-5-5"))
        llm().send(LlmRequest(ClaudeModel.HAIKU, "sys", "news", 4000, "low"))
        val req = server.takeRequest()
        assertTrue(req.getHeader("anthropic-beta")?.contains("server-side-fallback") != true)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertTrue(body["fallbacks"] == null)
        assertTrue(body["output_config"]!!.jsonObject["format"] == null)
    }

    @Test fun `bad key and refusal become readable errors`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val e = runCatching { llm().send(LlmRequest(ClaudeModel.HAIKU, "s", "u", 100, "low")) }.exceptionOrNull()
        assertTrue(e is LlmException && e.message!!.contains("API key"))

        server.enqueue(reply("", stop = "refusal"))
        val e2 = runCatching { llm().send(LlmRequest(ClaudeModel.HAIKU, "s", "u", 100, "low")) }.exceptionOrNull()
        assertTrue(e2 is LlmException && e2.message!!.contains("declined"))
    }
}

class ClaudeClientDowngradeTest {
    @Test fun `a 400 about the fallback or schema retries without it`() = runBlocking {
        val server = MockWebServer().apply { start() }
        fun err(msg: String) = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"$msg"}}""")
        server.enqueue(err("fallbacks: Extra inputs are not permitted"))
        server.enqueue(err("output_config.format.schema: unsupported keyword"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"id":"m","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"{}"}],
               "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""))
        val notes = mutableListOf<String>()
        val llm = AnthropicLlm("k", server.url("/").toString().trimEnd('/')) { notes += it }
        llm.send(LlmRequest(ClaudeModel.SONNET, "s", "u", 100, "medium", Prompts.RECOMMENDATION_SCHEMA))
        server.takeRequest(); server.takeRequest()
        val last = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(last["fallbacks"] == null)
        assertTrue(last["output_config"]!!.jsonObject["format"] == null)
        assertTrue(last["messages"].toString().contains("Reply with only a JSON object"))
        assertEquals(2, notes.size)
        server.shutdown()
    }
}
