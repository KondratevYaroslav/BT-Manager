package ru.yar.btmacros.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ru.yar.btmacros.App
import ru.yar.btmacros.R
import ru.yar.btmacros.data.EventLog
import ru.yar.btmacros.engine.MediaKeys
import ru.yar.btmacros.engine.Source
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.MediaKey
import ru.yar.btmacros.service.MonitorService

/** Виджет «кнопка макроса»: название, статус, нажатие — запуск/остановка. */
class MacroWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) =
        Widgets.updateMacroWidgets(ctx, ids)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Widgets.unbind(ctx, it) }
    }
}

/** Виджет управления аудио: назад, пауза/играть, вперёд, приоритет музыки. */
class MediaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, Widgets.mediaViews(ctx)) }
    }
}

/** Обработка нажатий на виджеты. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.instance
        when (intent.action) {
            Widgets.ACTION_TOGGLE -> {
                val id = intent.getStringExtra(Widgets.EXTRA_MACRO) ?: return
                val m = app.store.macros.value.find { it.id == id } ?: return
                if (id in app.engine.running.value) {
                    app.engine.stop(id)
                } else {
                    if (app.monitoringEnabled && !MonitorService.running.value) MonitorService.start(ctx)
                    app.engine.run(m, null, Source.MANUAL)
                }
            }
            Widgets.ACTION_MEDIA -> {
                val key = runCatching { MediaKey.valueOf(intent.getStringExtra(Widgets.EXTRA_KEY) ?: "") }
                    .getOrNull() ?: return
                MediaKeys.send(ctx, key)
            }
            Widgets.ACTION_PRIORITY -> app.music.applyAsync("виджет")
            Widgets.ACTION_PIN_DONE -> {
                val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val macroId = intent.getStringExtra(Widgets.EXTRA_MACRO) ?: return
                if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Widgets.bind(ctx, wid, macroId)
                    Widgets.updateMacroWidgets(ctx, intArrayOf(wid))
                    EventLog.add("Виджет добавлен на экран")
                }
            }
        }
    }
}

object Widgets {
    const val ACTION_TOGGLE = "ru.yar.btmacros.widget.TOGGLE"
    const val ACTION_MEDIA = "ru.yar.btmacros.widget.MEDIA"
    const val ACTION_PRIORITY = "ru.yar.btmacros.widget.PRIORITY"
    const val ACTION_PIN_DONE = "ru.yar.btmacros.widget.PIN_DONE"
    const val EXTRA_MACRO = "macro"
    const val EXTRA_KEY = "key"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun bind(ctx: Context, widgetId: Int, macroId: String) =
        prefs(ctx).edit().putString("w$widgetId", macroId).apply()

    fun unbind(ctx: Context, widgetId: Int) = prefs(ctx).edit().remove("w$widgetId").apply()

    private fun macroIdFor(ctx: Context, widgetId: Int) = prefs(ctx).getString("w$widgetId", null)

    fun updateMacroWidgets(ctx: Context, ids: IntArray? = null) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val all = ids ?: mgr.getAppWidgetIds(ComponentName(ctx, MacroWidget::class.java))
        if (all.isEmpty()) return
        val app = App.instance
        val macros = app.store.macros.value
        val running = app.engine.running.value
        val status = app.engine.status.value
        for (wid in all) {
            val macroId = macroIdFor(ctx, wid)
            val m: Macro? = macros.find { it.id == macroId }
            val v = RemoteViews(ctx.packageName, R.layout.widget_macro)
            if (m == null) {
                v.setTextViewText(R.id.w_name, if (macroId == null) "Выберите макрос" else "Макрос удалён")
                v.setTextViewText(R.id.w_status, "нажмите для настройки")
                v.setInt(R.id.w_root, "setBackgroundResource", R.drawable.widget_bg)
                val cfg = Intent(ctx, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(
                    ctx, wid, cfg, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                val isRunning = m.id in running
                v.setTextViewText(R.id.w_name, m.name)
                v.setTextViewText(R.id.w_status, when {
                    isRunning -> "▶ " + (status[m.id] ?: "выполняется")
                    !m.enabled -> "выключен · нажмите для запуска"
                    else -> status[m.id] ?: "нажмите для запуска"
                })
                v.setInt(R.id.w_root, "setBackgroundResource",
                    if (isRunning) R.drawable.widget_bg_active else R.drawable.widget_bg)
                val click = Intent(ctx, WidgetActionReceiver::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(EXTRA_MACRO, m.id)
                v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getBroadcast(
                    ctx, wid, click, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            mgr.updateAppWidget(wid, v)
        }
    }

    fun mediaViews(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_media)
        fun media(viewId: Int, key: MediaKey, req: Int) {
            val i = Intent(ctx, WidgetActionReceiver::class.java).setAction(ACTION_MEDIA).putExtra(EXTRA_KEY, key.name)
            v.setOnClickPendingIntent(viewId, PendingIntent.getBroadcast(
                ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        media(R.id.w_prev, MediaKey.PREV, 9001)
        media(R.id.w_play, MediaKey.PLAY_PAUSE, 9002)
        media(R.id.w_next, MediaKey.NEXT, 9003)
        val pr = Intent(ctx, WidgetActionReceiver::class.java).setAction(ACTION_PRIORITY)
        v.setOnClickPendingIntent(R.id.w_priority, PendingIntent.getBroadcast(
            ctx, 9004, pr, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return v
    }

    /** Попросить лаунчер добавить виджет макроса на рабочий стол. */
    fun requestPinMacro(ctx: Context, m: Macro): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        val cb = Intent(ctx, WidgetActionReceiver::class.java)
            .setAction(ACTION_PIN_DONE)
            .putExtra(EXTRA_MACRO, m.id)
        val pi = PendingIntent.getBroadcast(
            ctx, m.id.hashCode(), cb, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return mgr.requestPinAppWidget(ComponentName(ctx, MacroWidget::class.java), null, pi)
    }

    fun requestPinMedia(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(ctx, MediaWidget::class.java), null, null)
    }
}
