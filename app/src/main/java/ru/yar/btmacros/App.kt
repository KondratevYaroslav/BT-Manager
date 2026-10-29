package ru.yar.btmacros

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.data.EventLog
import ru.yar.btmacros.data.MacroStore
import ru.yar.btmacros.engine.AndroidAutoMonitor
import ru.yar.btmacros.engine.MacroEngine
import ru.yar.btmacros.engine.MusicPriority
import ru.yar.btmacros.model.TriggerType
import ru.yar.btmacros.widget.Widgets

class App : Application() {
    lateinit var bt: BtController
        private set
    lateinit var store: MacroStore
        private set
    lateinit var engine: MacroEngine
        private set
    lateinit var music: MusicPriority
        private set
    lateinit var aa: AndroidAutoMonitor
        private set

    /** Открыт ли сейчас экран приложения (тогда можно запускать другие приложения напрямую). */
    @Volatile var uiVisible = false

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** Держать ли фоновый мониторинг (постоянное уведомление). */
    var monitoringEnabled: Boolean
        get() = settings.getBoolean("monitoring", true)
        set(value) = settings.edit().putBoolean("monitoring", value).apply()

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        store = MacroStore(this)
        bt = BtController(this)
        aa = AndroidAutoMonitor(this)
        music = MusicPriority(this, bt)
        engine = MacroEngine(this, bt, store, music, aa)
        bt.onEvent = { type, address -> engine.onEvent(type, address) }
        bt.onAudioChanged = music::onAudioChanged
        aa.onChange = { on -> engine.onEvent(if (on) TriggerType.AA_CONNECTED else TriggerType.AA_DISCONNECTED) }
        bt.start()
        aa.start()
        EventLog.add("Приложение запущено, сборка ${bt.appVersion()}")

        // Виджеты макросов показывают статус — обновляем при любых изменениях.
        appScope.launch {
            combine(engine.running, engine.status, store.macros) { _, _, _ -> }
                .debounce(300)
                .collect { runCatching { Widgets.updateMacroWidgets(this@App) } }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
