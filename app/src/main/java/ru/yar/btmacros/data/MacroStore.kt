package ru.yar.btmacros.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.yar.btmacros.model.Macro
import ru.yar.btmacros.model.MacroJson

class MacroStore(context: Context) {
    private val prefs = context.getSharedPreferences("macros", Context.MODE_PRIVATE)
    private val _macros = MutableStateFlow(load())
    val macros: StateFlow<List<Macro>> = _macros

    private fun load(): List<Macro> =
        runCatching { MacroJson.listFromJson(prefs.getString("list", "[]") ?: "[]") }
            .getOrDefault(emptyList())

    private fun save(list: List<Macro>) {
        _macros.value = list
        prefs.edit().putString("list", MacroJson.listToJson(list)).apply()
    }

    @Synchronized
    fun upsert(m: Macro) {
        val list = _macros.value
        save(if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m)
    }

    @Synchronized
    fun delete(id: String) = save(_macros.value.filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) {
        _macros.value.find { it.id == id }?.let { upsert(it.copy(enabled = enabled)) }
    }
}
