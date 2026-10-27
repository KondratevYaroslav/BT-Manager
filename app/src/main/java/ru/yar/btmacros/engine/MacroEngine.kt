package ru.yar.btmacros.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.yar.btmacros.Notifications
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.data.EventLog
import ru.yar.btmacros.data.MacroStore
import ru.yar.btmacros.model.ActionType
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.MacroAction
import ru.yar.btmacros.model.TRIGGER_DEVICE
import ru.yar.btmacros.model.TriggerType
import java.util.concurrent.ConcurrentHashMap

/** Запускает макросы по событиям и выполняет их действия по порядку. */
class MacroEngine(
    private val context: Context,
    private val bt: BtController,
    private val store: MacroStore,
    private val music: MusicPriority,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastStart = ConcurrentHashMap<String, Long>()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running

    fun onEvent(type: TriggerType, address: String?) {
        for (m in store.macros.value) {
            if (!m.enabled || m.trigger.type != type) continue
            val wanted = m.trigger.deviceAddress
            if (type.needsDevice && wanted != null && !wanted.equals(address, ignoreCase = true)) continue
            run(m, address, manual = false)
        }
    }

    fun run(m: Macro, triggerAddress: String?, manual: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!manual) {
            val last = lastStart[m.id]
            if (last != null && now - last < m.cooldownSec * 1000L) {
                EventLog.add("«${m.name}»: пропущен (повтор раньше ${m.cooldownSec} с)")
                return
            }
            val cond = m.conditionAddress
            if (cond != null && !bt.isConnected(cond)) {
                EventLog.add("«${m.name}»: условие не выполнено — ${m.conditionName ?: cond} не подключено")
                return
            }
        }
        lastStart[m.id] = now
        jobs.remove(m.id)?.cancel()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            _running.update { it + m.id }
            EventLog.add("▶ «${m.name}» запущен" + if (manual) " вручную" else "")
            try {
                m.actions.forEachIndexed { i, a -> execute(m, i, a, triggerAddress) }
                EventLog.add("■ «${m.name}» выполнен")
            } catch (e: CancellationException) {
                EventLog.add("✕ «${m.name}» прерван")
                throw e
            } catch (e: Exception) {
                EventLog.add("✕ «${m.name}»: ошибка ${e.message}")
            } finally {
                _running.update { it - m.id }
            }
        }
        jobs[m.id] = job
        job.invokeOnCompletion { jobs.remove(m.id, job) }
        job.start()
    }

    fun stop(id: String) {
        jobs.remove(id)?.cancel()
    }

    private fun resolve(m: Macro, a: MacroAction, triggerAddress: String?): String? =
        when (a.deviceAddress) {
            null -> null
            TRIGGER_DEVICE -> triggerAddress ?: m.trigger.deviceAddress
            else -> a.deviceAddress
        }

    private suspend fun execute(m: Macro, index: Int, a: MacroAction, triggerAddress: String?) {
        val p = "«${m.name}» #${index + 1}"
        when (a.type) {
            ActionType.DELAY -> {
                EventLog.add("$p: пауза ${a.seconds} с")
                delay(a.seconds * 1000L)
            }

            ActionType.CONNECT, ActionType.DISCONNECT -> {
                val addr = resolve(m, a, triggerAddress)
                    ?: return EventLog.add("$p: устройство не задано")
                val r = withContext(Dispatchers.IO) {
                    if (a.type == ActionType.CONNECT) bt.connect(addr) else bt.disconnect(addr)
                }
                EventLog.add("$p: ${a.type.title.lowercase()} ${bt.nameOf(addr)} → " +
                    (if (r.ok) "OK" else "НЕ УДАЛОСЬ") + " (${r.details})")
            }

            ActionType.WAIT_CONNECTED, ActionType.WAIT_DISCONNECTED -> {
                val addr = resolve(m, a, triggerAddress)
                    ?: return EventLog.add("$p: устройство не задано")
                val want = a.type == ActionType.WAIT_CONNECTED
                val ok = withTimeoutOrNull(a.seconds * 1000L) {
                    bt.connected.first { (addr in it) == want }
                } != null
                EventLog.add("$p: ${bt.nameOf(addr)} " +
                    (if (want) "подключилось" else "отключилось").let { if (ok) it else "не $it за ${a.seconds} с" })
            }

            ActionType.BT_OFF -> EventLog.add("$p: выключить Bluetooth → " + okText(bt.setBluetooth(false)))
            ActionType.BT_ON -> EventLog.add("$p: включить Bluetooth → " + okText(bt.setBluetooth(true)))
            ActionType.BT_RESTART -> {
                EventLog.add("$p: выключаю Bluetooth → " + okText(bt.setBluetooth(false)))
                delay(a.seconds.coerceAtLeast(1) * 1000L)
                EventLog.add("$p: включаю Bluetooth → " + okText(bt.setBluetooth(true)))
            }

            ActionType.MUSIC_TO -> {
                val addr = resolve(m, a, triggerAddress)
                    ?: return EventLog.add("$p: устройство не задано")
                val r = music.setDevice(addr)
                EventLog.add("$p: музыку на ${bt.nameOf(addr)} → " +
                    (if (r.ok) "OK" else "НЕ УДАЛОСЬ") + " (${r.details})")
            }

            ActionType.MUSIC_PRIORITY -> {
                val r = music.apply(reason = "макрос «${m.name}»")
                if (r == null) EventLog.add("$p: приоритет — подходящих устройств нет")
            }

            ActionType.NOTIFY -> {
                Notifications.event(context, a.text.ifBlank { m.name })
                EventLog.add("$p: уведомление")
            }
        }
    }

    private fun okText(ok: Boolean) = if (ok) "OK" else "система отказала"
}
