package ru.yar.btmacros.engine

import android.content.Context
import androidx.car.app.connection.CarConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.yar.btmacros.data.EventLog

/**
 * Следит, запущен ли Android Auto (проекция на экран машины).
 * Использует официальный CarConnection из библиотеки Android for Cars.
 */
class AndroidAutoMonitor(private val context: Context) {
    private val prefs = context.getSharedPreferences("aa", Context.MODE_PRIVATE)

    private val _connected = MutableStateFlow(prefs.getBoolean("connected", false))
    val connected: StateFlow<Boolean> = _connected

    var onChange: ((Boolean) -> Unit)? = null
    private var started = false

    /** Вызывать с главного потока. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            CarConnection(context).type.observeForever { type ->
                val now = type == CarConnection.CONNECTION_TYPE_PROJECTION
                if (now != _connected.value) {
                    _connected.value = now
                    prefs.edit().putBoolean("connected", now).apply()
                    EventLog.add(if (now) "Android Auto подключён" else "Android Auto отключён")
                    onChange?.invoke(now)
                }
            }
        }.onFailure { EventLog.add("Не удалось следить за Android Auto: ${it.message}") }
    }
}
