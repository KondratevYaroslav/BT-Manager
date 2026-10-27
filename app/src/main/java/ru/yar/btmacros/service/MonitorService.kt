package ru.yar.btmacros.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.yar.btmacros.App
import ru.yar.btmacros.Notifications
import ru.yar.btmacros.data.EventLog

/**
 * Сервис с постоянным уведомлением. Сам ничего не делает —
 * просто не даёт системе выгрузить приложение, пока идут паузы в макросах.
 */
class MonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_MONITOR, Notifications.monitor(this))
        App.instance.bt.start()
        _running.value = true
        EventLog.add("Фоновый мониторинг запущен")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        _running.value = false
        EventLog.add("Фоновый мониторинг остановлен")
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } catch (e: Exception) {
                EventLog.add("Не удалось запустить мониторинг: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}
