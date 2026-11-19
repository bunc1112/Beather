package hr.beather.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Open-Meteo: free, no API key. Default model is ECMWF IFS HRES at 9 km –
 * the same global model DHMZ (Croatian met service) blends into its own
 * city forecasts. Other models are selectable in the app.
 */
object OpenMeteo {

    data class Model(val id: String, val label: String)

    val MODELS = listOf(
        Model("ecmwf_ifs", "ECMWF IFS 9 km"),
        Model(Yr.MODEL_ID, "Yr (MET Norway)"),
        Model("best_match", "Open-Meteo Best match"),
        Model("icon_seamless", "DWD ICON"),
        Model("ecmwf_aifs025_single", "ECMWF AIFS (AI)"),
    )

    fun label(id: String) = MODELS.firstOrNull { it.id == id }?.label ?: id

    fun forecast(lat: Double, lon: Double, model: String): String {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(lat).append("&longitude=").append(lon)
            append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,")
            append("weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation")
            append("&hourly=temperature_2m,weather_code,precipitation_probability,is_day,precipitation")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,")
            append("precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset")
            append("&timezone=auto&forecast_days=8&wind_speed_unit=kmh")
            if (model != "best_match") append("&models=").append(model)
        }
        return Http.get(url)
    }

    /** Place search. With [onlyCroatia] results are limited to country code HR. */
    fun search(query: String, onlyCroatia: Boolean, language: String): List<SavedLocation> {
        val q = URLEncoder.encode(query, "UTF-8")
        val lang = if (language in setOf("hr", "en", "de", "it", "sl", "sr", "bs")) language else "en"
        var url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=15&language=$lang&format=json"
        if (onlyCroatia) url += "&countryCode=HR"
        val json = JSONObject(Http.get(url))
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val parts = listOfNotNull(
                o.optString("admin1").takeIf { it.isNotBlank() },
                o.optString("country").takeIf { it.isNotBlank() && !onlyCroatia },
            )
            SavedLocation(
                id = "om_" + o.optLong("id", (o.getDouble("latitude") * 1e4 + o.getDouble("longitude")).toLong()),
                name = o.getString("name"),
                region = parts.joinToString(", "),
                lat = o.getDouble("latitude"),
                lon = o.getDouble("longitude"),
            )
        }
    }
}
