package ru.yar.btmacros

import android.app.Application
import ru.yar.btmacros.bt.BtController
import ru.yar.btmacros.data.EventLog
import ru.yar.btmacros.data.MacroStore
import ru.yar.btmacros.engine.MacroEngine
import ru.yar.btmacros.engine.MusicPriority

class App : Application() {
    lateinit var bt: BtController
        private set
    lateinit var store: MacroStore
        private set
    lateinit var engine: MacroEngine
        private set
    lateinit var music: MusicPriority
        private set

    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** Держать ли фоновый мониторинг (постоянное уведомление). */
    var monitoringEnabled: Boolean
        get() = settings.getBoolean("monitoring", true)
        set(value) = settings.edit().putBoolean("monitoring", value).apply()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        store = MacroStore(this)
        bt = BtController(this)
        music = MusicPriority(this, bt)
        engine = MacroEngine(this, bt, store, music)
        bt.onEvent = engine::onEvent
        bt.onAudioChanged = music::onAudioChanged
        bt.start()
        EventLog.add("Приложение запущено")
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
