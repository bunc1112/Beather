package hr.beather.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import hr.beather.R
import hr.beather.data.OpenMeteo
import hr.beather.data.SavedLocation
import hr.beather.data.WeatherCodes
import hr.beather.data.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Search dialog: type a place name, pick a result. */
fun showAddLocationDialog(activity: AppCompatActivity, onAdded: (SavedLocation) -> Unit) {
    val view = activity.layoutInflater.inflate(R.layout.dialog_search, null)
    val input = view.findViewById<EditText>(R.id.search_input)
    val onlyHr = view.findViewById<CheckBox>(R.id.only_croatia)
    val progress = view.findViewById<ProgressBar>(R.id.search_progress)
    val empty = view.findViewById<TextView>(R.id.search_empty)
    val list = view.findViewById<ListView>(R.id.search_results)

    val results = mutableListOf<SavedLocation>()
    val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    list.adapter = adapter

    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.add_location)
        .setView(view)
        .setNegativeButton(android.R.string.cancel, null)
        .create()

    var job: Job? = null
    fun search() {
        job?.cancel()
        val q = input.text.toString().trim()
        if (q.length < 2) {
            results.clear(); adapter.clear(); empty.isVisible = false
            return
        }
        job = activity.lifecycleScope.launch {
            delay(350)
            progress.isVisible = true
            val found = withContext(Dispatchers.IO) {
                runCatching { OpenMeteo.search(q, onlyHr.isChecked, Locale.getDefault().language) }
                    .getOrDefault(emptyList())
            }
            progress.isVisible = false
            results.clear(); results.addAll(found)
            adapter.clear()
            adapter.addAll(found.map { if (it.region.isBlank()) it.name else "${it.name} — ${it.region}" })
            empty.isVisible = found.isEmpty()
        }
    }

    input.doAfterTextChanged { search() }
    onlyHr.setOnCheckedChangeListener { _, _ -> search() }
    list.setOnItemClickListener { _, _, pos, _ ->
        results.getOrNull(pos)?.let(onAdded)
        dialog.dismiss()
    }
    dialog.show()
    input.requestFocus()
}

object DeviceLocation {

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in providers) {
                val fix = runCatching {
                    withTimeoutOrNull(15_000) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            val cancel = CancellationSignal()
                            cont.invokeOnCancellation { cancel.cancel() }
                            lm.getCurrentLocation(p, cancel, ctx.mainExecutor) { cont.resume(it) }
                        }
                    }
                }.getOrNull()
                if (fix != null) return fix
            }
        }
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    fun placeName(ctx: Context, lat: Double, lon: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
        a?.locality ?: a?.subAdminArea ?: a?.adminArea
    }.getOrNull()
}

class LocationAdapter(
    private val onClick: (SavedLocation) -> Unit,
    private val onDelete: (SavedLocation) -> Unit,
) : RecyclerView.Adapter<LocationAdapter.VH>() {

    private var items: List<SavedLocation> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SavedLocation>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: android.widget.ImageView = v.findViewById(R.id.item_icon)
        val name: TextView = v.findViewById(R.id.item_name)
        val sub: TextView = v.findViewById(R.id.item_sub)
        val temp: TextView = v.findViewById(R.id.item_temp)
        val delete: ImageButton = v.findViewById(R.id.item_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_location, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val loc = items[position]
        val ctx = h.itemView.context
        val fc = WeatherRepository.cached(ctx, loc)
        h.name.text = if (loc.fromGps) "📍 ${loc.name}" else loc.name
        h.sub.text = listOfNotNull(
            loc.region.takeIf { it.isNotBlank() },
            fc?.let { WeatherCodes.describe(ctx, it.current.code) },
        ).joinToString(" · ")
        h.temp.text = fc?.let { WeatherCodes.deg(it.current.temp) } ?: "--°"
        if (fc != null) h.icon.setImageResource(WeatherCodes.icon(fc.current.code, fc.current.isDay))
        else h.icon.setImageResource(R.drawable.ic_w_cloudy)
        h.itemView.setOnClickListener { onClick(loc) }
        h.delete.setOnClickListener { onDelete(loc) }
    }
}
