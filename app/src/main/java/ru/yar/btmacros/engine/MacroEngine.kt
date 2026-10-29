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
import ru.yar.btmacros.model.MediaKey
import ru.yar.btmacros.model.TRIGGER_DEVICE
import ru.yar.btmacros.model.TriggerType
import ru.yar.btmacros.model.describe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Откуда запущен макрос. */
enum class Source { EVENT, MANUAL, MACRO }

/** Запускает макросы по событиям и выполняет их действия по порядку. */
class MacroEngine(
    private val context: Context,
    private val bt: BtController,
    private val store: MacroStore,
    private val music: MusicPriority,
    private val aa: AndroidAutoMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastStart = ConcurrentHashMap<String, Long>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running

    /** Текст статуса по каждому макросу: текущий шаг или итог последнего запуска. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status

    private fun setStatus(id: String, text: String) = _status.update { it + (id to text) }

    fun onEvent(type: TriggerType, address: String? = null, param: String? = null) {
        for (m in store.macros.value) {
            if (!m.enabled || m.trigger.type != type) continue
            if (type.needsDevice) {
                val wanted = m.trigger.deviceAddress
                if (wanted != null && !wanted.equals(address, ignoreCase = true)) continue
            }
            if (type.needsMacro) {
                if (param == m.id) continue // не реагировать на своё же завершение
                if (m.trigger.param != null && m.trigger.param != param) continue
            }
            run(m, address, Source.EVENT)
        }
    }

    /** Почему макрос сейчас не должен запускаться (null — можно). */
    private fun conditionFailure(m: Macro): String? {
        m.conditionAddress?.let { if (!bt.isConnected(it)) return "${m.conditionName ?: it} не подключено" }
        val from = m.timeFrom
        val to = m.timeTo
        if (from != null && to != null && from != to) {
            val c = Calendar.getInstance()
            val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val inside = if (from < to) now in from until to else now >= from || now < to
            if (!inside) return "вне временного окна"
        }
        m.condAA?.let { want ->
            if (aa.connected.value != want) return if (want) "Android Auto не активен" else "Android Auto активен"
        }
        m.condMacroId?.let { id ->
            val isRunning = id in _running.value
            if (isRunning != m.condMacroRunning) {
                return "«${m.condMacroName ?: "?"}» " + if (isRunning) "выполняется" else "не выполняется"
            }
        }
        return null
    }

    /**
     * Запустить макрос. [chain] — цепочка вызывающих макросов (защита от рекурсии).
     * Возвращает Job или null, если запуск не состоялся.
     */
    fun run(m: Macro, triggerAddress: String?, source: Source, chain: Set<String> = emptySet()): Job? {
        if (m.id in chain || chain.size > 8) {
            EventLog.add("«${m.name}»: пропущен — макрос вызывает сам себя по цепочке")
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (source != Source.MANUAL) {
            conditionFailure(m)?.let {
                EventLog.add("«${m.name}»: условие не выполнено — $it")
                return null
            }
        }
        if (source == Source.EVENT) {
            val last = lastStart[m.id]
            if (last != null && now - last < m.cooldownSec * 1000L) {
                EventLog.add("«${m.name}»: пропущен (повтор раньше ${m.cooldownSec} с)")
                return null
            }
        }
        lastStart[m.id] = now
        jobs.remove(m.id)?.cancel()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            _running.update { it + m.id }
            setStatus(m.id, "запуск…")
            EventLog.add("▶ «${m.name}» запущен" + when (source) {
                Source.MANUAL -> " вручную"
                Source.MACRO -> " из другого макроса"
                Source.EVENT -> ""
            })
            var finishedOk = false
            try {
                m.actions.forEachIndexed { i, a ->
                    setStatus(m.id, "шаг ${i + 1}/${m.actions.size}: ${a.describe()}")
                    execute(m, i, a, triggerAddress, chain + m.id)
                }
                finishedOk = true
                setStatus(m.id, "выполнен в ${timeFmt.format(Date())}")
                EventLog.add("■ «${m.name}» выполнен")
            } catch (e: CancellationException) {
                setStatus(m.id, "прерван в ${timeFmt.format(Date())}")
                EventLog.add("✕ «${m.name}» прерван")
                throw e
            } catch (e: Exception) {
                setStatus(m.id, "ошибка: ${e.message}")
                EventLog.add("✕ «${m.name}»: ошибка ${e.message}")
            } finally {
                _running.update { it - m.id }
                if (finishedOk) onEvent(TriggerType.MACRO_FINISHED, null, m.id)
            }
        }
        jobs[m.id] = job
        job.invokeOnCompletion { jobs.remove(m.id, job) }
        job.start()
        return job
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

    /** Подстановки в тексты: {macro} {device} {time} {date}. */
    private fun expand(text: String, m: Macro, triggerAddress: String?): String {
        val now = Date()
        return text
            .replace("{macro}", m.name)
            .replace("{device}", triggerAddress?.let { bt.nameOf(it) } ?: "—")
            .replace("{time}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
            .replace("{date}", SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(now))
    }

    private suspend fun execute(m: Macro, index: Int, a: MacroAction, triggerAddress: String?, chain: Set<String>) {
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
                val what = if (want) "подключилось" else "отключилось"
                EventLog.add("$p: ${bt.nameOf(addr)} " + if (ok) what else "не $what за ${a.seconds} с")
            }

            ActionType.WAIT_AA_ON, ActionType.WAIT_AA_OFF -> {
                val want = a.type == ActionType.WAIT_AA_ON
                val ok = withTimeoutOrNull(a.seconds * 1000L) { aa.connected.first { it == want } } != null
                val what = if (want) "подключился" else "отключился"
                EventLog.add("$p: Android Auto " + if (ok) what else "не $what за ${a.seconds} с")
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

            ActionType.MEDIA_KEY -> {
                val key = runCatching { MediaKey.valueOf(a.param ?: "") }.getOrNull()
                    ?: return EventLog.add("$p: кнопка не выбрана")
                MediaKeys.send(context, key)
                EventLog.add("$p: медиа-кнопка «${key.title}»")
            }

            ActionType.RUN_MACRO -> {
                val target = store.macros.value.find { it.id == a.param }
                    ?: return EventLog.add("$p: макрос не найден (удалён?)")
                val job = run(target, triggerAddress, Source.MACRO, chain)
                if (job == null) {
                    EventLog.add("$p: «${target.name}» не запущен")
                } else if (a.flag) {
                    EventLog.add("$p: жду завершения «${target.name}»")
                    job.join()
                }
            }

            ActionType.STOP_MACRO -> {
                val id = a.param ?: return EventLog.add("$p: макрос не выбран")
                stop(id)
                EventLog.add("$p: остановить «${a.paramLabel ?: id}»")
            }

            ActionType.OPEN_APP -> {
                val pkg = a.param ?: return EventLog.add("$p: приложение не выбрано")
                val r = withContext(Dispatchers.Main) { Launcher.openApp(context, pkg, a.paramLabel ?: pkg) }
                EventLog.add("$p: открыть ${a.paramLabel ?: pkg} → $r")
            }

            ActionType.SET_TIMER -> {
                val label = expand(a.text, m, triggerAddress)
                val r = withContext(Dispatchers.Main) { Launcher.setTimer(context, a.seconds.coerceAtLeast(1), label) }
                EventLog.add("$p: таймер на ${a.seconds / 60} мин → $r")
            }

            ActionType.SET_ALARM -> {
                val label = expand(a.text, m, triggerAddress)
                val r = withContext(Dispatchers.Main) { Launcher.setAlarm(context, a.hour, a.minute, label) }
                EventLog.add("$p: будильник на %02d:%02d → $r".format(a.hour, a.minute))
            }

            ActionType.NOTIFY -> {
                Notifications.event(context, expand(a.text.ifBlank { m.name }, m, triggerAddress))
                EventLog.add("$p: уведомление")
            }

            ActionType.EMAIL -> {
                val cfg = Mailer.load(context)
                val err = withContext(Dispatchers.IO) {
                    Mailer.send(
                        cfg,
                        to = a.param.orEmpty(),
                        subject = expand(a.subject.ifBlank { "BT Макросы: {macro}" }, m, triggerAddress),
                        body = expand(a.text.ifBlank { "Сработал макрос «{macro}» в {time}" }, m, triggerAddress),
                    )
                }
                EventLog.add("$p: письмо → " + (err ?: "отправлено"))
            }
        }
    }

    private fun okText(ok: Boolean) = if (ok) "OK" else "система отказала"
}
