package ru.yar.btmacros.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.yar.btmacros.App
import ru.yar.btmacros.model.describe
import ru.yar.btmacros.ui.BtTheme

/** Выбор макроса для виджета. */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val macros = App.instance.store.macros.value

        setContent {
            BtTheme {
                Scaffold(topBar = { TopAppBar(title = { Text("Макрос для виджета") }) }) { pad ->
                    if (macros.isEmpty()) {
                        Text("Сначала создайте макрос в приложении.", Modifier.padding(pad).padding(16.dp))
                    }
                    LazyColumn(
                        Modifier.padding(pad),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(macros, key = { it.id }) { m ->
                            Card(Modifier.fillMaxWidth().clickable {
                                Widgets.bind(this@WidgetConfigActivity, widgetId, m.id)
                                Widgets.updateMacroWidgets(this@WidgetConfigActivity, intArrayOf(widgetId))
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                                    Text(m.trigger.describe(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
