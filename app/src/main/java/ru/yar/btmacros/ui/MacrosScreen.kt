package ru.yar.btmacros.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.yar.btmacros.App
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.describe

@Composable
fun MacrosScreen(onEdit: (Macro) -> Unit) {
    val app = App.instance
    val macros by app.store.macros.collectAsStateWithLifecycle()
    val running by app.engine.running.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Macro?>(null) }

    if (macros.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Макросов пока нет", style = MaterialTheme.typography.titleMedium)
            Text(
                "Нажмите «Макрос» — откроется шаблон «подключилось → пауза 10 с → отключить». " +
                    "Останется выбрать устройство.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(macros, key = { it.id }) { m ->
                MacroCard(
                    m = m,
                    isRunning = m.id in running,
                    onToggle = { app.store.setEnabled(m.id, it) },
                    onRun = { if (m.id in running) app.engine.stop(m.id) else app.engine.run(m, null, manual = true) },
                    onEdit = { onEdit(m) },
                    onDelete = { toDelete = m },
                )
            }
        }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить макрос?") },
            text = { Text("«${m.name}»") },
            confirmButton = {
                TextButton(onClick = { app.engine.stop(m.id); app.store.delete(m.id); toDelete = null }) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun MacroCard(
    m: Macro,
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = m.enabled, onCheckedChange = onToggle)
            }
            Text("Когда: " + m.trigger.describe(), style = MaterialTheme.typography.bodyMedium)
            if (m.conditionAddress != null) {
                Text("Если подключено: " + (m.conditionName ?: m.conditionAddress),
                    style = MaterialTheme.typography.bodyMedium)
            }
            m.actions.forEachIndexed { i, a ->
                Text("${i + 1}. ${a.describe()}", style = MaterialTheme.typography.bodySmall)
            }
            if (m.actions.isEmpty()) Text("Нет действий", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRunning) {
                    Text("выполняется…", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRun) {
                        Icon(if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow, null)
                        Text(if (isRunning) " Стоп" else " Запустить")
                    }
                    TextButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Изменить") }
                    TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
                }
            }
        }
    }
}
