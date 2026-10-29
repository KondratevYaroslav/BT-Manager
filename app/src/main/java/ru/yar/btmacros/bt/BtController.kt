package ru.yar.btmacros.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.yar.btmacros.data.EventLog
import ru.yar.btmacros.model.TriggerType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Всё, что касается Bluetooth: список сохранённых устройств, кто подключён,
 * подключение/отключение, включение/выключение адаптера.
 *
 * Подключить/отключить конкретное устройство публичным API Android нельзя,
 * поэтому используются скрытые методы профилей A2DP (музыка) и HFP (звонки)
 * через рефлексию. На части телефонов с Android 12+ система может их запретить —
 * для этого есть «Диагностика» и запасной вариант «Перезапустить Bluetooth».
 */
@SuppressLint("MissingPermission")
class BtController(private val context: Context) {

    data class Device(val address: String, val name: String, val kind: String)
    data class OpResult(val ok: Boolean, val details: String)

    private val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter: BluetoothAdapter? get() = manager?.adapter

    @Volatile private var a2dp: BluetoothProfile? = null
    @Volatile private var headset: BluetoothProfile? = null

    /** Запасной учёт ACL-подключений, если скрытый isConnected() недоступен. */
    private val aclFallback: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val isConnectedMethod: Method? by lazy {
        runCatching { BluetoothDevice::class.java.getMethod("isConnected") }.getOrNull()
    }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _bonded = MutableStateFlow<List<Device>>(emptyList())
    val bonded: StateFlow<List<Device>> = _bonded

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected

    /** Подключённые по A2DP — те, на которые в принципе может идти музыка. */
    private val _a2dpConnected = MutableStateFlow<Set<String>>(emptySet())
    val a2dpConnected: StateFlow<Set<String>> = _a2dpConnected

    /** Устройство, на которое сейчас идёт музыка (null — неизвестно или нет). */
    private val _musicDevice = MutableStateFlow<String?>(null)
    val musicDevice: StateFlow<String?> = _musicDevice

    /** Устройства, найденные поиском рядом: адрес → время, когда видели последний раз. */
    private val seenAt = ConcurrentHashMap<String, Long>()
    private val _nearby = MutableStateFlow<Set<String>>(emptySet())
    val nearby: StateFlow<Set<String>> = _nearby

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Почему список устройств может быть пустым (null — всё в порядке). */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    /** Куда отправлять события-триггеры (выставляет App → MacroEngine). */
    var onEvent: ((TriggerType, String?) -> Unit)? = null

    /** Изменился набор аудиоустройств (выставляет App → MusicPriority). */
    var onAudioChanged: (() -> Unit)? = null

    private var started = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy
                BluetoothProfile.HEADSET -> headset = proxy
            }
            refresh()
            if (profile == BluetoothProfile.A2DP) onAudioChanged?.invoke()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = null
                BluetoothProfile.HEADSET -> headset = null
            }
            refresh()
        }
    }

    /** Состояние адаптера и профилей. ACL-события приходят через BtEventReceiver. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = androidx.core.content.IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (d != null) {
                        seenAt[d.address] = SystemClock.elapsedRealtime()
                        updateNearby()
                    }
                    return
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _scanning.value = true
                    return
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _scanning.value = false
                    updateNearby()
                    return
                }
            }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> {
                        EventLog.add("Bluetooth включён")
                        refresh()
                        onEvent?.invoke(TriggerType.BT_ON, null)
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        EventLog.add("Bluetooth выключен")
                        aclFallback.clear()
                        refresh()
                        onEvent?.invoke(TriggerType.BT_OFF, null)
                    }
                }
            } else {
                refresh()
                if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) onAudioChanged?.invoke()
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_A2DP_ACTIVE_DEVICE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        ensureProxies()
        refresh()
    }

    private fun updateNearby() {
        val now = SystemClock.elapsedRealtime()
        seenAt.entries.removeIf { now - it.value > NEARBY_TTL_MS }
        _nearby.value = seenAt.keys.toSet()
    }

    /**
     * Поиск устройств рядом (около 12 секунд). Находит только те устройства,
     * которые сейчас в режиме видимости. Возвращает null при успехе или текст причины.
     */
    @Suppress("DEPRECATION")
    fun startScan(): String? {
        val ad = adapter ?: return "нет Bluetooth-адаптера"
        if (!ad.isEnabled) return "Bluetooth выключен"
        if (!hasLocationPermission()) return "нет разрешения на геолокацию — без него Android не даёт искать устройства"
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm != null && !androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) {
            return "выключена геолокация — включите её в шторке, иначе поиск ничего не найдёт"
        }
        return try {
            if (ad.isDiscovering) null
            else if (ad.startDiscovery()) null else "система не запустила поиск"
        } catch (e: SecurityException) {
            "система запретила поиск: ${e.message}"
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** На Android 12+ — выдано ли разрешение «Устройства поблизости». */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Подключиться к профилям A2DP/HFP (повторно — после выдачи разрешения). */
    fun ensureProxies() {
        val ad = adapter ?: return
        if (a2dp == null) runCatching { ad.getProfileProxy(context, profileListener, BluetoothProfile.A2DP) }
        if (headset == null) runCatching { ad.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET) }
    }

    private fun setProblem(p: String?) {
        if (_problem.value != p) {
            _problem.value = p
            if (p != null) EventLog.add("Проблема: $p")
        }
    }

    /** Вызывается из BtEventReceiver при ACL_CONNECTED / ACL_DISCONNECTED. */
    fun onAclEvent(address: String, name: String?, connected: Boolean) {
        if (connected) aclFallback.add(address) else aclFallback.remove(address)
        EventLog.add((if (connected) "Подключено: " else "Отключено: ") + (name ?: address))
        refresh()
        onEvent?.invoke(
            if (connected) TriggerType.DEVICE_CONNECTED else TriggerType.DEVICE_DISCONNECTED,
            address,
        )
    }

    @Synchronized
    fun refresh() {
        val ad = adapter
        val on = ad?.isEnabled == true
        _enabled.value = on

        var problem: String? = null
        val devices: List<BluetoothDevice> = try {
            ad?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            problem = "система не дала доступ к списку устройств (${e.message})"
            emptyList()
        }
        if (ad == null) problem = "на телефоне не найден Bluetooth-адаптер"
        else if (problem == null && !hasPermission()) problem = "не выдано разрешение «Устройства поблизости»"
        else if (problem == null && on && devices.isEmpty()) problem = "система вернула пустой список сопряжённых устройств"
        setProblem(problem)
        _bonded.value = devices
            .map { Device(it.address, safeName(it) ?: it.address, kindOf(it)) }
            .sortedBy { it.name.lowercase() }

        val set = HashSet<String>()
        if (on) {
            val m = isConnectedMethod
            if (m != null) {
                devices.forEach { d ->
                    if (runCatching { m.invoke(d) as Boolean }.getOrDefault(false)) set.add(d.address)
                }
            } else {
                set.addAll(aclFallback)
            }
            runCatching { headset?.connectedDevices?.forEach { set.add(it.address) } }
        }
        val audio = HashSet<String>()
        if (on) runCatching { a2dp?.connectedDevices?.forEach { audio.add(it.address) } }
        set.addAll(audio)
        _connected.value = set
        updateNearby()
        _a2dpConnected.value = audio
        _musicDevice.value = if (on) readActiveMusicDevice() else null
    }

    private fun readActiveMusicDevice(): String? = runCatching {
        val p = a2dp ?: return null
        (p.javaClass.getMethod("getActiveDevice").invoke(p) as? BluetoothDevice)?.address
    }.getOrNull()

    /** Можем ли мы вообще узнать, куда сейчас идёт музыка. */
    fun canReadMusicDevice(): Boolean = runCatching {
        val p = a2dp ?: return false
        p.javaClass.getMethod("getActiveDevice").invoke(p)
        true
    }.getOrDefault(false)

    /**
     * Направить музыку на устройство (оно должно быть подключено по A2DP).
     * Скрытый метод BluetoothA2dp.setActiveDevice — на части прошивок Android 12+ запрещён.
     */
    fun setMusicDevice(address: String): OpResult {
        val ad = adapter ?: return OpResult(false, "нет Bluetooth-адаптера")
        val p = a2dp ?: return OpResult(false, "профиль A2DP недоступен")
        if (address !in _a2dpConnected.value) return OpResult(false, "устройство не подключено для музыки")
        val device = try {
            ad.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return OpResult(false, "неверный адрес")
        }
        return try {
            val ok = (p.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
                .invoke(p, device) as? Boolean) ?: true
            refresh()
            OpResult(ok, if (ok) "ок" else "система вернула отказ")
        } catch (e: InvocationTargetException) {
            val t = e.targetException
            OpResult(false, "${t?.javaClass?.simpleName} ${t?.message.orEmpty()}".trim())
        } catch (e: Exception) {
            OpResult(false, e.javaClass.simpleName)
        }
    }

    fun isConnected(address: String) = address in _connected.value

    fun nameOf(address: String): String =
        _bonded.value.find { it.address == address }?.name ?: address

    fun connect(address: String): OpResult = profileOp("connect", address)

    fun disconnect(address: String): OpResult = profileOp("disconnect", address)

    private fun profileOp(method: String, address: String): OpResult {
        val ad = adapter ?: return OpResult(false, "нет Bluetooth-адаптера")
        if (!ad.isEnabled) return OpResult(false, "Bluetooth выключен")
        val device = try {
            ad.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return OpResult(false, "неверный адрес")
        }
        val parts = mutableListOf<String>()
        var anyOk = false
        for ((label, proxy) in listOf("A2DP" to a2dp, "HFP" to headset)) {
            if (proxy == null) {
                parts += "$label: профиль недоступен"
                continue
            }
            try {
                val m = proxy.javaClass.getMethod(method, BluetoothDevice::class.java)
                val ok = (m.invoke(proxy, device) as? Boolean) ?: true
                anyOk = anyOk || ok
                parts += "$label: " + if (ok) "ок" else "отказ"
            } catch (e: InvocationTargetException) {
                val t = e.targetException
                parts += "$label: ${t?.javaClass?.simpleName} ${t?.message.orEmpty()}".trim()
            } catch (e: Exception) {
                parts += "$label: ${e.javaClass.simpleName}"
            }
        }
        refresh()
        return OpResult(anyOk, parts.joinToString("; "))
    }

    /** Включить/выключить адаптер. Работает благодаря targetSdk 30. */
    @Suppress("DEPRECATION")
    fun setBluetooth(on: Boolean): Boolean {
        val ad = adapter ?: return false
        return try {
            if (on) ad.enable() else ad.disable()
        } catch (e: SecurityException) {
            false
        }
    }

    fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun diagnose(): String = buildString {
        appendLine("Сборка приложения: ${appVersion()}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        val ad = adapter
        appendLine("Адаптер: " + when {
            ad == null -> "нет"
            ad.isEnabled -> "включён"
            else -> "выключен"
        })
        appendLine("Разрешение «Устройства поблизости»: " +
            if (Build.VERSION.SDK_INT < 31) "не требуется" else if (hasPermission()) "выдано" else "НЕ выдано")
        val bondedRaw = try {
            "${ad?.bondedDevices?.size ?: "нет адаптера"}"
        } catch (e: Exception) {
            "ошибка ${e.javaClass.simpleName}: ${e.message}"
        }
        appendLine("Сопряжённых устройств (ответ системы): $bondedRaw")
        val viaProfiles = runCatching {
            (a2dp?.connectedDevices.orEmpty() + headset?.connectedDevices.orEmpty()).size
        }.fold({ "$it" }, { "ошибка ${it.javaClass.simpleName}" })
        appendLine("Подключено по A2DP/HFP: $viaProfiles")
        appendLine("Профиль A2DP: " + if (a2dp != null) "доступен" else "нет")
        appendLine("Профиль HFP: " + if (headset != null) "доступен" else "нет")
        for ((label, proxy) in listOf("A2DP" to a2dp, "HFP" to headset)) {
            val cls = proxy?.javaClass ?: continue
            for (m in listOf("connect", "disconnect")) {
                val found = runCatching { cls.getMethod(m, BluetoothDevice::class.java) }.isSuccess
                appendLine("$label.$m: " + if (found) "метод найден" else "скрыт системой")
            }
        }
        appendLine("Определение подключений (isConnected): " +
            if (isConnectedMethod != null) "доступно" else "по событиям")
        appendLine("Чтение устройства для музыки: " + if (canReadMusicDevice()) "доступно" else "запрещено")
        val hasSetActive = runCatching {
            a2dp!!.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
        }.isSuccess
        appendLine("A2DP.setActiveDevice (приоритет музыки): " + if (hasSetActive) "метод найден" else "скрыт системой")
        appendLine()
        append("«Метод найден» ещё не гарантирует, что система разрешит вызов. " +
            "Проверьте кнопками «Подключить»/«Отключить» на нужном устройстве и посмотрите " +
            "результат во вкладке «Журнал». Если там SecurityException — прошивка запрещает это " +
            "обычным приложениям; используйте действие «Перезапустить Bluetooth».")
    }

    private fun safeName(d: BluetoothDevice): String? =
        try { d.name } catch (e: SecurityException) { null }

    private fun kindOf(d: BluetoothDevice): String = try {
        when (d.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "Аудио"
            BluetoothClass.Device.Major.PHONE -> "Телефон"
            BluetoothClass.Device.Major.COMPUTER -> "Компьютер"
            BluetoothClass.Device.Major.PERIPHERAL -> "Периферия"
            BluetoothClass.Device.Major.WEARABLE -> "Носимое"
            else -> ""
        }
    } catch (e: SecurityException) {
        ""
    }

    companion object {
        /** Скрытая константа BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED. */
        /** Сколько считать устройство «рядом» после последнего обнаружения. */
        const val NEARBY_TTL_MS = 3 * 60 * 1000L

        const val ACTION_A2DP_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
    }
}
