package ru.yar.btmacros.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.yar.btmacros.App
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.model.ActionType
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.MacroAction
import ru.yar.btmacros.model.TRIGGER_DEVICE
import ru.yar.btmacros.model.TriggerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditor(initial: Macro, onSave: (Macro) -> Unit, onCancel: () -> Unit) {
    val bonded by App.instance.bt.bonded.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(initial.name) }
    var trigger by remember { mutableStateOf(initial.trigger) }
    var condAddr by remember { mutableStateOf(initial.conditionAddress) }
    var condName by remember { mutableStateOf(initial.conditionName) }
    var cooldown by remember { mutableStateOf(initial.cooldownSec.toString()) }
    val actions = remember { mutableStateListOf<MacroAction>().apply { addAll(initial.actions) } }

    fun save() = onSave(
        initial.copy(
            name = name.ifBlank { "Макрос" },
            trigger = trigger,
            conditionAddress = condAddr,
            conditionName = condName,
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
            item {
                DevicePicker("Только если при этом подключено", condAddr, condName, bonded,
                    nullLabel = "Без условия") { a, n -> condAddr = a; condName = n }
            }
            item {
                OutlinedTextField(
                    value = cooldown, onValueChange = { cooldown = it.filter(Char::isDigit).take(4) },
                    label = { Text("Не повторять чаще, чем раз в N секунд") },
                    supportingText = { Text("Защита от зацикливания, если макрос сам вызывает своё событие") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Section("Что делать (по порядку)") }
            itemsIndexed(actions) { i, a ->
                ActionEditor(
                    index = i, action = a, bonded = bonded,
                    canUp = i > 0, canDown = i < actions.lastIndex,
                    onChange = { actions[i] = it },
                    onUp = { actions.add(i - 1, actions.removeAt(i)) },
                    onDown = { actions.add(i + 1, actions.removeAt(i)) },
                    onDelete = { actions.removeAt(i) },
                )
            }
            item {
                AddActionButton { t ->
                    actions.add(
                        MacroAction(
                            type = t,
                            deviceAddress = if (t.needsDevice) TRIGGER_DEVICE else null,
                            seconds = when (t) {
                                ActionType.DELAY -> 10
                                ActionType.BT_RESTART -> 3
                                else -> 15
                            },
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ActionEditor(
    index: Int,
    action: MacroAction,
    bonded: List<BtController.Device>,
    canUp: Boolean,
    canDown: Boolean,
    onChange: (MacroAction) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp))
                Box(Modifier.weight(1f)) {
                    Picker("Действие", action.type.title, ActionType.entries.map { it.title to it }) { t ->
                        onChange(action.copy(
                            type = t,
                            deviceAddress = if (t.needsDevice) (action.deviceAddress ?: TRIGGER_DEVICE) else action.deviceAddress,
                        ))
                    }
                }
                IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, "Выше") }
                IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, "Ниже") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
            }
            if (action.type.needsDevice) {
                DevicePicker("Устройство", action.deviceAddress, action.deviceName, bonded,
                    nullLabel = "— не выбрано —", allowTrigger = true) { a, n ->
                    onChange(action.copy(deviceAddress = a, deviceName = n))
                }
            }
            if (action.type.needsSeconds) {
                SecondsField(
                    label = when (action.type) {
                        ActionType.DELAY -> "Пауза, секунд"
                        ActionType.BT_RESTART -> "Сколько держать выключенным, секунд"
                        else -> "Ждать не дольше, секунд"
                    },
                    seconds = action.seconds,
                ) { onChange(action.copy(seconds = it)) }
            }
            if (action.type.needsText) {
                OutlinedTextField(
                    value = action.text, onValueChange = { onChange(action.copy(text = it)) },
                    label = { Text("Текст уведомления") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SecondsField(label: String, seconds: Int, onChange: (Int) -> Unit) {
    var text by remember(seconds) { mutableStateOf(seconds.toString()) }
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
private fun AddActionButton(onPick: (ActionType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Text(" Добавить действие")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionType.entries.forEach { t ->
                DropdownMenuItem(text = { Text(t.title) }, onClick = { open = false; onPick(t) })
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
private fun <T> Picker(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
                }
            }
        }
    }
}
