package com.abeswastaken.trades

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.TradesApp
import com.abeswastaken.trades.ui.theme.TradesTheme
import com.abeswastaken.trades.util.AppLog

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TradesTheme { TradesApp(vm) } }
        if (savedInstanceState == null) { handleShare(intent); handleOpen(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        handleOpen(intent)
    }

    /** A tapped alert opens the inbox. */
    private fun handleOpen(intent: Intent?) {
        intent?.getStringExtra(com.abeswastaken.trades.alerts.Notifier.EXTRA_OPEN)?.let { vm.openTab.value = it }
    }

    /** A Schwab CSV shared or opened from another app goes straight to the import preview on the Ledger tab. */
    private fun handleShare(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .onFailure { AppLog.e("Shared file unreadable", it) }.getOrNull()
        if (text == null) { vm.toast.value = "Couldn't open that file"; return }
        vm.openTab.value = "ledger"
        vm.previewImport(text)
    }
}
