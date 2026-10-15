package com.abeswastaken.trades.core.engine

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

data class LlmRequest(
    val model: ClaudeModel,
    val system: String,
    val user: String,
    val maxTokens: Long,
    val effort: String,
    /** When set, the reply is constrained to this JSON schema. */
    val jsonSchema: Map<String, Any>? = null,
)

data class LlmResponse(
    val text: String,
    val usage: Usage,
    /** The model that actually answered (differs from the request after a refusal fallback). */
    val servedModel: String,
    val stopReason: String?,
)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Seam for tests: the recommender only needs this. */
fun interface LlmClient {
    suspend fun send(req: LlmRequest): LlmResponse
}

/** Calls the Claude API through the official Java SDK. */
class AnthropicLlm(apiKey: String) : LlmClient {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    override suspend fun send(req: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val output = OutputConfig.builder().effort(OutputConfig.Effort.of(req.effort))
        req.jsonSchema?.let { schema ->
            output.format(
                JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema.mapValues { JsonValue.from(it.value) }).build())
                    .build()
            )
        }
        val params = MessageCreateParams.builder()
            .model(req.model.id)
            .maxTokens(req.maxTokens)
            .systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(req.system).cacheControl(CacheControlEphemeral.builder().build()).build())
            )
            .addUserMessage(req.user)
            .outputConfig(output.build())
            .apply {
                if (req.model.supportsFallback) {
                    // Server-side refusal fallback: if a safety classifier declines, the API retries on a
                    // suitable model instead of returning an empty refusal.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val msg = try {
            client.messages().create(params)
        } catch (e: UnauthorizedException) {
            throw LlmException("The API key was rejected. Check it in Settings.", e)
        } catch (e: RateLimitException) {
            throw LlmException("Rate limited by the Claude API. Try again in a minute.", e)
        } catch (e: AnthropicServiceException) {
            throw LlmException("Claude API error ${e.statusCode()}: ${e.message}", e)
        } catch (e: Exception) {
            throw LlmException("Couldn't reach the Claude API: ${e.message}", e)
        }

        val stop = msg.stopReason().map { it.asString() }.orElse(null)
        if (stop == "refusal") throw LlmException("Claude declined this request.")
        val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("")
        val u = msg.usage()
        LlmResponse(
            text = text,
            usage = Usage(
                inputTokens = u.inputTokens(),
                outputTokens = u.outputTokens(),
                cacheReadTokens = u.cacheReadInputTokens().orElse(0L),
                cacheWriteTokens = u.cacheCreationInputTokens().orElse(0L),
            ),
            servedModel = msg.model().asString(),
            stopReason = stop,
        )
    }
}
