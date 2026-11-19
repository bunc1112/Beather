package hr.beather.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import hr.beather.R
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.widget.WidgetRenderer
import hr.beather.work.RefreshWorker

/** Shown when a Beather widget is placed (or reconfigured): pick its location. */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }

        setContentView(R.layout.activity_config)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        val locations = Store.locations(this)
        val list = findViewById<ListView>(R.id.config_list)
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            locations.map { if (it.region.isBlank()) it.name else "${it.name} — ${it.region}" },
        )
        list.setOnItemClickListener { _, _, pos, _ -> choose(locations[pos]) }

        findViewById<Button>(R.id.config_add).setOnClickListener {
            showAddLocationDialog(this) { loc ->
                Store.addLocation(this, loc)
                choose(loc)
            }
        }
    }

    private fun choose(loc: SavedLocation) {
        Store.setWidgetLocation(this, widgetId, loc.id)
        WidgetRenderer.update(this, AppWidgetManager.getInstance(this), widgetId)
        RefreshWorker.schedulePeriodic(this)
        RefreshWorker.refreshNow(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
