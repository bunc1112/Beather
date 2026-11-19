package hr.beather.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
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
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.data.Units
import hr.beather.data.WeatherRepository
import hr.beather.widget.BeatherWidgetProvider
import hr.beather.widget.WidgetRenderer
import hr.beather.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        adapter = LocationAdapter(onClick = this::openForecast, onDelete = this::confirmDelete)
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
        Units.load(this)
        reload()
        val stale = Store.locations(this).filter { WeatherRepository.cacheAgeMs(this, it) > 30 * 60 * 1000L }
        if (stale.isNotEmpty()) refreshLocations(stale, quiet = true)
    }

    private fun reload() = adapter.submit(Store.locations(this))

    private fun openForecast(loc: SavedLocation) {
        startActivity(Intent(this, ForecastActivity::class.java).putExtra(ForecastActivity.EXTRA_LOCATION, loc.id))
    }

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
            if (!quiet || ok < list.size) toast(if (ok == list.size) R.string.refreshed else R.string.refresh_failed)
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

    /** Lets the user restyle any placed widget from inside the app (works on every launcher). */
    private fun customizeWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, BeatherWidgetProvider::class.java))
        if (ids.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.no_widgets)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.add_widget) { _, _ -> pinWidget() }
                .show()
            return
        }
        val labels = ids.mapIndexed { i, id ->
            getString(R.string.widget_n, i + 1, Store.widgetLocation(this, id)?.name ?: "—")
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.customize_widgets)
            .setItems(labels) { _, which -> openWidgetDesigner(ids[which]) }
            .show()
    }

    private fun openWidgetDesigner(widgetId: Int) {
        startActivity(Intent(this, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
    }

    private fun pinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, BeatherWidgetProvider::class.java), null, null)
        } else {
            toast(R.string.pin_unsupported)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { refreshLocations(Store.locations(this), quiet = false); true }
        R.id.action_widgets -> { customizeWidgets(); true }
        R.id.action_add_widget -> { pinWidget(); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
