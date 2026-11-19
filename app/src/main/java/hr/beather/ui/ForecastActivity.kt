package hr.beather.ui

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import hr.beather.R
import hr.beather.data.Day
import hr.beather.data.Forecast
import hr.beather.data.Hour
import hr.beather.data.OpenMeteo
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.data.Units
import hr.beather.data.WeatherCodes
import hr.beather.data.WeatherCodes.deg
import hr.beather.data.WeatherRepository
import hr.beather.widget.WidgetRenderer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Full week view. Opened from the app or by tapping the widget (a tapped day opens expanded). */
class ForecastActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOCATION = "location"
        const val EXTRA_DAY = "day"
    }

    private lateinit var loc: SavedLocation
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val locale: Locale get() = Locale.getDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        content = findViewById(R.id.content)
        scroll = findViewById(R.id.scroll)

        val id = intent.getStringExtra(EXTRA_LOCATION)
        loc = Store.locations(this).let { all -> all.firstOrNull { it.id == id } ?: all.firstOrNull() } ?: run {
            finish(); return
        }
        title = loc.name

        val cached = WeatherRepository.cached(this, loc)
        if (cached != null) render(cached, intent.getIntExtra(EXTRA_DAY, 0))
        if (cached == null || WeatherRepository.cacheAgeMs(this, loc) > 30 * 60 * 1000L) refresh(cached == null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.refresh).setIcon(R.drawable.ic_refresh)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { refresh(true); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh(announce: Boolean) {
        lifecycleScope.launch {
            val result = runCatching { WeatherRepository.refresh(this@ForecastActivity, loc) }
            result.getOrNull()?.let {
                render(it, intent.getIntExtra(EXTRA_DAY, 0))
                WidgetRenderer.updateAll(this@ForecastActivity)
            }
            if (announce) Toast.makeText(
                this@ForecastActivity,
                if (result.isSuccess) R.string.refreshed else R.string.refresh_failed, Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render(fc: Forecast, selectedDay: Int) {
        content.removeAllViews()
        renderHeader(fc)

        val days = fc.upcomingDays()
        val hours = fc.upcomingHours()
        var selectedView: View? = null
        days.forEachIndexed { i, d ->
            val v = dayRow(d, i, hours.filter { it.time.toLocalDate() == d.date }, expanded = i == selectedDay)
            content.addView(v)
            if (i == selectedDay) selectedView = v
        }

        content.addView(TextView(this).apply {
            text = buildString {
                append(getString(R.string.source_line, OpenMeteo.label(fc.model)))
                fc.current.station?.let { append("\n").append(getString(R.string.measured_at, it.name, it.distanceKm.roundToInt())) }
                append("\n").append(getString(R.string.updated_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fc.fetchedAt))))
            }
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
        })

        if (selectedDay > 0) selectedView?.let { v -> scroll.post { scroll.smoothScrollTo(0, v.top - dp(8)) } }
    }

    private fun renderHeader(fc: Forecast) {
        val h = layoutInflater.inflate(R.layout.forecast_header, content, false)
        val c = fc.current
        val today = fc.upcomingDays().firstOrNull()
        h.findViewById<ImageView>(R.id.hdr_icon).setImageResource(WeatherCodes.icon(c.code, c.isDay))
        h.findViewById<TextView>(R.id.hdr_temp).text = deg(c.temp)
        h.findViewById<TextView>(R.id.hdr_desc).text = WeatherCodes.describe(this, c.code)
        h.findViewById<TextView>(R.id.hdr_hilo).text = today?.let { "↑ ${deg(it.max)}   ↓ ${deg(it.min)}" } ?: ""
        h.findViewById<TextView>(R.id.hdr_details).text = buildList {
            c.feelsLike?.let { add(getString(R.string.feels_like, deg(it))) }
            if (c.wind != null) {
                val dir = c.windDir?.let { WeatherCodes.windDir(this@ForecastActivity, it) + " " } ?: ""
                val local = WeatherCodes.adriaticWind(this@ForecastActivity, loc.lat, loc.lon, c.windDir, c.wind)?.let { "$it " } ?: ""
                val gust = c.gusts?.let { " (" + getString(R.string.gusts, Units.windNumber(it)) + ")" } ?: ""
                add("💨 $local$dir${Units.windSpeed(c.wind)}$gust")
            }
            c.humidity?.let { add("💧 $it%") }
        }.joinToString("   ")
        content.addView(h)
    }

    private fun dayName(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.today)
            today.plusDays(1) -> getString(R.string.tomorrow)
            else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
        }
    }

    private fun dayRow(d: Day, index: Int, hours: List<Hour>, expanded: Boolean): View {
        val v = layoutInflater.inflate(R.layout.item_day, content, false)
        v.findViewById<TextView>(R.id.day_name).text = dayName(d.date)
        v.findViewById<TextView>(R.id.day_date).text = d.date.format(DateTimeFormatter.ofPattern(getString(R.string.short_date_format), locale))
        v.findViewById<ImageView>(R.id.day_icon).setImageResource(WeatherCodes.icon(d.code, true))
        v.findViewById<TextView>(R.id.day_desc).text = WeatherCodes.describe(this, d.code)
        v.findViewById<TextView>(R.id.day_extra).text = listOfNotNull(
            d.precip?.takeIf { it >= 0.1 }?.let { "☔ " + String.format(locale, "%.1f mm", it) },
            d.pop?.takeIf { it >= 10 }?.let { "$it%" },
            d.windMax?.let { "💨 " + Units.windSpeed(it) },
        ).joinToString("  ")
        v.findViewById<TextView>(R.id.day_max).text = deg(d.max)
        v.findViewById<TextView>(R.id.day_min).text = deg(d.min)

        val hourRow = v.findViewById<LinearLayout>(R.id.day_hours)
        val hourScroll = v.findViewById<View>(R.id.day_hours_scroll)
        if (hours.isEmpty()) {
            v.findViewById<View>(R.id.day_chevron).visibility = View.INVISIBLE
        } else {
            hours.forEach { hourRow.addView(hourCell(it)) }
        }
        fun setExpanded(on: Boolean) {
            hourScroll.visibility = if (on && hours.isNotEmpty()) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.day_chevron).rotation = if (on) 180f else 0f
        }
        setExpanded(expanded)
        v.findViewById<View>(R.id.day_header).setOnClickListener { setExpanded(hourScroll.visibility != View.VISIBLE) }
        v.tag = index
        return v
    }

    private fun hourCell(h: Hour): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun text(s: String, size: Float, color: Int = 0xFFFFFFFF.toInt()) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        }
        col.addView(text("%02d".format(h.time.hour), 12f, 0xB3FFFFFF.toInt()))
        col.addView(ImageView(this).apply { setImageResource(WeatherCodes.icon(h.code, h.isDay)) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        col.addView(text(deg(h.temp), 14f))
        val rain = when {
            (h.precip ?: 0.0) >= 0.1 -> String.format(locale, "%.1f", h.precip)
            (h.pop ?: 0) >= 10 -> "${h.pop}%"
            else -> " "
        }
        col.addView(text(rain, 11f, 0xFF90CAF9.toInt()))
        return col
    }
}
