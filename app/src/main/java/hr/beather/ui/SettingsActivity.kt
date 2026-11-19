package hr.beather.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import hr.beather.R
import hr.beather.data.OpenMeteo
import hr.beather.data.Store
import hr.beather.data.Units
import hr.beather.data.WeatherRepository
import hr.beather.widget.WidgetRenderer
import hr.beather.work.RefreshWorker
import kotlinx.coroutines.launch

/** App-wide settings: forecast source, live stations, units, refresh interval. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val ui = UiKit(this, findViewById<LinearLayout>(R.id.options))

        ui.section(getString(R.string.forecast_model))
        val models = OpenMeteo.MODELS
        ui.choice(models.map { it.label }, models.indexOfFirst { it.id == Store.model(this) }.coerceAtLeast(0)) { i ->
            Store.setModel(this, models[i].id)
            refetchAll()
        }
        ui.hint(getString(R.string.model_hint))

        ui.section(getString(R.string.live_data))
        ui.switch(getString(R.string.use_stations), Store.useStations(this)) { on ->
            Store.setUseStations(this, on)
            refetchAll()
        }
        ui.hint(getString(R.string.stations_hint))

        ui.section(getString(R.string.units))
        ui.label(getString(R.string.temperature))
        ui.choice(listOf("°C", "°F"), if (Store.fahrenheit(this)) 1 else 0) { i ->
            Store.setFahrenheit(this, i == 1); unitsChanged()
        }
        ui.label(getString(R.string.wind_speed))
        val winds = listOf(Units.WIND_KMH, Units.WIND_MS, Units.WIND_KN, Units.WIND_BFT)
        ui.choice(listOf("km/h", "m/s", getString(R.string.knots), "Beaufort"), winds.indexOf(Store.windUnit(this)).coerceAtLeast(0)) { i ->
            Store.setWindUnit(this, winds[i]); unitsChanged()
        }

        ui.section(getString(R.string.refresh_every))
        val intervals = listOf(15, 30, 60, 120)
        ui.choice(intervals.map { getString(R.string.minutes, it) }, intervals.indexOf(Store.refreshMinutes(this)).coerceAtLeast(1)) { i ->
            Store.setRefreshMinutes(this, intervals[i])
            RefreshWorker.reschedule(this)
        }
        ui.hint(getString(R.string.refresh_hint))
    }

    private fun unitsChanged() {
        Units.load(this)
        WidgetRenderer.updateAll(this)
    }

    private fun refetchAll() {
        val app = applicationContext
        lifecycleScope.launch {
            var failed = 0
            for (loc in Store.locations(app)) {
                if (runCatching { WeatherRepository.refresh(app, loc) }.isFailure) failed++
            }
            WidgetRenderer.updateAll(app)
            Toast.makeText(app, if (failed == 0) R.string.refreshed else R.string.refresh_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
