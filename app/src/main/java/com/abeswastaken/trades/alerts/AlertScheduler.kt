package com.abeswastaken.trades.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.abeswastaken.trades.App
import com.abeswastaken.trades.core.engine.AlertSchedule
import com.abeswastaken.trades.util.AppLog
import java.time.ZonedDateTime

/** Keeps exactly one alarm set: the next check (9:31 ET, every interval, or 30 minutes before the close). */
object AlertScheduler {
    const val ACTION_CHECK = "com.abeswastaken.trades.ALERT_CHECK"
    const val EXTRA_KIND = "kind"

    private fun pending(ctx: Context, kind: String?): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7,
        Intent(ctx, AlertReceiver::class.java).setAction(ACTION_CHECK).apply { kind?.let { putExtra(EXTRA_KIND, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext as App
        val repo = app.repo
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = AlertSchedule.next(ZonedDateTime.now(), repo.settings.state.value.alerts)
        if (next == null) {
            am.cancel(pending(ctx, null))
            repo.alertStore.setStatus { copy(nextAt = null, nextCheck = null) }
            return
        }
        val at = next.at.toInstant().toEpochMilli()
        val pi = pending(ctx, next.kind.name)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            AppLog.e("Exact alarm refused, using an inexact one", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        repo.alertStore.setStatus { copy(nextAt = at, nextCheck = next.kind.label) }
    }
}
