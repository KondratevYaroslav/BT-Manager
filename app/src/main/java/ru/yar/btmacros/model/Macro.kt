package ru.yar.btmacros.model

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Специальный «адрес»: устройство, которое вызвало срабатывание макроса. */
const val TRIGGER_DEVICE = "@trigger"

enum class TriggerType(val title: String, val needsDevice: Boolean = false, val needsMacro: Boolean = false) {
    DEVICE_CONNECTED("Устройство подключилось", needsDevice = true),
    DEVICE_DISCONNECTED("Устройство отключилось", needsDevice = true),
    AA_CONNECTED("Android Auto подключён"),
    AA_DISCONNECTED("Android Auto отключён"),
    BT_ON("Bluetooth включён"),
    BT_OFF("Bluetooth выключен"),
    MACRO_FINISHED("Другой макрос завершился", needsMacro = true),
    MANUAL("Только вручную / из виджета"),
}

/** Какие поля нужны действию — по ним строится редактор. */
enum class Field { DEVICE, SECONDS, MINUTES, TEXT, SUBJECT, EMAIL_TO, APP, MACRO, WAIT, CLOCK, MEDIA_KEY }

enum class ActionType(val title: String, vararg f: Field) {
    DELAY("Пауза", Field.SECONDS),
    CONNECT("Подключить устройство", Field.DEVICE),
    DISCONNECT("Отключить устройство", Field.DEVICE),
    WAIT_CONNECTED("Ждать подключения", Field.DEVICE, Field.SECONDS),
    WAIT_DISCONNECTED("Ждать отключения", Field.DEVICE, Field.SECONDS),
    BT_OFF("Выключить Bluetooth"),
    BT_ON("Включить Bluetooth"),
    BT_RESTART("Перезапустить Bluetooth", Field.SECONDS),
    MUSIC_TO("Музыку на устройство", Field.DEVICE),
    MUSIC_PRIORITY("Применить приоритет музыки"),
    MEDIA_KEY("Медиа-кнопка", Field.MEDIA_KEY),
    WAIT_AA_ON("Ждать подключения Android Auto", Field.SECONDS),
    WAIT_AA_OFF("Ждать отключения Android Auto", Field.SECONDS),
    RUN_MACRO("Запустить макрос", Field.MACRO, Field.WAIT),
    STOP_MACRO("Остановить макрос", Field.MACRO),
    OPEN_APP("Открыть приложение", Field.APP),
    SET_TIMER("Запустить таймер", Field.MINUTES, Field.TEXT),
    SET_ALARM("Поставить будильник", Field.CLOCK, Field.TEXT),
    NOTIFY("Показать уведомление", Field.TEXT),
    EMAIL("Отправить письмо", Field.EMAIL_TO, Field.SUBJECT, Field.TEXT),
    ;

    val fields: Set<Field> = f.toSet()
    fun has(x: Field) = x in fields
}

enum class MediaKey(val title: String, val code: Int) {
    PLAY_PAUSE("Пауза / воспроизведение", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    PLAY("Воспроизвести", KeyEvent.KEYCODE_MEDIA_PLAY),
    PAUSE("Пауза", KeyEvent.KEYCODE_MEDIA_PAUSE),
    NEXT("Следующий трек", KeyEvent.KEYCODE_MEDIA_NEXT),
    PREV("Предыдущий трек", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    STOP("Стоп", KeyEvent.KEYCODE_MEDIA_STOP),
}

data class Trigger(
    val type: TriggerType = TriggerType.DEVICE_CONNECTED,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** id макроса для MACRO_FINISHED */
    val param: String? = null,
    val paramLabel: String? = null,
)

data class MacroAction(
    val type: ActionType,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val seconds: Int = 10,
    val text: String = "",
    val subject: String = "",
    /** Адрес почты / пакет приложения / id макроса / имя MediaKey — в зависимости от типа. */
    val param: String? = null,
    /** Человекочитаемое имя для param (название приложения, макроса). */
    val paramLabel: String? = null,
    /** RUN_MACRO: ждать завершения. */
    val flag: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
)

data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Новый макрос",
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger(),
    /** Запускать только если это устройство сейчас подключено (null — без условия). */
    val conditionAddress: String? = null,
    val conditionName: String? = null,
    /** Временное окно в минутах от полуночи; может переходить через полночь (22:00–06:00). */
    val timeFrom: Int? = null,
    val timeTo: Int? = null,
    /** null — не важно, true — только когда Android Auto активен, false — только когда нет. */
    val condAA: Boolean? = null,
    /** Условие по другому макросу. */
    val condMacroId: String? = null,
    val condMacroName: String? = null,
    val condMacroRunning: Boolean = true,
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

fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

fun parseHhmm(s: String): Int? {
    val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(s) ?: return null
    val h = m.groupValues[1].toInt()
    val mm = m.groupValues[2].toInt()
    return if (h in 0..23 && mm in 0..59) h * 60 + mm else null
}

fun Trigger.describe(): String = when {
    type.needsDevice -> "${type.title}: ${deviceLabel(deviceAddress, deviceName, "любое")}"
    type.needsMacro -> "${type.title}: ${paramLabel ?: if (param == null) "любой" else "?"}"
    else -> type.title
}

fun Macro.describeConditions(): List<String> = buildList {
    if (conditionAddress != null) add("подключено ${conditionName ?: conditionAddress}")
    if (timeFrom != null && timeTo != null) add("время ${hhmm(timeFrom)}–${hhmm(timeTo)}")
    when (condAA) {
        true -> add("Android Auto активен")
        false -> add("Android Auto не активен")
        null -> {}
    }
    if (condMacroId != null) {
        add("«${condMacroName ?: "?"}» " + if (condMacroRunning) "выполняется" else "не выполняется")
    }
}

fun MacroAction.describe(): String = buildString {
    append(type.title)
    if (type.has(Field.DEVICE)) append(": ").append(deviceLabel(deviceAddress, deviceName, "не выбрано"))
    if (type.has(Field.MACRO)) append(": «").append(paramLabel ?: "не выбран").append("»")
    if (type.has(Field.WAIT) && flag) append(" и ждать завершения")
    if (type.has(Field.APP)) append(": ").append(paramLabel ?: param ?: "не выбрано")
    if (type.has(Field.MEDIA_KEY)) append(": ").append(
        runCatching { MediaKey.valueOf(param ?: "") }.getOrNull()?.title ?: "не выбрано"
    )
    if (type.has(Field.EMAIL_TO)) append(" → ").append(param?.ifBlank { null } ?: "адрес по умолчанию")
    if (type.has(Field.CLOCK)) append(" на ").append(hhmm(hour * 60 + minute))
    if (type.has(Field.MINUTES)) append(" на ").append(seconds / 60).append(" мин")
    if (type.has(Field.SECONDS)) {
        when (type) {
            ActionType.DELAY -> append(" ").append(seconds).append(" с")
            ActionType.BT_RESTART -> append(" (пауза ").append(seconds).append(" с)")
            else -> append(" (до ").append(seconds).append(" с)")
        }
    }
    if (type.has(Field.SUBJECT) && subject.isNotBlank()) append(": «").append(subject).append("»")
    else if (type.has(Field.TEXT) && text.isNotBlank() && !type.has(Field.SUBJECT)) {
        append(": «").append(text).append("»")
    }
}

// ---------- JSON ----------

object MacroJson {
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)

    private fun JSONObject.bool(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else getBoolean(key)

    private fun JSONObject.putNullable(key: String, v: Any?): JSONObject =
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
            .putNullable("name", m.trigger.deviceName)
            .putNullable("param", m.trigger.param)
            .putNullable("paramLabel", m.trigger.paramLabel))
        .putNullable("condAddress", m.conditionAddress)
        .putNullable("condName", m.conditionName)
        .putNullable("timeFrom", m.timeFrom)
        .putNullable("timeTo", m.timeTo)
        .putNullable("condAA", m.condAA)
        .putNullable("condMacroId", m.condMacroId)
        .putNullable("condMacroName", m.condMacroName)
        .put("condMacroRunning", m.condMacroRunning)
        .put("cooldown", m.cooldownSec)
        .put("actions", JSONArray().apply {
            m.actions.forEach { a ->
                put(JSONObject()
                    .put("type", a.type.name)
                    .putNullable("address", a.deviceAddress)
                    .putNullable("name", a.deviceName)
                    .put("seconds", a.seconds)
                    .put("text", a.text)
                    .put("subject", a.subject)
                    .putNullable("param", a.param)
                    .putNullable("paramLabel", a.paramLabel)
                    .put("flag", a.flag)
                    .put("hour", a.hour)
                    .put("minute", a.minute))
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
                type = runCatching { TriggerType.valueOf(t.getString("type")) }.getOrDefault(TriggerType.MANUAL),
                deviceAddress = t.str("address"),
                deviceName = t.str("name"),
                param = t.str("param"),
                paramLabel = t.str("paramLabel"),
            ),
            conditionAddress = o.str("condAddress"),
            conditionName = o.str("condName"),
            timeFrom = o.int("timeFrom"),
            timeTo = o.int("timeTo"),
            condAA = o.bool("condAA"),
            condMacroId = o.str("condMacroId"),
            condMacroName = o.str("condMacroName"),
            condMacroRunning = o.optBoolean("condMacroRunning", true),
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
                    subject = a.optString("subject", ""),
                    param = a.str("param"),
                    paramLabel = a.str("paramLabel"),
                    flag = a.optBoolean("flag", false),
                    hour = a.optInt("hour", 7),
                    minute = a.optInt("minute", 0),
                )
            },
        )
    }
}
