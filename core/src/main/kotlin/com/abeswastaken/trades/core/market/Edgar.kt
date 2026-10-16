package com.abeswastaken.trades.core.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.Locale

/** One SEC filing, newest first in [EdgarSummary.filings]. */
data class Filing(val form: String, val date: LocalDate, val description: String, val url: String)

/** An open-market insider trade from a Form 4 (code P = bought, S = sold). */
data class InsiderTrade(val owner: String, val role: String, val code: String, val shares: Double, val price: Double?, val date: LocalDate) {
    val buy get() = code == "P"
    val value get() = price?.let { it * shares }
}

/** What the SEC record shows on a ticker lately: insider buys and sells, and offering (dilution) filings. */
data class EdgarSummary(val ticker: String, val filings: List<Filing>, val insiders: List<InsiderTrade>) {
    val buys get() = insiders.filter { it.buy }
    val sells get() = insiders.filter { it.code == "S" }
    /** S-1, S-3 and 424B prospectuses: the company is selling (or registering) new shares. */
    val offerings get() = filings.filter { Edgar.isOffering(it.form) }
    val eightKs get() = filings.filter { it.form == "8-K" }

    /** Plain lines for prompts and the hype check; empty when nothing stands out. */
    fun lines(): List<String> = buildList {
        if (buys.isNotEmpty()) {
            val v = buys.sumOf { it.value ?: 0.0 }
            add("Insider buying: ${buys.size} open-market buy${if (buys.size > 1) "s" else ""}" +
                (if (v > 0) String.format(Locale.US, " (\$%,.0f)", v) else "") +
                " by ${buys.map { it.owner }.distinct().take(2).joinToString(", ")}, latest ${buys.maxOf { it.date }}")
        }
        if (sells.isNotEmpty()) add("Insider selling: ${sells.size} sale${if (sells.size > 1) "s" else ""}, latest ${sells.maxOf { it.date }}")
        offerings.firstOrNull()?.let { add("Offering filed (${it.form}) on ${it.date}: possible dilution") }
        eightKs.firstOrNull()?.let { add("8-K filed ${it.date}${if (it.description.isNotBlank()) ": ${it.description}" else ""}") }
    }
}

/**
 * SEC EDGAR, free and keyless. The SEC asks for a User-Agent naming the app and a contact, and at most
 * 10 requests a second; this makes a handful per ticker.
 */
class Edgar(
    baseClient: OkHttpClient = OkHttpClient(),
    // The SEC requires "Name contact-email"; this alias is the one Jake set up for it (2026-10-16).
    private val userAgent: String = "ColvinTrades sec-oct2026.cascade969@simplelogin.com",
    private val www: String = "https://www.sec.gov",
    private val data: String = "https://data.sec.gov",
) {
    private val client = baseClient.newBuilder()
        .addInterceptor { it.proceed(it.request().newBuilder().header("User-Agent", userAgent).build()) }
        .build()
    private val lock = Mutex()
    @Volatile private var ciks: Map<String, Long>? = null

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw MarketDataException("SEC returned ${r.code} for $url")
            r.body?.string() ?: throw MarketDataException("SEC sent nothing")
        }
    }

    suspend fun cik(ticker: String): Long? = lock.withLock {
        val map = ciks ?: parseTickers(get("$www/files/company_tickers.json")).also { ciks = it }
        map[ticker.uppercase().replace('.', '-')]
    }

    /** Filings and Form 4 trades from the last [days] days. Reads at most [maxForm4] Form 4s. */
    suspend fun summary(ticker: String, today: LocalDate, days: Long = 14, maxForm4: Int = 6): EdgarSummary? {
        val cik = cik(ticker) ?: return null
        val recent = parseSubmissions(cik, YahooMarketData.json.parseToJsonElement(get("$data/submissions/CIK${cik.toString().padStart(10, '0')}.json")).jsonObject)
            .filter { !it.date.isBefore(today.minusDays(days)) }
        val form4s = recent.filter { it.form == "4" }.take(maxForm4)
        val trades = form4s.flatMap { f ->
            runCatching { parseForm4(get(f.url), f.date) }.getOrDefault(emptyList())
        }
        return EdgarSummary(ticker.uppercase(), recent.filter { it.form != "4" }, trades.filter { it.code == "P" || it.code == "S" })
    }

    companion object {
        fun isOffering(form: String) = form in setOf("S-1", "S-1/A", "S-3", "S-3/A", "S-3ASR", "F-1", "F-3") || form.startsWith("424B")

        internal fun parseTickers(json: String): Map<String, Long> =
            YahooMarketData.json.parseToJsonElement(json).jsonObject.values.mapNotNull { v ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val t = o.str("ticker") ?: return@mapNotNull null
                val c = o.long("cik_str") ?: return@mapNotNull null
                t.uppercase() to c
            }.toMap()

        /** The "recent" block lists filings as parallel arrays. */
        internal fun parseSubmissions(cik: Long, root: JsonObject): List<Filing> {
            val r = root.obj("filings")?.obj("recent") ?: return emptyList()
            fun col(k: String) = r.arr(k).orEmpty().map { (it as? JsonPrimitive)?.content.orEmpty() }
            val acc = col("accessionNumber"); val dates = col("filingDate"); val forms = col("form")
            val docs = col("primaryDocument"); val desc = col("primaryDocDescription")
            return acc.indices.mapNotNull { i ->
                val d = runCatching { LocalDate.parse(dates.getOrNull(i)) }.getOrNull() ?: return@mapNotNull null
                // Form 4's primaryDocument points at the styled view (xslF345X05/...); the raw XML sits beside it.
                val doc = docs.getOrNull(i).orEmpty().substringAfterLast('/')
                val url = "https://www.sec.gov/Archives/edgar/data/$cik/${acc[i].replace("-", "")}/$doc"
                Filing(forms.getOrNull(i).orEmpty(), d, desc.getOrNull(i).orEmpty(), url)
            }
        }

        private fun tag(xml: String, name: String) = Regex("<$name>\\s*(?:<value>)?\\s*([^<]*?)\\s*(?:</value>)?\\s*</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

        internal fun parseForm4(xml: String, filed: LocalDate): List<InsiderTrade> {
            val owner = tag(xml, "rptOwnerName")?.let(::unescape) ?: "Insider"
            val role = tag(xml, "officerTitle")?.let(::unescape)
                ?: if (tag(xml, "isDirector") in setOf("1", "true")) "Director" else ""
            return Regex("<nonDerivativeTransaction>(.*?)</nonDerivativeTransaction>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val t = m.groupValues[1]
                val code = tag(t, "transactionCode") ?: return@mapNotNull null
                val shares = tag(t, "transactionShares")?.toDoubleOrNull() ?: return@mapNotNull null
                val date = tag(t, "transactionDate")?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: filed
                InsiderTrade(owner, role, code, shares, tag(t, "transactionPricePerShare")?.toDoubleOrNull(), date)
            }.toList()
        }

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    }
}
