package ru.yar.btmacros.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.yar.btmacros.App

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (App.instance.monitoringEnabled) MonitorService.start(context)
    }
}
