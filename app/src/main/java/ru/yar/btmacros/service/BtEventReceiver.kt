package ru.yar.btmacros.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import ru.yar.btmacros.App

/** Ловит подключение/отключение любого Bluetooth-устройства и передаёт в макросы. */
class BtEventReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) return

        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return

        val app = App.instance
        // Сначала поднимаем сервис, чтобы процесс не убили во время паузы макроса.
        if (app.monitoringEnabled && !MonitorService.running.value) MonitorService.start(context)

        val name = try { device.name } catch (_: SecurityException) { null }
        app.bt.onAclEvent(device.address, name, action == BluetoothDevice.ACTION_ACL_CONNECTED)
    }
}
