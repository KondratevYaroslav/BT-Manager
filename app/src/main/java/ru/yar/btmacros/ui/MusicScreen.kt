package ru.yar.btmacros.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.yar.btmacros.App

@Composable
fun MusicScreen() {
    val app = App.instance
    val bt = app.bt
    val music = app.music
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val order by music.order.collectAsStateWithLifecycle()
    val auto by music.auto.collectAsStateWithLifecycle()
    val bonded by bt.bonded.collectAsStateWithLifecycle()
    val audio by bt.a2dpConnected.collectAsStateWithLifecycle()
    val current by bt.musicDevice.collectAsStateWithLifecycle()

    val names = bonded.associate { it.address to it.name }
    var addOpen by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Автовыбор устройства для музыки", style = MaterialTheme.typography.titleMedium)
                            Text("При каждом подключении/отключении музыка уходит на самое верхнее из подключённых",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = auto, onCheckedChange = { music.setAuto(it) })
                    }
                    val cur = current
                    Text(
                        "Сейчас музыка: " + when {
                            cur != null -> names[cur] ?: cur
                            audio.isEmpty() -> "на телефоне (аудиоустройств нет)"
                            else -> "неизвестно (система не сообщает)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        scope.launch {
                            val r = music.apply(reason = "вручную")
                            Toast.makeText(ctx,
                                when {
                                    r == null -> "Нет подключённых устройств из списка"
                                    r.ok -> "Готово"
                                    else -> "Не удалось — см. Журнал"
                                }, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Применить сейчас") }
                }
            }
        }

        item {
            Text("Порядок (сверху — главное)", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp))
        }

        if (order.isEmpty()) {
            item { Text("Список пуст. Добавьте аудиоустройства — магнитолу, наушники, колонку.") }
        }

        itemsIndexed(order, key = { _, a -> a }) { i, addr ->
            val on = addr in audio
            val playing = addr == current
            Card(
                Modifier.fillMaxWidth(),
                colors = if (playing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(names[addr] ?: addr, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                playing -> "♪ играет сейчас"
                                on -> "● подключено"
                                else -> "○ не подключено"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { music.move(addr, up = true) }, enabled = i > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, "Выше")
                    }
                    IconButton(onClick = { music.move(addr, up = false) }, enabled = i < order.lastIndex) {
                        Icon(Icons.Default.KeyboardArrowDown, "Ниже")
                    }
                    IconButton(onClick = { music.remove(addr) }) { Icon(Icons.Default.Delete, "Убрать") }
                }
            }
        }

        item {
            val candidates = bonded.filter { it.address !in order }
            Box {
                OutlinedButton(onClick = { addOpen = true }, enabled = candidates.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Text(" Добавить устройство")
                }
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    candidates.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(if (d.kind.isNotEmpty()) "${d.name}  ·  ${d.kind}" else d.name) },
                            onClick = { addOpen = false; music.add(d.address) },
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Переключение работает, если прошивка разрешает выбирать устройство для музыки " +
                    "(проверьте «Диагностику» на вкладке «Устройства» и кнопку «Применить сейчас»). " +
                    "Результат каждой попытки пишется в Журнал.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
