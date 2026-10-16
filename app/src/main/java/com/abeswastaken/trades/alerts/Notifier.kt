package com.abeswastaken.trades.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.abeswastaken.trades.MainActivity
import com.abeswastaken.trades.R
import com.abeswastaken.trades.core.engine.AlertLevel
import com.abeswastaken.trades.core.engine.CheckKind
import com.abeswastaken.trades.data.InboxItem
import com.abeswastaken.trades.util.AppLog

/** Notification channels and posting. Tapping any alert opens the inbox. */
object Notifier {
    const val CRITICAL = "alerts_critical"
    const val UPDATES = "alerts_updates"
    const val CHECKS = "alert_checks"
    const val EXTRA_OPEN = "open"
    const val CHECKING_ID = 1

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CRITICAL, "Money at risk", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Short options in the money or expiring, holdings falling hard, early assignment" },
            NotificationChannel(UPDATES, "Heads-ups and opportunities", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "The open brief, moves, earnings, unusual activity, insider buys, long shots" },
            NotificationChannel(CHECKS, "Background checks", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Shown briefly while a check runs"; setShowBadge(false) },
        ))
    }

    private fun openInbox(ctx: Context, request: Int): PendingIntent = PendingIntent.getActivity(
        ctx, request,
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, "inbox")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun enabled(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** Posts one alert. Critical ones go to the loud channel. */
    fun post(ctx: Context, item: InboxItem) {
        if (!enabled(ctx)) { AppLog.i("Notifications are off; '${item.title}' is in the inbox only"); return }
        val n = NotificationCompat.Builder(ctx, if (item.level == AlertLevel.CRITICAL) CRITICAL else UPDATES)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(item.title)
            .setContentText(item.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setSubText(item.level.label)
            .setWhen(item.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (item.level == AlertLevel.CRITICAL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openInbox(ctx, item.id.toInt()))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1000 + (item.id % 100_000).toInt(), n)
        } catch (e: SecurityException) {
            AppLog.e("Posting a notification was refused", e)
        }
    }

    /** The quiet notification a foreground check has to show while it runs. */
    fun checking(ctx: Context, kind: CheckKind): Notification =
        NotificationCompat.Builder(ctx, CHECKS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("${kind.label}…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openInbox(ctx, 0))
            .build()
}
