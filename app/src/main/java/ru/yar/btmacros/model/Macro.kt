package ru.yar.btmacros.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Специальный «адрес»: устройство, которое вызвало срабатывание макроса. */
const val TRIGGER_DEVICE = "@trigger"

enum class TriggerType(val title: String, val needsDevice: Boolean) {
    DEVICE_CONNECTED("Устройство подключилось", true),
    DEVICE_DISCONNECTED("Устройство отключилось", true),
    BT_ON("Bluetooth включён", false),
    BT_OFF("Bluetooth выключен", false),
    MANUAL("Только вручную", false),
}

enum class ActionType(
    val title: String,
    val needsDevice: Boolean,
    val needsSeconds: Boolean,
    val needsText: Boolean = false,
) {
    DELAY("Пауза", false, true),
    CONNECT("Подключить", true, false),
    DISCONNECT("Отключить", true, false),
    WAIT_CONNECTED("Ждать подключения", true, true),
    WAIT_DISCONNECTED("Ждать отключения", true, true),
    BT_OFF("Выключить Bluetooth", false, false),
    BT_ON("Включить Bluetooth", false, false),
    BT_RESTART("Перезапустить Bluetooth", false, true),
    NOTIFY("Показать уведомление", false, false, true),
    MUSIC_TO("Музыку на устройство", true, false),
    MUSIC_PRIORITY("Применить приоритет музыки", false, false),
}

data class Trigger(
    val type: TriggerType = TriggerType.DEVICE_CONNECTED,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
)

data class MacroAction(
    val type: ActionType,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val seconds: Int = 10,
    val text: String = "",
)

data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Новый макрос",
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger(),
    /** Запускать только если это устройство сейчас подключено (null — без условия). */
    val conditionAddress: String? = null,
    val conditionName: String? = null,
    /** Защита от зацикливания: не запускать чаще, чем раз в N секунд. */
    val cooldownSec: Int = 30,
    val actions: List<MacroAction> = emptyList(),
)

fun newMacroTemplate() = Macro(
    name = "Подключить и отключить через 10 с",
    trigger = Trigger(TriggerType.DEVICE_CONNECTED),
    actions = listOf(
        MacroAction(ActionType.DELAY, seconds = 10),
        MacroAction(ActionType.DISCONNECT, deviceAddress = TRIGGER_DEVICE),
    ),
)

// ---------- Описания для интерфейса ----------

fun deviceLabel(address: String?, name: String?, nullLabel: String): String = when (address) {
    null -> nullLabel
    TRIGGER_DEVICE -> "устройство-триггер"
    else -> name ?: address
}

fun Trigger.describe(): String =
    if (type.needsDevice) "${type.title}: ${deviceLabel(deviceAddress, deviceName, "любое")}"
    else type.title

fun MacroAction.describe(): String = buildString {
    append(type.title)
    if (type.needsDevice) append(": ").append(deviceLabel(deviceAddress, deviceName, "не выбрано"))
    if (type.needsSeconds) {
        append(if (type == ActionType.DELAY) " " else " (до ")
        append(seconds).append(" с")
        if (type != ActionType.DELAY) append(")")
    }
    if (type.needsText) append(": «").append(text).append("»")
}

// ---------- JSON ----------

object MacroJson {
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.putNullable(key: String, v: String?): JSONObject =
        put(key, v ?: JSONObject.NULL)

    fun listToJson(list: List<Macro>): String = JSONArray().apply {
        list.forEach { put(toJson(it)) }
    }.toString()

    fun listFromJson(s: String): List<Macro> {
        val arr = JSONArray(s)
        return (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun toJson(m: Macro) = JSONObject()
        .put("id", m.id)
        .put("name", m.name)
        .put("enabled", m.enabled)
        .put("trigger", JSONObject()
            .put("type", m.trigger.type.name)
            .putNullable("address", m.trigger.deviceAddress)
            .putNullable("name", m.trigger.deviceName))
        .putNullable("condAddress", m.conditionAddress)
        .putNullable("condName", m.conditionName)
        .put("cooldown", m.cooldownSec)
        .put("actions", JSONArray().apply {
            m.actions.forEach { a ->
                put(JSONObject()
                    .put("type", a.type.name)
                    .putNullable("address", a.deviceAddress)
                    .putNullable("name", a.deviceName)
                    .put("seconds", a.seconds)
                    .put("text", a.text))
            }
        })

    private fun fromJson(o: JSONObject): Macro {
        val t = o.getJSONObject("trigger")
        val acts = o.optJSONArray("actions") ?: JSONArray()
        return Macro(
            id = o.getString("id"),
            name = o.optString("name", "Макрос"),
            enabled = o.optBoolean("enabled", true),
            trigger = Trigger(
                type = TriggerType.valueOf(t.getString("type")),
                deviceAddress = t.str("address"),
                deviceName = t.str("name"),
            ),
            conditionAddress = o.str("condAddress"),
            conditionName = o.str("condName"),
            cooldownSec = o.optInt("cooldown", 30),
            actions = (0 until acts.length()).mapNotNull { i ->
                val a = acts.getJSONObject(i)
                val type = runCatching { ActionType.valueOf(a.getString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                MacroAction(
                    type = type,
                    deviceAddress = a.str("address"),
                    deviceName = a.str("name"),
                    seconds = a.optInt("seconds", 10),
                    text = a.optString("text", ""),
                )
            },
        )
    }
}
