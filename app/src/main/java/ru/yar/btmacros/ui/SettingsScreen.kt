package ru.yar.btmacros.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yar.btmacros.App
import ru.yar.btmacros.engine.Launcher
import ru.yar.btmacros.engine.Mailer
import ru.yar.btmacros.widget.Widgets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val aaOn by App.instance.aa.connected.collectAsStateWithLifecycle()

    val saved = remember { Mailer.load(ctx) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var ssl by remember { mutableStateOf(saved.ssl) }
    var user by remember { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    var from by remember { mutableStateOf(saved.from) }
    var to by remember { mutableStateOf(saved.defaultTo) }
    var testing by remember { mutableStateOf(false) }
    var overlayOk by remember { mutableStateOf(Launcher.canStartFromBackground(ctx)) }

    fun config() = Mailer.Config(host, port.toIntOrNull() ?: 465, ssl, user, password, from, to)

    fun preset(h: String) {
        host = h; port = "465"; ssl = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = { Mailer.save(ctx, config()); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- Разрешения ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Запуск приложений, таймеров, будильников", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android не даёт открывать экраны из фона. Разрешите «Поверх других окон», " +
                            "а на Xiaomi ещё «Другие разрешения → Отображать всплывающие окна, " +
                            "когда приложение работает в фоне». Без этого вместо запуска придёт уведомление-кнопка.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Поверх других окон: " + if (overlayOk) "разрешено ✓" else "не разрешено",
                        color = if (overlayOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")))
                            }
                        }) { Text("Поверх окон") }
                        OutlinedButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${ctx.packageName}")))
                            }
                        }) { Text("Разрешения") }
                    }
                    OutlinedButton(onClick = { overlayOk = Launcher.canStartFromBackground(ctx) }) {
                        Text("Проверить ещё раз")
                    }
                }
            }

            // ---------- Android Auto ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Android Auto", style = MaterialTheme.typography.titleMedium)
                    Text("Сейчас: " + if (aaOn) "подключён" else "не подключён")
                    Text(
                        "Триггеры и условия «Android Auto» срабатывают, когда телефон начинает или " +
                            "заканчивает показ Android Auto на экране машины.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------- Виджеты ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Виджеты", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Виджет макроса добавляется кнопкой на карточке макроса или через меню виджетов " +
                            "рабочего стола («BT Макросы → Макрос»).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        if (!Widgets.requestPinMedia(ctx)) {
                            Toast.makeText(ctx, "Лаунчер не поддерживает — добавьте через меню виджетов",
                                Toast.LENGTH_LONG).show()
                        }
                    }) { Text("Добавить виджет «Управление аудио»") }
                }
            }

            // ---------- Почта ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Почта для действия «Отправить письмо»", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Нужен «пароль приложения» (не основной пароль): Яндекс ID → Безопасность → " +
                            "Пароли приложений; Gmail — myaccount.google.com/apppasswords; Mail.ru — " +
                            "Безопасность → Пароли для внешних приложений.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = host == "smtp.yandex.ru", onClick = { preset("smtp.yandex.ru") },
                            label = { Text("Яндекс") })
                        FilterChip(selected = host == "smtp.gmail.com", onClick = { preset("smtp.gmail.com") },
                            label = { Text("Gmail") })
                        FilterChip(selected = host == "smtp.mail.ru", onClick = { preset("smtp.mail.ru") },
                            label = { Text("Mail.ru") })
                    }
                    Field("SMTP-сервер", host) { host = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Порт") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Text("SSL")
                        Switch(checked = ssl, onCheckedChange = { ssl = it })
                    }
                    Field("Логин (адрес почты)", user, KeyboardType.Email) { user = it }
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль приложения") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Field("От кого (пусто = логин)", from, KeyboardType.Email) { from = it }
                    Field("Кому по умолчанию (пусто = себе)", to, KeyboardType.Email) { to = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            Mailer.save(ctx, config())
                            Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                        }) { Text("Сохранить") }
                        OutlinedButton(enabled = !testing, onClick = {
                            Mailer.save(ctx, config())
                            testing = true
                            scope.launch {
                                val err = withContext(Dispatchers.IO) {
                                    Mailer.send(config(), "", "BT Макросы: проверка", "Почта настроена правильно.")
                                }
                                testing = false
                                Toast.makeText(ctx, err ?: "Письмо отправлено ✓", Toast.LENGTH_LONG).show()
                            }
                        }) { Text(if (testing) "Отправляю…" else "Тестовое письмо") }
                    }
                    Text(
                        "В теме и тексте письма и уведомлений можно писать {macro}, {device}, {time}, {date}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}
