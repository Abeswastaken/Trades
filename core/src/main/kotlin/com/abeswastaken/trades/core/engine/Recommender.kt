package com.abeswastaken.trades.core.engine

import com.abeswastaken.trades.core.market.Headline
import kotlinx.serialization.json.Json

/** One billed call, for the spend log. */
data class SpendItem(val purpose: String, val model: ClaudeModel, val servedModel: String, val usage: Usage) {
    val cost get() = Pricing.cost(ClaudeModel.byId(servedModel) ?: model, usage)
}

data class RecResult(
    val recommendation: Recommendation,
    val rawJson: String,
    val digest: String?,
    val spend: List<SpendItem>,
)

class Recommender(private val llm: LlmClient) {

    /** Cheap pre-digestion of headlines with Haiku, so the main call gets signal instead of noise. */
    suspend fun digest(headlines: Map<String, List<Headline>>): Pair<String, SpendItem>? {
        val body = Prompts.digestRequest(headlines)
        if (body.isBlank()) return null
        val r = llm.send(
            LlmRequest(ClaudeModel.HAIKU, Prompts.DIGEST_SYSTEM, body, maxTokens = 4000, effort = "low")
        )
        return r.text.trim() to SpendItem("news digest", ClaudeModel.HAIKU, r.servedModel, r.usage)
    }

    suspend fun recommend(
        model: ClaudeModel,
        context: RecContext,
        headlines: Map<String, List<Headline>>,
        useDigest: Boolean,
    ): RecResult {
        val spend = mutableListOf<SpendItem>()
        val digest = if (useDigest) {
            runCatching { digest(headlines) }.getOrNull()?.also { spend += it.second }?.first
        } else {
            // Without the digest, pass raw headlines (capped) so the news still counts.
            headlines.entries.joinToString("\n") { (s, l) -> "$s: " + l.take(3).joinToString(" | ") { it.title } }
                .ifBlank { null }
        }
        val r = llm.send(
            LlmRequest(
                model = model,
                system = Prompts.SYSTEM,
                user = Prompts.recommendRequest(context.copy(newsDigest = digest)),
                maxTokens = 16000,
                effort = if (model == ClaudeModel.OPUS) "high" else "medium",
                jsonSchema = Prompts.RECOMMENDATION_SCHEMA,
            )
        )
        spend += SpendItem("recommendation", model, r.servedModel, r.usage)
        if (r.stopReason == "max_tokens") throw LlmException("The reply was cut off. Try again.")
        val rec = parse(r.text)
        return RecResult(rec, r.text, digest, spend)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        fun parse(text: String): Recommendation {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) throw LlmException("Claude's reply had no recommendation in it.")
            return try {
                json.decodeFromString(Recommendation.serializer(), text.substring(start, end + 1))
            } catch (e: Exception) {
                throw LlmException("Couldn't read Claude's recommendation: ${e.message}", e)
            }
        }

        fun encode(rec: Recommendation): String = json.encodeToString(Recommendation.serializer(), rec)
    }
}
