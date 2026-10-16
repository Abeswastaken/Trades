package com.abeswastaken.trades.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.abeswastaken.trades.core.engine.AlertRules
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.engine.RiskLevel
import com.abeswastaken.trades.core.market.NewsSourceId
import com.abeswastaken.trades.core.scanner.ScanRules
import com.abeswastaken.trades.core.scanner.ScreenerRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val startingCash: Double = 0.0,
    val defaultModel: ClaudeModel = ClaudeModel.SONNET,
    val useNewsDigest: Boolean = true,
    val hasApiKey: Boolean = false,
    val finnhubKey: String = "",
    val rules: ScanRules = ScanRules(),
    val newsSources: Set<NewsSourceId> = NewsSourceId.defaults,
    val alphaVantageKey: String = "",
    val marketauxKey: String = "",
    val screener: ScreenerRules = ScreenerRules(),
    val risk: RiskLevel = RiskLevel.NORMAL,
    /** Margin account: negative cash is a loan and spending room is buying power, not cash. */
    val margin: Boolean = false,
    /** Settings → Alerts: background checks and notifications. */
    val alerts: AlertRules = AlertRules(),
) {
    /** Keys for the news sources that need one (Finnhub's doubles as the quote backup). */
    val newsKeys: Map<NewsSourceId, String> get() = mapOf(
        NewsSourceId.FINNHUB to finnhubKey,
        NewsSourceId.ALPHA_VANTAGE to alphaVantageKey,
        NewsSourceId.MARKETAUX to marketauxKey,
    )
}

/** Plain settings in SharedPreferences; the Anthropic key is encrypted with an Android Keystore key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private fun read(): AppSettings {
        val d = ScanRules()
        val sd = ScreenerRules()
        return AppSettings(
            // Stored as text so cents survive; older installs kept a float.
            startingCash = prefs.getString("startingCashText", null)?.toDoubleOrNull()
                ?: Math.round(prefs.getFloat("startingCash", 0f) * 100.0) / 100.0,
            defaultModel = ClaudeModel.byName(prefs.getString("defaultModel", null)),
            useNewsDigest = prefs.getBoolean("useNewsDigest", true),
            hasApiKey = prefs.contains("apiKey"),
            finnhubKey = prefs.getString("finnhubKey", "") ?: "",
            rules = ScanRules(
                minPrice = prefs.getFloat("minPrice", d.minPrice.toFloat()).toDouble(),
                maxPrice = prefs.getFloat("maxPrice", d.maxPrice.toFloat()).toDouble(),
                minAvgVolume = prefs.getLong("minAvgVolume", d.minAvgVolume),
                minDte = prefs.getInt("minDte", d.minDte),
                maxDte = prefs.getInt("maxDte", d.maxDte),
                minOtmPercent = prefs.getFloat("minOtmPercent", d.minOtmPercent.toFloat()).toDouble(),
                minOpenInterest = prefs.getLong("minOpenInterest", d.minOpenInterest),
                minBid = prefs.getFloat("minBid", d.minBid.toFloat()).toDouble(),
                minPremiumPercent = prefs.getFloat("minPremiumPercent", d.minPremiumPercent.toFloat()).toDouble(),
                maxCandidates = prefs.getInt("maxCandidates", d.maxCandidates),
            ),
            newsSources = prefs.getStringSet("newsSources", null)?.let { NewsSourceId.parse(it) } ?: NewsSourceId.defaults,
            alphaVantageKey = prefs.getString("alphaVantageKey", "") ?: "",
            marketauxKey = prefs.getString("marketauxKey", "") ?: "",
            screener = ScreenerRules(
                minPrice = prefs.getFloat("scrMinPrice", sd.minPrice.toFloat()).toDouble(),
                maxPrice = prefs.getFloat("scrMaxPrice", sd.maxPrice.toFloat()).toDouble(),
                minAvgVolume = prefs.getLong("scrMinAvgVolume", sd.minAvgVolume),
                weekliesOnly = prefs.getBoolean("scrWeekliesOnly", sd.weekliesOnly),
                maxDte = prefs.getInt("scrMaxDte", sd.maxDte),
                minOtmPercent = prefs.getFloat("scrMinOtm", sd.minOtmPercent.toFloat()).toDouble(),
            ),
            risk = RiskLevel.byName(prefs.getString("risk", null)),
            margin = prefs.getBoolean("margin", false),
            alerts = readAlerts(),
        )
    }

    private fun readAlerts(): AlertRules {
        val d = AlertRules()
        return AlertRules(
            enabled = prefs.getBoolean("al_enabled", d.enabled),
            openCheck = prefs.getBoolean("al_open", d.openCheck),
            openModel = ClaudeModel.byName(prefs.getString("al_openModel", d.openModel.name)),
            closeCheck = prefs.getBoolean("al_close", d.closeCheck),
            intraday = prefs.getBoolean("al_intraday", d.intraday),
            intervalMin = prefs.getInt("al_interval", d.intervalMin),
            quiet = prefs.getBoolean("al_quiet", d.quiet),
            moves = prefs.getBoolean("al_moves", d.moves),
            movePercent = prefs.getFloat("al_movePct", d.movePercent.toFloat()).toDouble(),
            shortCalls = prefs.getBoolean("al_short", d.shortCalls),
            nearPercent = prefs.getFloat("al_nearPct", d.nearPercent.toFloat()).toDouble(),
            exDividend = prefs.getBoolean("al_exdiv", d.exDividend),
            earnings = prefs.getBoolean("al_earnings", d.earnings),
            longShots = prefs.getBoolean("al_longshots", d.longShots),
            unusual = prefs.getBoolean("al_unusual", d.unusual),
            volumeMultiple = prefs.getFloat("al_volMult", d.volumeMultiple.toFloat()).toDouble(),
            insider = prefs.getBoolean("al_insider", d.insider),
        )
    }

    private fun android.content.SharedPreferences.Editor.putAlerts(a: AlertRules) = this
        .putBoolean("al_enabled", a.enabled)
        .putBoolean("al_open", a.openCheck)
        .putString("al_openModel", a.openModel.name)
        .putBoolean("al_close", a.closeCheck)
        .putBoolean("al_intraday", a.intraday)
        .putInt("al_interval", a.intervalMin)
        .putBoolean("al_quiet", a.quiet)
        .putBoolean("al_moves", a.moves)
        .putFloat("al_movePct", a.movePercent.toFloat())
        .putBoolean("al_short", a.shortCalls)
        .putFloat("al_nearPct", a.nearPercent.toFloat())
        .putBoolean("al_exdiv", a.exDividend)
        .putBoolean("al_earnings", a.earnings)
        .putBoolean("al_longshots", a.longShots)
        .putBoolean("al_unusual", a.unusual)
        .putFloat("al_volMult", a.volumeMultiple.toFloat())
        .putBoolean("al_insider", a.insider)

    fun update(block: AppSettings.() -> AppSettings) {
        val s = _state.value.block()
        prefs.edit()
            .putString("startingCashText", s.startingCash.toBigDecimal().toPlainString())
            .putString("risk", s.risk.name)
            .putBoolean("margin", s.margin)
            .putString("defaultModel", s.defaultModel.name)
            .putBoolean("useNewsDigest", s.useNewsDigest)
            .putString("finnhubKey", s.finnhubKey.trim())
            .putFloat("minPrice", s.rules.minPrice.toFloat())
            .putFloat("maxPrice", s.rules.maxPrice.toFloat())
            .putLong("minAvgVolume", s.rules.minAvgVolume)
            .putInt("minDte", s.rules.minDte)
            .putInt("maxDte", s.rules.maxDte)
            .putFloat("minOtmPercent", s.rules.minOtmPercent.toFloat())
            .putLong("minOpenInterest", s.rules.minOpenInterest)
            .putFloat("minBid", s.rules.minBid.toFloat())
            .putFloat("minPremiumPercent", s.rules.minPremiumPercent.toFloat())
            .putInt("maxCandidates", s.rules.maxCandidates)
            .putStringSet("newsSources", s.newsSources.map { it.name }.toSet())
            .putString("alphaVantageKey", s.alphaVantageKey.trim())
            .putString("marketauxKey", s.marketauxKey.trim())
            .putFloat("scrMinPrice", s.screener.minPrice.toFloat())
            .putFloat("scrMaxPrice", s.screener.maxPrice.toFloat())
            .putLong("scrMinAvgVolume", s.screener.minAvgVolume)
            .putBoolean("scrWeekliesOnly", s.screener.weekliesOnly)
            .putInt("scrMaxDte", s.screener.maxDte)
            .putFloat("scrMinOtm", s.screener.minOtmPercent.toFloat())
            .putAlerts(s.alerts)
            .apply()
        _state.value = read()
    }

    fun setApiKey(key: String?) {
        if (key.isNullOrBlank()) prefs.edit().remove("apiKey").apply()
        else prefs.edit().putString("apiKey", encrypt(key.trim())).apply()
        _state.value = read()
    }

    fun apiKey(): String? = prefs.getString("apiKey", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }

    companion object {
        private const val ALIAS = "trades_api_key"

        fun alertsToJson(a: AlertRules): org.json.JSONObject = org.json.JSONObject()
            .put("enabled", a.enabled).put("openCheck", a.openCheck).put("openModel", a.openModel.name)
            .put("closeCheck", a.closeCheck).put("intraday", a.intraday).put("intervalMin", a.intervalMin)
            .put("quiet", a.quiet).put("moves", a.moves).put("movePercent", a.movePercent)
            .put("shortCalls", a.shortCalls).put("nearPercent", a.nearPercent).put("exDividend", a.exDividend)
            .put("earnings", a.earnings).put("longShots", a.longShots).put("unusual", a.unusual)
            .put("volumeMultiple", a.volumeMultiple).put("insider", a.insider)

        fun alertsFromJson(o: org.json.JSONObject, d: AlertRules): AlertRules = AlertRules(
            enabled = o.optBoolean("enabled", d.enabled), openCheck = o.optBoolean("openCheck", d.openCheck),
            openModel = ClaudeModel.byName(o.optString("openModel", d.openModel.name)),
            closeCheck = o.optBoolean("closeCheck", d.closeCheck), intraday = o.optBoolean("intraday", d.intraday),
            intervalMin = o.optInt("intervalMin", d.intervalMin), quiet = o.optBoolean("quiet", d.quiet),
            moves = o.optBoolean("moves", d.moves), movePercent = o.optDouble("movePercent", d.movePercent),
            shortCalls = o.optBoolean("shortCalls", d.shortCalls), nearPercent = o.optDouble("nearPercent", d.nearPercent),
            exDividend = o.optBoolean("exDividend", d.exDividend), earnings = o.optBoolean("earnings", d.earnings),
            longShots = o.optBoolean("longShots", d.longShots), unusual = o.optBoolean("unusual", d.unusual),
            volumeMultiple = o.optDouble("volumeMultiple", d.volumeMultiple), insider = o.optBoolean("insider", d.insider),
        )
    }
}
