package hr.beather.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import hr.beather.BuildConfig
import hr.beather.R
import hr.beather.data.CalSource
import hr.beather.data.Calendars
import hr.beather.data.OpenMeteo
import hr.beather.data.Store
import hr.beather.data.Units
import hr.beather.data.WeatherRepository
import hr.beather.widget.WidgetRenderer
import hr.beather.work.RefreshWorker
import kotlinx.coroutines.launch

/** App-wide settings: forecast source, live stations, units, refresh interval. */
class SettingsActivity : AppCompatActivity() {

    private var showShared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    // Rebuilt on every return, so calendars appear right after enabling sync in Outlook.
    override fun onResume() {
        super.onResume()
        build()
        WidgetRenderer.updateAll(this)
    }

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        build(); WidgetRenderer.updateAll(this)
    }

    private fun build() {
        val options = findViewById<LinearLayout>(R.id.options)
        options.removeAllViews()
        val ui = UiKit(this, options)

        ui.section(getString(R.string.updates))
        ui.label(getString(R.string.current_version, BuildConfig.VERSION_NAME))
        ui.button(getString(R.string.check_updates)) { checkForUpdate(this, manual = true) }
        ui.switch(getString(R.string.auto_update_check), Store.autoUpdateCheck(this)) { on -> Store.setAutoUpdateCheck(this, on) }

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

        // ---- Calendars: which row each calendar goes in ----
        ui.section(getString(R.string.calendars))
        if (!Calendars.hasPermission(this)) {
            ui.hint(getString(R.string.calendar_permission_hint))
            ui.button(getString(R.string.allow_calendar)) { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
        } else {
            val cals = Calendars.list(this)
            if (cals.isEmpty()) ui.hint(getString(R.string.no_calendars))
            val labels = listOf(getString(R.string.row_google), getString(R.string.row_outlook), getString(R.string.row_hide))
            val sources = listOf(CalSource.GOOGLE, CalSource.OUTLOOK, CalSource.HIDDEN)
            val hasOutlook = cals.any { Calendars.source(this, it) == CalSource.OUTLOOK }
            if (!hasOutlook) {
                ui.hint(getString(R.string.no_outlook_calendars))
                if (Calendars.outlookInstalled(this)) {
                    ui.button(getString(R.string.open_outlook)) {
                        packageManager.getLaunchIntentForPackage(Calendars.OUTLOOK_PACKAGE)?.let { startActivity(it) }
                    }
                }
                ui.button(getString(R.string.open_account_sync)) {
                    runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
                }
            }
            val shared = cals.count { !it.isOwn }
            if (shared > 0) {
                ui.switch(getString(R.string.show_shared_calendars, shared), showShared) { on ->
                    showShared = on; build()
                }
            }
            cals.filter { it.isOwn || showShared }.forEach { cal ->
                ui.label("${cal.name}  ·  ${cal.account}")
                ui.hint(getString(R.string.calendar_info, cal.accountType, Calendars.countUpcoming(this, cal.id)))
                ui.choice(labels, sources.indexOf(Calendars.source(this, cal))) { i ->
                    Store.setCalendarSource(this, cal.id, sources[i])
                    WidgetRenderer.updateAll(this)
                }
            }
        }
        ui.hint(getString(R.string.outlook_sync_hint))
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
