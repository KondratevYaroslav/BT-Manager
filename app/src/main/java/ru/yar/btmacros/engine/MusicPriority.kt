package ru.yar.btmacros.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.data.EventLog

/**
 * Приоритет устройств для музыки: список адресов сверху вниз.
 * Когда меняется набор подключённых аудиоустройств, музыка направляется
 * на самое приоритетное из подключённых.
 */
class MusicPriority(context: Context, private val bt: BtController) {
    private val prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    private val _order = MutableStateFlow(
        prefs.getString("order", "").orEmpty().split(",").filter { it.isNotBlank() }
    )
    val order: StateFlow<List<String>> = _order

    private val _auto = MutableStateFlow(prefs.getBoolean("auto", true))
    val auto: StateFlow<Boolean> = _auto

    private fun saveOrder(list: List<String>) {
        _order.value = list
        prefs.edit().putString("order", list.joinToString(",")).apply()
    }

    fun setAuto(on: Boolean) {
        _auto.value = on
        prefs.edit().putBoolean("auto", on).apply()
        if (on) onAudioChanged()
    }

    @Synchronized
    fun add(address: String) {
        if (address !in _order.value) saveOrder(_order.value + address)
    }

    @Synchronized
    fun remove(address: String) = saveOrder(_order.value - address)

    @Synchronized
    fun move(address: String, up: Boolean) {
        val list = _order.value.toMutableList()
        val i = list.indexOf(address)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        saveOrder(list)
    }

    /** Вызывается при подключении/отключении аудиоустройства. Ждём 2 с, пока всё устаканится. */
    fun onAudioChanged() {
        if (!_auto.value || _order.value.isEmpty()) return
        pending?.cancel()
        pending = scope.launch {
            delay(2000)
            apply(reason = "авто")
        }
    }

    /** Выбрать самое приоритетное из подключённых и направить на него музыку. */
    suspend fun apply(reason: String): BtController.OpResult? = withContext(Dispatchers.IO) {
        bt.refresh()
        val audio = bt.a2dpConnected.value
        val target = _order.value.firstOrNull { it in audio }
        if (target == null) {
            if (reason != "авто") EventLog.add("Приоритет музыки: нет подключённых устройств из списка")
            return@withContext null
        }
        if (bt.musicDevice.value == target) {
            if (reason != "авто") EventLog.add("Приоритет музыки: уже играет на ${bt.nameOf(target)}")
            return@withContext BtController.OpResult(true, "уже выбрано")
        }
        // Если подключено только одно аудиоустройство — музыка и так на нём.
        if (audio.size == 1 && !bt.canReadMusicDevice()) return@withContext BtController.OpResult(true, "единственное")
        val r = bt.setMusicDevice(target)
        EventLog.add("Приоритет музыки ($reason): → ${bt.nameOf(target)} — " +
            (if (r.ok) "OK" else "НЕ УДАЛОСЬ") + " (${r.details})")
        r
    }

    /** Направить музыку на конкретное устройство (для макросов). */
    suspend fun setDevice(address: String): BtController.OpResult =
        withContext(Dispatchers.IO) { bt.setMusicDevice(address) }
}
