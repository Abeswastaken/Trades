package com.abeswastaken.trades.core.engine

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
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
class AnthropicLlm(
    apiKey: String,
    baseUrl: String? = null,
    private val onDowngrade: (String) -> Unit = {},
) : LlmClient {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { baseUrl?.let { baseUrl(it) } }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    override suspend fun send(req: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        var useFallback = req.model.supportsFallback
        var useSchema = req.jsonSchema != null
        var msg: com.anthropic.models.messages.Message? = null
        // A 400 that names one of the optional features (refusal fallback, structured output) means this
        // account or model doesn't take it yet: drop it and retry, rather than failing every recommendation.
        while (msg == null) {
            try {
                msg = client.messages().create(buildParams(req, useFallback, useSchema))
            } catch (e: BadRequestException) {
                val text = (e.message ?: "").lowercase()
                val dropped = when {
                    useFallback && "fallback" in text -> { useFallback = false; "refusal fallback" }
                    useSchema && ("schema" in text || "output_config" in text || "format" in text) -> { useSchema = false; "structured output" }
                    else -> throw LlmException("Claude API rejected the request: ${e.message}", e)
                }
                onDowngrade("Retrying without $dropped: ${e.message}")
            } catch (e: UnauthorizedException) {
                throw LlmException("The API key was rejected. Check it in Settings.", e)
            } catch (e: PermissionDeniedException) {
                throw LlmException("This API key isn't allowed to use ${req.model.label}. Check your Anthropic plan.", e)
            } catch (e: RateLimitException) {
                throw LlmException("Rate limited by the Claude API. Try again in a minute.", e)
            } catch (e: AnthropicServiceException) {
                throw LlmException("Claude API error ${e.statusCode()}: ${e.message}", e)
            } catch (e: Exception) {
                throw LlmException("Couldn't reach the Claude API: ${e.message}", e)
            }
        }
        val m = msg!!
        val stop = m.stopReason().map { it.asString() }.orElse(null)
        if (stop == "refusal") throw LlmException("Claude declined this request.")
        val text = m.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("")
        val u = m.usage()
        LlmResponse(
            text = text,
            usage = Usage(
                inputTokens = u.inputTokens(),
                outputTokens = u.outputTokens(),
                cacheReadTokens = u.cacheReadInputTokens().orElse(0L),
                cacheWriteTokens = u.cacheCreationInputTokens().orElse(0L),
            ),
            servedModel = m.model().asString(),
            stopReason = stop,
        )
    }

    private fun buildParams(req: LlmRequest, fallback: Boolean, schema: Boolean): MessageCreateParams {
        val output = OutputConfig.builder().effort(OutputConfig.Effort.of(req.effort))
        val user = if (schema || req.jsonSchema == null) req.user
        else req.user + "\n\nReply with only a JSON object with these fields: " +
            (req.jsonSchema["properties"] as Map<*, *>).keys.joinToString(", ") + "."
        if (schema) req.jsonSchema?.let { s ->
            output.format(
                JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(s.mapValues { JsonValue.from(it.value) }).build())
                    .build()
            )
        }
        return MessageCreateParams.builder()
            .model(req.model.id)
            .maxTokens(req.maxTokens)
            .systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(req.system).cacheControl(CacheControlEphemeral.builder().build()).build())
            )
            .addUserMessage(user)
            .outputConfig(output.build())
            .apply {
                if (fallback) {
                    // Server-side refusal fallback: if a safety classifier declines, the API retries on a
                    // suitable model instead of returning an empty refusal.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
    }
}
