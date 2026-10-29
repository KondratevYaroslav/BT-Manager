package ru.yar.btmacros

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.yar.btmacros.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    private const val CH_MONITOR = "monitor"
    private const val CH_EVENTS = "events"
    const val ID_MONITOR = 1
    private val nextId = AtomicInteger(100)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Фоновый мониторинг", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Уведомления макросов", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTIONS, "Действия, требующие нажатия", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private const val CH_ACTIONS = "actions"

    /** Уведомление-кнопка: когда Android не разрешает открыть экран из фона. */
    @SuppressLint("MissingPermission")
    fun actionPrompt(ctx: Context, title: String, text: String, pi: PendingIntent) {
        val n = NotificationCompat.Builder(ctx, CH_ACTIONS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(nextId.incrementAndGet(), n)
        } catch (_: SecurityException) {
        }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitor(ctx: Context): Notification = NotificationCompat.Builder(ctx, CH_MONITOR)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("BT Макросы")
        .setContentText("Слежу за Bluetooth-устройствами")
        .setOngoing(true)
        .setContentIntent(openApp(ctx))
        .build()

    @SuppressLint("MissingPermission")
    fun event(ctx: Context, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BT Макросы")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(nextId.incrementAndGet(), n)
        } catch (_: SecurityException) {
        }
    }
}
