package com.abeswastaken.trades.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.abeswastaken.trades.core.engine.ClaudeModel
import com.abeswastaken.trades.core.scanner.ScanRules
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
)

/** Plain settings in SharedPreferences; the Anthropic key is encrypted with an Android Keystore key. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private fun read(): AppSettings {
        val d = ScanRules()
        return AppSettings(
            startingCash = prefs.getFloat("startingCash", 0f).toDouble(),
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
        )
    }

    fun update(block: AppSettings.() -> AppSettings) {
        val s = _state.value.block()
        prefs.edit()
            .putFloat("startingCash", s.startingCash.toFloat())
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

    companion object { private const val ALIAS = "trades_api_key" }
}
