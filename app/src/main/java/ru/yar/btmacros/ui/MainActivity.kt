package ru.yar.btmacros.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.yar.btmacros.App
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.newMacroTemplate
import ru.yar.btmacros.service.MonitorService

class MainActivity : ComponentActivity() {
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val bt = App.instance.bt
            ru.yar.btmacros.data.EventLog.add(
                "Разрешение «Устройства поблизости»: " + if (result.values.all { it }) "выдано" else "отклонено"
            )
            bt.ensureProxies()
            bt.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = App.instance
        app.bt.start()
        if (app.monitoringEnabled) MonitorService.start(this)
        setContent { BtTheme { MainScreen() } }
        if (!app.bt.hasPermission()) {
            permissionRequest.launch(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    override fun onResume() {
        super.onResume()
        App.instance.bt.ensureProxies()
        App.instance.bt.refresh()
    }
}

@Composable
fun BtTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Macro?>(null) }

    val current = editing
    if (current != null) {
        BackHandler { editing = null }
        MacroEditor(
            initial = current,
            onSave = { App.instance.store.upsert(it); editing = null },
            onCancel = { editing = null },
        )
        return
    }

    val titles = listOf("Устройства", "Макросы", "Музыка", "Журнал")
    Scaffold(
        topBar = { TopAppBar(title = { Text(titles[tab]) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text(titles[0]) })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Build, null) }, label = { Text(titles[1]) })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Star, null) }, label = { Text(titles[2]) })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text(titles[3]) })
            }
        },
        floatingActionButton = {
            if (tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { editing = newMacroTemplate() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Макрос") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> DevicesScreen()
                1 -> MacrosScreen(onEdit = { editing = it })
                2 -> MusicScreen()
                else -> LogScreen()
            }
        }
    }
}
