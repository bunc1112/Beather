package hr.beather.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hr.beather.R
import hr.beather.data.Forecast
import hr.beather.data.OpenMeteo
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.data.WeatherCodes
import hr.beather.data.WeatherCodes.deg
import hr.beather.data.WeatherRepository
import hr.beather.widget.WidgetRenderer
import hr.beather.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: LocationAdapter

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) addDeviceLocation() else toast(R.string.perm_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        adapter = LocationAdapter(onClick = this::showForecast, onDelete = this::confirmDelete)
        findViewById<RecyclerView>(R.id.locations).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btn_add).setOnClickListener {
            showAddLocationDialog(this) { onLocationAdded(it) }
        }
        findViewById<Button>(R.id.btn_gps).setOnClickListener {
            val has = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (has) addDeviceLocation()
            else locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        RefreshWorker.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        reload()
        // Fill in anything never fetched (e.g. the default Zagreb on first launch).
        val missing = Store.locations(this).filter { WeatherRepository.cacheAgeMs(this, it) > 30 * 60 * 1000L }
        if (missing.isNotEmpty()) refreshLocations(missing, quiet = true)
    }

    private fun reload() = adapter.submit(Store.locations(this))

    private fun onLocationAdded(loc: SavedLocation) {
        Store.addLocation(this, loc)
        reload()
        refreshLocations(listOf(loc), quiet = true)
    }

    private fun refreshLocations(list: List<SavedLocation>, quiet: Boolean) {
        lifecycleScope.launch {
            var ok = 0
            for (l in list) if (runCatching { WeatherRepository.refresh(this@MainActivity, l) }.isSuccess) ok++
            reload()
            WidgetRenderer.updateAll(this@MainActivity)
            if (!quiet || ok < list.size) {
                toast(if (ok == list.size) R.string.refreshed else R.string.refresh_failed)
            }
        }
    }

    private fun addDeviceLocation() {
        toast(R.string.locating)
        lifecycleScope.launch {
            val fix = DeviceLocation.current(this@MainActivity)
            if (fix == null) {
                toast(R.string.no_fix); return@launch
            }
            val name = withContext(Dispatchers.IO) {
                DeviceLocation.placeName(this@MainActivity, fix.latitude, fix.longitude)
            } ?: getString(R.string.my_location)
            onLocationAdded(
                SavedLocation(Store.GPS_ID, name, getString(R.string.my_location), fix.latitude, fix.longitude, fromGps = true)
            )
        }
    }

    private fun confirmDelete(loc: SavedLocation) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_q, loc.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Store.removeLocation(this, loc.id)
                reload()
                WidgetRenderer.updateAll(this)
            }
            .show()
    }

    private fun showForecast(loc: SavedLocation) {
        val fc: Forecast = WeatherRepository.cached(this, loc) ?: run {
            refreshLocations(listOf(loc), quiet = false); return
        }
        val locale = Locale.getDefault()
        val c = fc.current
        val text = buildString {
            append(deg(c.temp)).append("  ").append(WeatherCodes.describe(this@MainActivity, c.code)).append('\n')
            c.feelsLike?.let { append(getString(R.string.feels_like, deg(it))).append("   ") }
            c.humidity?.let { append("💧").append(it).append("%") }
            append('\n')
            if (c.wind != null) {
                append("💨 ")
                c.windDir?.let { append(WeatherCodes.windDir(this@MainActivity, it)).append(' ') }
                append(c.wind.roundToInt()).append(" km/h")
                c.gusts?.let { append(" (").append(getString(R.string.gusts, it.roundToInt())).append(")") }
                append('\n')
            }
            c.station?.let {
                append(getString(R.string.measured_at, it.name, it.distanceKm.roundToInt())).append('\n')
            }
            append('\n')
            fc.days.forEachIndexed { i, d ->
                val name = if (i == 0) getString(R.string.today)
                else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) }
                append(name.padEnd(6)).append("  ")
                append(deg(d.max)).append(" / ").append(deg(d.min)).append("  ")
                append(WeatherCodes.describe(this@MainActivity, d.code))
                val rain = listOfNotNull(
                    d.precip?.takeIf { it >= 0.1 }?.let { "%.1f mm".format(locale, it) },
                    d.pop?.takeIf { it >= 10 }?.let { "$it%" },
                )
                if (rain.isNotEmpty()) append(" · ").append(rain.joinToString(" "))
                append('\n')
            }
            append('\n').append(getString(R.string.source_line, OpenMeteo.label(fc.model)))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(loc.name)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.action_stations).isChecked = Store.useStations(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            refreshLocations(Store.locations(this), quiet = false); true
        }
        R.id.action_model -> {
            val models = OpenMeteo.MODELS
            val current = models.indexOfFirst { it.id == Store.model(this) }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.forecast_model)
                .setSingleChoiceItems(models.map { it.label }.toTypedArray(), current) { d, which ->
                    Store.setModel(this, models[which].id)
                    d.dismiss()
                    refreshLocations(Store.locations(this), quiet = false)
                }
                .show()
            true
        }
        R.id.action_stations -> {
            val on = !item.isChecked
            item.isChecked = on
            Store.setUseStations(this, on)
            refreshLocations(Store.locations(this), quiet = false)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
