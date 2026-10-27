package ru.yar.btmacros.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yar.btmacros.App
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.data.EventLog
import ru.yar.btmacros.service.MonitorService

@Composable
fun DevicesScreen() {
    val app = App.instance
    val bt = app.bt
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by bt.enabled.collectAsStateWithLifecycle()
    val bonded by bt.bonded.collectAsStateWithLifecycle()
    val connected by bt.connected.collectAsStateWithLifecycle()
    val monitoring by MonitorService.running.collectAsStateWithLifecycle()
    val problem by bt.problem.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf<String?>(null) }
    var diag by remember { mutableStateOf<String?>(null) }

    fun op(d: BtController.Device, connect: Boolean) {
        scope.launch {
            busy = d.address
            val r = withContext(Dispatchers.IO) {
                if (connect) bt.connect(d.address) else bt.disconnect(d.address)
            }
            val what = if (connect) "Подключить" else "Отключить"
            EventLog.add("Вручную: $what ${d.name} → ${if (r.ok) "OK" else "НЕ УДАЛОСЬ"} (${r.details})")
            Toast.makeText(ctx, if (r.ok) "$what: команда принята" else "$what: не удалось — см. Журнал",
                Toast.LENGTH_SHORT).show()
            busy = null
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow("Bluetooth", if (enabled) "включён" else "выключен", enabled) { on ->
                        if (!bt.setBluetooth(on)) {
                            Toast.makeText(ctx, "Система не дала переключить — открываю настройки", Toast.LENGTH_SHORT).show()
                            openScreen(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    }
                    SwitchRow(
                        "Фоновый мониторинг",
                        if (monitoring) "макросы срабатывают в фоне" else "макросы работают, пока открыто приложение",
                        monitoring,
                    ) { on ->
                        app.monitoringEnabled = on
                        if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openScreen(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Text("Настройки BT")
                        }
                        OutlinedButton(onClick = { diag = bt.diagnose() }) { Text("Диагностика") }
                    }
                    TextButton(onClick = { openBatterySettings(ctx) }) {
                        Text("Разрешить работу в фоне (батарея)")
                    }
                }
            }
        }

        item {
            Text(
                "Сохранённые устройства (${bonded.size}), подключено: ${connected.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!enabled) {
            item { Text("Bluetooth выключен — список и состояние могут быть неполными.") }
        }

        problem?.let { p ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Не вижу устройства: $p", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Откройте настройки приложения → Разрешения и разрешите «Устройства поблизости» " +
                                "(на Xiaomi также может быть пункт «Bluetooth» в разделе «Другие разрешения»). " +
                                "Затем вернитесь сюда и нажмите «Обновить».",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                openScreen(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${ctx.packageName}")))
                            }) { Text("Настройки приложения") }
                            OutlinedButton(onClick = { bt.ensureProxies(); bt.refresh() }) { Text("Обновить") }
                        }
                    }
                }
            }
        }

        items(bonded, key = { it.address }) { d ->
            val isOn = d.address in connected
            Card(
                Modifier.fillMaxWidth(),
                colors = if (isOn) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(d.address, d.kind).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (busy == d.address) "выполняю…" else if (isOn) "● подключено" else "○ не подключено",
                            color = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { op(d, true) }, enabled = busy == null) { Text("Подключить") }
                        TextButton(onClick = { op(d, false) }, enabled = busy == null) { Text("Отключить") }
                    }
                }
            }
        }
    }

    diag?.let { text ->
        AlertDialog(
            onDismissRequest = { diag = null },
            confirmButton = { TextButton(onClick = { diag = null }) { Text("OK") } },
            title = { Text("Диагностика") },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState())) },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun openScreen(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "Не нашёл нужный экран настроек", Toast.LENGTH_SHORT).show()
    }
}

@android.annotation.SuppressLint("BatteryLife")
private fun openBatterySettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        )
    } catch (_: Exception) {
        openScreen(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
