package hr.beather.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import hr.beather.R
import hr.beather.data.Calendars
import hr.beather.data.Store
import hr.beather.data.WeatherRepository
import hr.beather.data.WidgetPrefs
import hr.beather.widget.WidgetRenderer
import hr.beather.work.RefreshWorker
import kotlinx.coroutines.launch

/**
 * Per-widget designer: location, what to show, clock, background, transparency,
 * corners, colours, sizes and tap behaviour – with a live preview on top.
 * Opens when a widget is placed, on "reconfigure", or from the app's Widgets menu.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var prefs: WidgetPrefs
    private var locationId: String? = null
    private var compactPreview = false

    private lateinit var previewFrame: FrameLayout
    private lateinit var options: LinearLayout

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        buildOptionsKeepScroll(); updatePreview()
    }

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
        previewFrame = findViewById(R.id.preview_frame)
        options = findViewById(R.id.options)

        prefs = Store.widgetPrefs(this, widgetId)
        locationId = Store.widgetLocation(this, widgetId)?.id

        findViewById<Button>(R.id.config_save).setOnClickListener { save() }
        buildOptions()
        updatePreview()
        ensureForecast()
    }

    private fun update(change: WidgetPrefs.() -> WidgetPrefs) {
        prefs = prefs.change()
        updatePreview()
    }

    private fun ensureForecast() {
        val loc = locationId?.let { Store.location(this, it) } ?: return
        if (WeatherRepository.cached(this, loc) != null) return
        lifecycleScope.launch {
            runCatching { WeatherRepository.refresh(this@WidgetConfigActivity, loc) }
            updatePreview()
        }
    }

    private fun updatePreview() {
        previewFrame.removeAllViews()
        val loc = locationId?.let { Store.location(this, it) }
        val rv = WidgetRenderer.preview(this, loc, prefs, compactPreview)
        val view = rv.apply(applicationContext, previewFrame)
        val w = if (compactPreview) dp(170) else FrameLayout.LayoutParams.MATCH_PARENT
        previewFrame.addView(view, FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // Swallow taps so the preview doesn't launch anything.
        previewFrame.addView(View(this).apply { isClickable = true }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildOptions() {
        options.removeAllViews()
        val ui = UiKit(this, options)
        val p = prefs

        // ---- Location ----
        ui.section(getString(R.string.cfg_location))
        val locations = Store.locations(this)
        val names = locations.map { if (it.fromGps) "📍 ${it.name}" else it.name } + getString(R.string.add_location_chip)
        ui.choice(names, locations.indexOfFirst { it.id == locationId }.coerceAtLeast(0)) { i ->
            if (i < locations.size) {
                locationId = locations[i].id
                updatePreview(); ensureForecast()
            } else {
                showAddLocationDialog(this) { loc ->
                    Store.addLocation(this, loc)
                    locationId = loc.id
                    buildOptions(); updatePreview(); ensureForecast()
                }
            }
        }

        // ---- Presets ----
        ui.section(getString(R.string.cfg_presets))
        ui.hint(getString(R.string.cfg_presets_hint))
        ui.choice(
            listOf(getString(R.string.preset_clock), getString(R.string.preset_full), getString(R.string.preset_minimal)), -1,
        ) { i ->
            prefs = when (i) {
                0 -> WidgetPrefs.PRESET_CLOCK
                1 -> WidgetPrefs.PRESET_FULL
                else -> WidgetPrefs.PRESET_MINIMAL
            }
            buildOptions(); updatePreview()
        }
        ui.choice(listOf(getString(R.string.preview_normal), getString(R.string.preview_small)), if (compactPreview) 1 else 0) {
            compactPreview = it == 1; updatePreview()
        }

        // ---- Content ----
        ui.section(getString(R.string.cfg_show))
        ui.label(getString(R.string.position))
        ui.choice(resources.getStringArray(R.array.positions).toList(), p.vAlign) { i -> update { copy(vAlign = i) } }
        ui.switch(getString(R.string.show_location), p.showLocation) { on -> update { copy(showLocation = on) } }
        ui.switch(getString(R.string.show_clock), p.showClock) { on -> update { copy(showClock = on) } }
        ui.switch(getString(R.string.show_date), p.showDate) { on -> update { copy(showDate = on) } }
        ui.switch(getString(R.string.show_alarm), p.showAlarm) { on -> update { copy(showAlarm = on) } }
        ui.switch(getString(R.string.show_description), p.showDescription) { on -> update { copy(showDescription = on) } }
        ui.switch(getString(R.string.show_details), p.showDetails) { on -> update { copy(showDetails = on) } }
        ui.switch(getString(R.string.show_hourly), p.showHourly) { on -> update { copy(showHourly = on) } }
        ui.switch(getString(R.string.show_days), p.showDays) { on -> update { copy(showDays = on) } }
        ui.slider(getString(R.string.days_count), 3, 7, 1, p.days) { v -> update { copy(days = v) } }
        ui.switch(getString(R.string.show_updated), p.showUpdated) { on -> update { copy(showUpdated = on) } }
        ui.switch(getString(R.string.show_source), p.showSource) { on -> update { copy(showSource = on) } }

        // ---- Calendar ----
        ui.section(getString(R.string.cfg_calendar))
        if (!Calendars.hasPermission(this)) {
            ui.hint(getString(R.string.calendar_permission_hint))
            ui.button(getString(R.string.allow_calendar)) { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
        }
        ui.switch(getString(R.string.show_google), p.showGoogle) { on -> update { copy(showGoogle = on) } }
        ui.switch(getString(R.string.show_outlook), p.showOutlook) { on -> update { copy(showOutlook = on) } }
        ui.slider(getString(R.string.cal_days), 1, 14, 1, p.calDays, { getString(R.string.n_days, it) }) { v -> update { copy(calDays = v) } }
        ui.slider(getString(R.string.cal_events), 1, 3, 1, p.calEvents) { v -> update { copy(calEvents = v) } }
        ui.hint(getString(R.string.calendar_assign_hint))

        // ---- Clock ----
        ui.section(getString(R.string.cfg_clock))
        ui.label(getString(R.string.clock_font))
        ui.choice(resources.getStringArray(R.array.clock_fonts).toList(), p.clockFont) { i -> update { copy(clockFont = i) } }
        ui.label(getString(R.string.clock_tap))
        ui.choice(listOf(getString(R.string.tap_alarms), getString(R.string.tap_forecast)), if (p.clockOpensAlarms) 0 else 1) { i ->
            update { copy(clockOpensAlarms = i == 0) }
        }

        // ---- Background ----
        ui.section(getString(R.string.cfg_background))
        ui.choice(listOf(getString(R.string.bg_solid), getString(R.string.bg_sky)), if (p.skyBackground) 1 else 0) { i ->
            update { copy(skyBackground = i == 1) }
        }
        ui.label(getString(R.string.bg_color))
        ui.colors(UiKit.BG_PALETTE, p.bgColor) { c -> update { copy(bgColor = c, skyBackground = false) }; buildOptionsKeepScroll() }
        ui.slider(getString(R.string.opacity), 0, 100, 5, p.opacity, { "$it%" }) { v -> update { copy(opacity = v) } }
        ui.hint(getString(R.string.opacity_hint))
        ui.label(getString(R.string.corners))
        ui.choice(resources.getStringArray(R.array.corner_names).toList(), p.corner) { i -> update { copy(corner = i) } }

        // ---- Text & icons ----
        ui.section(getString(R.string.cfg_text))
        ui.label(getString(R.string.text_color))
        ui.colors(UiKit.TEXT_PALETTE, p.textColor) { c -> update { copy(textColor = c) } }
        ui.label(getString(R.string.accent_color))
        ui.colors(UiKit.ACCENT_PALETTE, p.accentColor) { c -> update { copy(accentColor = c) } }
        ui.switch(getString(R.string.auto_fit), p.autoFit) { on -> update { copy(autoFit = on) } }
        ui.hint(getString(R.string.auto_fit_hint))
        ui.slider(getString(R.string.text_size), 30, 150, 5, p.textScale, { "$it%" }) { v -> update { copy(textScale = v) } }
        ui.slider(getString(R.string.icon_size), 30, 160, 10, p.iconScale, { "$it%" }) { v -> update { copy(iconScale = v) } }

        options.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
    }

    /** Re-draws the controls (e.g. so the Solid/Sky chip follows a colour pick) without jumping to the top. */
    private fun buildOptionsKeepScroll() {
        val scroll = options.parent as? android.widget.ScrollView
        val y = scroll?.scrollY ?: 0
        buildOptions()
        scroll?.post { scroll.scrollTo(0, y) }
    }

    private fun save() {
        val loc = locationId ?: Store.locations(this).firstOrNull()?.id
        loc?.let { Store.setWidgetLocation(this, widgetId, it) }
        Store.setWidgetPrefs(this, widgetId, prefs)
        WidgetRenderer.update(this, AppWidgetManager.getInstance(this), widgetId)
        RefreshWorker.schedulePeriodic(this)
        RefreshWorker.refreshNow(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
