package ru.yar.btmacros.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.yar.btmacros.App
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.engine.Launcher
import ru.yar.btmacros.model.ActionType
import ru.yar.btmacros.model.Field
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.MacroAction
import ru.yar.btmacros.model.MediaKey
import ru.yar.btmacros.model.TRIGGER_DEVICE
import ru.yar.btmacros.model.TriggerType
import ru.yar.btmacros.model.hhmm
import ru.yar.btmacros.model.parseHhmm

/** Данные, которые нужны пикерам редактора. */
private class EditorData(
    val bonded: List<BtController.Device>,
    val macros: List<Macro>,
    val apps: List<Launcher.AppInfo>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditor(initial: Macro, onSave: (Macro) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val bonded by App.instance.bt.bonded.collectAsStateWithLifecycle()
    val allMacros by App.instance.store.macros.collectAsStateWithLifecycle()
    val otherMacros = allMacros.filter { it.id != initial.id }
    var apps by remember { mutableStateOf<List<Launcher.AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Launcher.listApps(ctx) } }
    val data = EditorData(bonded, otherMacros, apps)

    var name by remember { mutableStateOf(initial.name) }
    var trigger by remember { mutableStateOf(initial.trigger) }
    var condAddr by remember { mutableStateOf(initial.conditionAddress) }
    var condName by remember { mutableStateOf(initial.conditionName) }
    var useTime by remember { mutableStateOf(initial.timeFrom != null && initial.timeTo != null) }
    var timeFrom by remember { mutableStateOf(hhmm(initial.timeFrom ?: 22 * 60)) }
    var timeTo by remember { mutableStateOf(hhmm(initial.timeTo ?: 6 * 60)) }
    var condAA by remember { mutableStateOf(initial.condAA) }
    var condMacroId by remember { mutableStateOf(initial.condMacroId) }
    var condMacroName by remember { mutableStateOf(initial.condMacroName) }
    var condMacroRunning by remember { mutableStateOf(initial.condMacroRunning) }
    var cooldown by remember { mutableStateOf(initial.cooldownSec.toString()) }
    val actions = remember { mutableStateListOf<MacroAction>().apply { addAll(initial.actions) } }

    fun save() = onSave(
        initial.copy(
            name = name.ifBlank { "Макрос" },
            trigger = trigger,
            conditionAddress = condAddr,
            conditionName = condName,
            timeFrom = if (useTime) parseHhmm(timeFrom) else null,
            timeTo = if (useTime) parseHhmm(timeTo) else null,
            condAA = condAA,
            condMacroId = condMacroId,
            condMacroName = condMacroName,
            condMacroRunning = condMacroRunning,
            cooldownSec = cooldown.toIntOrNull()?.coerceIn(0, 3600) ?: 30,
            actions = actions.toList(),
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Макрос") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Отмена") } },
                actions = { TextButton(onClick = { save() }) { Text("Сохранить") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Название") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------- Триггер ----------
            item { Section("Когда срабатывает") }
            item {
                Picker("Событие", trigger.type.title, TriggerType.entries.map { it.title to it }) {
                    trigger = trigger.copy(type = it)
                }
            }
            if (trigger.type.needsDevice) {
                item {
                    DevicePicker("Какое устройство", trigger.deviceAddress, trigger.deviceName, bonded,
                        nullLabel = "Любое устройство") { a, n -> trigger = trigger.copy(deviceAddress = a, deviceName = n) }
                }
            }
            if (trigger.type.needsMacro) {
                item {
                    MacroPicker("Какой макрос", trigger.param, trigger.paramLabel, otherMacros, nullLabel = "Любой") { id, n ->
                        trigger = trigger.copy(param = id, paramLabel = n)
                    }
                }
            }

            // ---------- Условия ----------
            item { Section("Условия (все должны выполняться)") }
            item {
                DevicePicker("Подключено устройство", condAddr, condName, bonded,
                    nullLabel = "Не важно") { a, n -> condAddr = a; condName = n }
            }
            item {
                Picker("Android Auto", when (condAA) {
                    null -> "Не важно"
                    true -> "Только когда подключён"
                    false -> "Только когда не подключён"
                }, listOf("Не важно" to null, "Только когда подключён" to true, "Только когда не подключён" to false)) {
                    condAA = it
                }
            }
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Только в заданное время", Modifier.weight(1f))
                        Switch(checked = useTime, onCheckedChange = { useTime = it })
                    }
                    if (useTime) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimeField("С", timeFrom, Modifier.weight(1f)) { timeFrom = it }
                            TimeField("До", timeTo, Modifier.weight(1f)) { timeTo = it }
                        }
                        Text("Например 22:00–06:00 — только ночью, 08:00–20:00 — только днём.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                MacroPicker("Другой макрос", condMacroId, condMacroName, otherMacros, nullLabel = "Не важно") { id, n ->
                    condMacroId = id; condMacroName = n
                }
            }
            if (condMacroId != null) {
                item {
                    Picker("Состояние этого макроса", if (condMacroRunning) "выполняется" else "не выполняется",
                        listOf("выполняется" to true, "не выполняется" to false)) { condMacroRunning = it }
                }
            }
            item {
                OutlinedTextField(
                    value = cooldown, onValueChange = { cooldown = it.filter(Char::isDigit).take(4) },
                    label = { Text("Не повторять чаще, чем раз в N секунд") },
                    supportingText = { Text("Защита от зацикливания") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------- Действия ----------
            item { Section("Что делать (по порядку)") }
            itemsIndexed(actions) { i, a ->
                ActionEditor(
                    index = i, action = a, data = data,
                    canUp = i > 0, canDown = i < actions.lastIndex,
                    onChange = { actions[i] = it },
                    onUp = { actions.add(i - 1, actions.removeAt(i)) },
                    onDown = { actions.add(i + 1, actions.removeAt(i)) },
                    onDelete = { actions.removeAt(i) },
                )
            }
            item {
                AddActionButton { t -> actions.add(defaultAction(t)) }
            }
        }
    }
}

private fun defaultAction(t: ActionType) = MacroAction(
    type = t,
    deviceAddress = if (t.has(Field.DEVICE)) TRIGGER_DEVICE else null,
    seconds = when (t) {
        ActionType.DELAY -> 10
        ActionType.BT_RESTART -> 3
        ActionType.SET_TIMER -> 5 * 60
        else -> 15
    },
    param = if (t == ActionType.MEDIA_KEY) MediaKey.PLAY_PAUSE.name else null,
)

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ActionEditor(
    index: Int,
    action: MacroAction,
    data: EditorData,
    canUp: Boolean,
    canDown: Boolean,
    onChange: (MacroAction) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = action.type
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp))
                Box(Modifier.weight(1f)) {
                    Picker("Действие", t.title, ActionType.entries.map { it.title to it }) { nt ->
                        if (nt != t) onChange(defaultAction(nt))
                    }
                }
                IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, "Выше") }
                IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, "Ниже") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
            }
            if (t.has(Field.DEVICE)) {
                DevicePicker("Устройство", action.deviceAddress, action.deviceName, data.bonded,
                    nullLabel = "— не выбрано —", allowTrigger = true) { a, n ->
                    onChange(action.copy(deviceAddress = a, deviceName = n))
                }
            }
            if (t.has(Field.MACRO)) {
                MacroPicker("Макрос", action.param, action.paramLabel, data.macros, nullLabel = "— не выбран —") { id, n ->
                    onChange(action.copy(param = id, paramLabel = n))
                }
            }
            if (t.has(Field.WAIT)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = action.flag, onCheckedChange = { onChange(action.copy(flag = it)) })
                    Text("Дождаться его завершения")
                }
            }
            if (t.has(Field.APP)) {
                Picker("Приложение", action.paramLabel ?: "— не выбрано —",
                    data.apps.map { it.label to it }) { app ->
                    onChange(action.copy(param = app.packageName, paramLabel = app.label))
                }
                if (data.apps.isEmpty()) Text("Загружаю список…", style = MaterialTheme.typography.bodySmall)
            }
            if (t.has(Field.MEDIA_KEY)) {
                val cur = runCatching { MediaKey.valueOf(action.param ?: "") }.getOrNull()
                Picker("Кнопка", cur?.title ?: "— не выбрана —", MediaKey.entries.map { it.title to it }) {
                    onChange(action.copy(param = it.name))
                }
            }
            if (t.has(Field.SECONDS)) {
                NumberField(
                    label = when (t) {
                        ActionType.DELAY -> "Пауза, секунд"
                        ActionType.BT_RESTART -> "Сколько держать выключенным, секунд"
                        else -> "Ждать не дольше, секунд"
                    },
                    value = action.seconds,
                ) { onChange(action.copy(seconds = it)) }
            }
            if (t.has(Field.MINUTES)) {
                NumberField("Длительность, минут", action.seconds / 60) {
                    onChange(action.copy(seconds = it * 60))
                }
            }
            if (t.has(Field.CLOCK)) {
                var text by remember(action.hour, action.minute) { mutableStateOf(hhmm(action.hour * 60 + action.minute)) }
                TimeField("Время будильника", text, Modifier.fillMaxWidth()) { v ->
                    text = v
                    parseHhmm(v)?.let { onChange(action.copy(hour = it / 60, minute = it % 60)) }
                }
            }
            if (t.has(Field.EMAIL_TO)) {
                OutlinedTextField(
                    value = action.param.orEmpty(), onValueChange = { onChange(action.copy(param = it)) },
                    label = { Text("Кому (пусто = адрес из настроек)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (t.has(Field.SUBJECT)) {
                OutlinedTextField(
                    value = action.subject, onValueChange = { onChange(action.copy(subject = it)) },
                    label = { Text("Тема") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (t.has(Field.TEXT)) {
                OutlinedTextField(
                    value = action.text, onValueChange = { onChange(action.copy(text = it)) },
                    label = {
                        Text(when (t) {
                            ActionType.EMAIL -> "Текст письма"
                            ActionType.NOTIFY -> "Текст уведомления"
                            else -> "Подпись"
                        })
                    },
                    singleLine = t != ActionType.EMAIL,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (t == ActionType.EMAIL || t == ActionType.NOTIFY) {
                    Text("Можно вставлять {macro}, {device}, {time}, {date}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == ':' }.take(5)) },
        label = { Text(label) },
        isError = parseHhmm(value) == null,
        placeholder = { Text("ЧЧ:ММ") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = modifier,
    )
}

/** Список действий сгруппирован, чтобы в нём было легко ориентироваться. */
private val actionGroups = listOf(
    "Время и управление" to listOf(ActionType.DELAY, ActionType.RUN_MACRO, ActionType.STOP_MACRO),
    "Bluetooth" to listOf(ActionType.CONNECT, ActionType.DISCONNECT, ActionType.WAIT_CONNECTED,
        ActionType.WAIT_DISCONNECTED, ActionType.BT_OFF, ActionType.BT_ON, ActionType.BT_RESTART),
    "Музыка" to listOf(ActionType.MUSIC_TO, ActionType.MUSIC_PRIORITY, ActionType.MEDIA_KEY),
    "Android Auto" to listOf(ActionType.WAIT_AA_ON, ActionType.WAIT_AA_OFF),
    "Телефон" to listOf(ActionType.OPEN_APP, ActionType.SET_TIMER, ActionType.SET_ALARM,
        ActionType.NOTIFY, ActionType.EMAIL),
)

@Composable
private fun AddActionButton(onPick: (ActionType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Text(" Добавить действие")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
            actionGroups.forEachIndexed { gi, (group, types) ->
                if (gi > 0) HorizontalDivider()
                Text(group, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                types.forEach { t ->
                    DropdownMenuItem(text = { Text(t.title) }, onClick = { open = false; onPick(t) })
                }
            }
        }
    }
}

@Composable
private fun DevicePicker(
    label: String,
    address: String?,
    name: String?,
    bonded: List<BtController.Device>,
    nullLabel: String,
    allowTrigger: Boolean = false,
    onPick: (String?, String?) -> Unit,
) {
    val options = buildList<Pair<String, Pair<String?, String?>>> {
        add(nullLabel to (null to null))
        if (allowTrigger) add("Устройство, вызвавшее макрос" to (TRIGGER_DEVICE to null))
        bonded.forEach { add("${it.name}  ·  ${it.address}" to (it.address to it.name)) }
    }
    val current = when (address) {
        null -> nullLabel
        TRIGGER_DEVICE -> "Устройство, вызвавшее макрос"
        else -> name ?: address
    }
    Picker(label, current, options) { (a, n) -> onPick(a, n) }
}

@Composable
private fun MacroPicker(
    label: String,
    id: String?,
    name: String?,
    macros: List<Macro>,
    nullLabel: String,
    onPick: (String?, String?) -> Unit,
) {
    val current = if (id == null) nullLabel else macros.find { it.id == id }?.name ?: name ?: "удалён"
    val options = listOf<Pair<String, Pair<String?, String?>>>(nullLabel to (null to null)) +
        macros.map { it.name to (it.id to it.name) }
    Picker(label, current, options) { (i, n) -> onPick(i, n) }
}

@Composable
private fun <T> Picker(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
                }
            }
        }
    }
}
