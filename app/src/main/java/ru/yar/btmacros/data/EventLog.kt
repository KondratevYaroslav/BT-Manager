package ru.yar.btmacros.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал событий и действий, который видно во вкладке «Журнал». */
object EventLog {
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun add(message: String) {
        Log.i("BtMacros", message)
        _lines.value = (listOf("${fmt.format(Date())}  $message") + _lines.value).take(500)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }
}
