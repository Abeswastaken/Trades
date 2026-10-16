package com.abeswastaken.trades.alerts

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.abeswastaken.trades.App
import com.abeswastaken.trades.core.engine.CheckKind
import com.abeswastaken.trades.core.market.MarketClock
import com.abeswastaken.trades.core.market.MarketSession
import com.abeswastaken.trades.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/** Runs one check in the foreground (so Android lets it finish), posts what's new, sets the next alarm. */
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kindName = intent?.getStringExtra(AlertScheduler.EXTRA_KIND)
        val kind = runCatching { CheckKind.valueOf(kindName!!) }.getOrDefault(CheckKind.INTRADAY)
        try {
            ServiceCompat.startForeground(
                this, Notifier.CHECKING_ID, Notifier.checking(this, kind),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            AppLog.e("startForeground refused", e)
        }
        AlertRunner.runDetached(applicationContext, kindName, budgetMs = 150_000, scope = scope) { stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Shared by the service and the receiver fallback. */
object AlertRunner {
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun runDetached(ctx: android.content.Context, kindName: String?, budgetMs: Long, scope: CoroutineScope = fallbackScope, done: () -> Unit) {
        scope.launch {
            try {
                withTimeoutOrNull(budgetMs) { run(ctx, runCatching { CheckKind.valueOf(kindName!!) }.getOrDefault(CheckKind.INTRADAY)) }
                    ?: AppLog.i("Alert check ran out of time")
            } catch (e: Exception) {
                AppLog.e("Alert check failed", e)
            } finally {
                runCatching { AlertScheduler.schedule(ctx) }.onFailure { AppLog.e("Rescheduling alerts failed", it) }
                done()
            }
        }
    }

    /** Runs a check and notifies. Scheduled checks only run while the market is open. */
    suspend fun run(ctx: android.content.Context, kind: CheckKind) {
        val repo = (ctx.applicationContext as App).repo
        val rules = repo.settings.state.value.alerts
        if (kind != CheckKind.MANUAL) {
            if (!rules.enabled) return
            if (MarketClock.session(ZonedDateTime.now()).phase != MarketSession.Phase.OPEN) {
                AppLog.i("${kind.label} skipped: market not open")
                return
            }
        }
        val fresh = repo.runAlertCheck(kind)
        // Oldest first, so the most important lands on top of the shade.
        fresh.filter { rules.buzzes(it.toEvent()) }.reversed().forEach { Notifier.post(ctx, it) }
    }

    private fun com.abeswastaken.trades.data.InboxItem.toEvent() = com.abeswastaken.trades.core.engine.AlertEvent(
        key, runCatching { com.abeswastaken.trades.core.engine.AlertKind.valueOf(kind) }.getOrDefault(com.abeswastaken.trades.core.engine.AlertKind.MOVE),
        level, title, body, ticker,
    )
}
