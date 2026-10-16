package com.abeswastaken.trades.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.abeswastaken.trades.util.AppLog

/** The alarm firing, or the phone/app restarting (which clears alarms, so they're set again). */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AlertScheduler.ACTION_CHECK -> {
                val kind = intent.getStringExtra(AlertScheduler.EXTRA_KIND)
                val service = Intent(ctx, AlertService::class.java).putExtra(AlertScheduler.EXTRA_KIND, kind)
                try {
                    ContextCompat.startForegroundService(ctx, service)
                } catch (e: Exception) {
                    // Android can refuse a background start (e.g. an inexact alarm); run the check here instead.
                    AppLog.e("Couldn't start the check service; running in the receiver", e)
                    val pending = goAsync()
                    AlertRunner.runDetached(ctx.applicationContext, kind, budgetMs = 50_000) { pending.finish() }
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> AlertScheduler.schedule(ctx)
        }
    }
}
