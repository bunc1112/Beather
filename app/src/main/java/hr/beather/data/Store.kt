package hr.beather.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedLocation(
    val id: String,
    val name: String,
    val region: String,
    val lat: Double,
    val lon: Double,
    val fromGps: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("region", region)
        .put("lat", lat).put("lon", lon).put("gps", fromGps)

    companion object {
        fun fromJson(o: JSONObject) = SavedLocation(
            id = o.getString("id"),
            name = o.getString("name"),
            region = o.optString("region"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            fromGps = o.optBoolean("gps"),
        )
    }
}

/** Everything Beather remembers lives in one SharedPreferences file. */
object Store {
    private const val PREFS = "beather"
    const val DEFAULT_MODEL = "ecmwf_ifs"
    const val GPS_ID = "gps"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Locations -------------------------------------------------------

    fun locations(ctx: Context): List<SavedLocation> {
        val raw = p(ctx).getString("locations", null)
        if (raw == null) {
            val zagreb = SavedLocation("om_3186886", "Zagreb", "Grad Zagreb", 45.8144, 15.978)
            saveLocations(ctx, listOf(zagreb))
            return listOf(zagreb)
        }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { SavedLocation.fromJson(arr.getJSONObject(it)) }
    }

    private fun saveLocations(ctx: Context, list: List<SavedLocation>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        p(ctx).edit().putString("locations", arr.toString()).apply()
    }

    fun addLocation(ctx: Context, loc: SavedLocation) {
        val current = locations(ctx)
        val idx = current.indexOfFirst { it.id == loc.id }
        val updated = if (idx >= 0) current.toMutableList().also { it[idx] = loc } else current + loc
        saveLocations(ctx, updated)
    }

    fun removeLocation(ctx: Context, id: String) {
        saveLocations(ctx, locations(ctx).filterNot { it.id == id })
        p(ctx).edit().remove("cache_$id").apply()
    }

    fun location(ctx: Context, id: String): SavedLocation? = locations(ctx).firstOrNull { it.id == id }

    // ---- Widget -> location mapping -------------------------------------

    fun widgetLocation(ctx: Context, widgetId: Int): SavedLocation? {
        val id = p(ctx).getString("w_$widgetId", null)
        val all = locations(ctx)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    fun setWidgetLocation(ctx: Context, widgetId: Int, locationId: String) =
        p(ctx).edit().putString("w_$widgetId", locationId).apply()

    fun removeWidget(ctx: Context, widgetId: Int) =
        p(ctx).edit().remove("w_$widgetId").remove("ws_$widgetId").apply()

    fun widgetPrefs(ctx: Context, widgetId: Int): WidgetPrefs =
        WidgetPrefs.fromJson(p(ctx).getString("ws_$widgetId", null))

    fun setWidgetPrefs(ctx: Context, widgetId: Int, prefs: WidgetPrefs) =
        p(ctx).edit().putString("ws_$widgetId", prefs.toJson()).apply()

    fun fahrenheit(ctx: Context) = p(ctx).getBoolean("fahrenheit", false)
    fun setFahrenheit(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("fahrenheit", on).apply()

    fun windUnit(ctx: Context): String = p(ctx).getString("wind", Units.WIND_KMH) ?: Units.WIND_KMH
    fun setWindUnit(ctx: Context, unit: String) = p(ctx).edit().putString("wind", unit).apply()

    fun calendarSource(ctx: Context, calendarId: Long): CalSource? =
        p(ctx).getString("cal_$calendarId", null)?.let { runCatching { CalSource.valueOf(it) }.getOrNull() }

    fun setCalendarSource(ctx: Context, calendarId: Long, source: CalSource) =
        p(ctx).edit().putString("cal_$calendarId", source.name).apply()

    fun refreshMinutes(ctx: Context) = p(ctx).getInt("refresh", 30)
    fun setRefreshMinutes(ctx: Context, min: Int) = p(ctx).edit().putInt("refresh", min).apply()

    // ---- Settings ---------------------------------------------------------

    fun model(ctx: Context): String = p(ctx).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(ctx: Context, model: String) = p(ctx).edit().putString("model", model).apply()

    fun useStations(ctx: Context): Boolean = p(ctx).getBoolean("stations", true)
    fun setUseStations(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("stations", on).apply()

    // ---- Forecast cache --------------------------------------------------

    fun cache(ctx: Context, locationId: String): String? = p(ctx).getString("cache_$locationId", null)
    fun putCache(ctx: Context, locationId: String, json: String) =
        p(ctx).edit().putString("cache_$locationId", json).apply()
}
