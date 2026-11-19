package hr.beather.data

import android.content.Context
import hr.beather.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class Current(
    val temp: Double,
    val feelsLike: Double?,
    val humidity: Int?,
    val code: Int,
    val isDay: Boolean,
    val wind: Double?,
    val windDir: Double?,
    val gusts: Double?,
    val station: StationObs?,
)

data class Hour(val time: LocalDateTime, val temp: Double, val code: Int, val isDay: Boolean, val pop: Int?)

data class Day(
    val date: LocalDate,
    val code: Int,
    val max: Double,
    val min: Double,
    val precip: Double?,
    val pop: Int?,
    val windMax: Double?,
    val gustMax: Double?,
)

data class Forecast(
    val fetchedAt: Long,
    val model: String,
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
)

private fun JSONArray?.d(i: Int): Double? =
    if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeUnless { it.isNaN() }

private fun JSONObject.d(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

object ForecastParser {

    fun parse(raw: String, station: StationObs?, model: String, fetchedAt: Long): Forecast {
        val root = JSONObject(raw)
        val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 3600))
        val nowLocal = LocalDateTime.now(offset)

        // Hourly
        val h = root.getJSONObject("hourly")
        val hTime = h.getJSONArray("time")
        val hTemp = h.optJSONArray("temperature_2m")
        val hCode = h.optJSONArray("weather_code")
        val hPop = h.optJSONArray("precipitation_probability")
        val hDay = h.optJSONArray("is_day")
        val allHours = (0 until hTime.length()).mapNotNull { i ->
            val t = hTemp.d(i) ?: return@mapNotNull null
            Hour(
                time = LocalDateTime.parse(hTime.getString(i)),
                temp = t,
                code = hCode.d(i)?.toInt() ?: 3,
                isDay = (hDay.d(i) ?: 1.0) > 0.5,
                pop = hPop.d(i)?.roundToInt(),
            )
        }
        val thisHour = nowLocal.truncatedTo(ChronoUnit.HOURS)
        val upcoming = allHours.filter { !it.time.isBefore(thisHour) }

        // Daily
        val dj = root.getJSONObject("daily")
        val dTime = dj.getJSONArray("time")
        val days = (0 until dTime.length()).mapNotNull { i ->
            val max = dj.optJSONArray("temperature_2m_max").d(i) ?: return@mapNotNull null
            val min = dj.optJSONArray("temperature_2m_min").d(i) ?: return@mapNotNull null
            Day(
                date = LocalDate.parse(dTime.getString(i)),
                code = dj.optJSONArray("weather_code").d(i)?.toInt() ?: 3,
                max = max,
                min = min,
                precip = dj.optJSONArray("precipitation_sum").d(i),
                pop = dj.optJSONArray("precipitation_probability_max").d(i)?.roundToInt(),
                windMax = dj.optJSONArray("wind_speed_10m_max").d(i),
                gustMax = dj.optJSONArray("wind_gusts_10m_max").d(i),
            )
        }.filter { !it.date.isBefore(nowLocal.toLocalDate()) }

        // Current (model), then corrected with a real DHMZ measurement if one is close.
        val c = root.optJSONObject("current")
        val firstHour = upcoming.firstOrNull() ?: allHours.lastOrNull()
        val modelTemp = c?.d("temperature_2m") ?: firstHour?.temp ?: 0.0
        val current = Current(
            temp = station?.temp ?: modelTemp,
            feelsLike = c?.d("apparent_temperature"),
            humidity = station?.humidity ?: c?.d("relative_humidity_2m")?.roundToInt(),
            code = c?.d("weather_code")?.toInt() ?: firstHour?.code ?: 3,
            isDay = (c?.d("is_day") ?: if (firstHour?.isDay == false) 0.0 else 1.0) > 0.5,
            wind = c?.d("wind_speed_10m"),
            windDir = c?.d("wind_direction_10m"),
            gusts = c?.d("wind_gusts_10m"),
            station = station,
        )

        return Forecast(fetchedAt, model, current, upcoming, days)
    }
}

object WeatherRepository {

    suspend fun refresh(ctx: Context, loc: SavedLocation): Forecast = withContext(Dispatchers.IO) {
        val model = Store.model(ctx)
        val raw = OpenMeteo.forecast(loc.lat, loc.lon, model)
        val station = if (Store.useStations(ctx)) {
            runCatching { Dhmz.nearest(loc.lat, loc.lon) }.getOrNull()
        } else null
        val now = System.currentTimeMillis()
        val forecast = ForecastParser.parse(raw, station, model, now) // validates before caching
        val cache = JSONObject()
            .put("om", raw)
            .put("model", model)
            .put("at", now)
        if (station != null) cache.put("st", station.toJson())
        Store.putCache(ctx, loc.id, cache.toString())
        forecast
    }

    fun cached(ctx: Context, loc: SavedLocation): Forecast? = runCatching {
        val o = JSONObject(Store.cache(ctx, loc.id) ?: return null)
        val st = o.optJSONObject("st")?.let { StationObs.fromJson(it) }
        ForecastParser.parse(o.getString("om"), st, o.optString("model"), o.getLong("at"))
    }.getOrNull()

    fun cacheAgeMs(ctx: Context, loc: SavedLocation): Long = runCatching {
        System.currentTimeMillis() - JSONObject(Store.cache(ctx, loc.id)!!).getLong("at")
    }.getOrDefault(Long.MAX_VALUE)
}

object WeatherCodes {

    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_w_clear_day else R.drawable.ic_w_clear_night
        2 -> if (isDay) R.drawable.ic_w_partly_day else R.drawable.ic_w_partly_night
        3 -> R.drawable.ic_w_cloudy
        45, 48 -> R.drawable.ic_w_fog
        51, 53, 55 -> R.drawable.ic_w_drizzle
        56, 57, 66, 67 -> R.drawable.ic_w_sleet
        61, 63, 65, 80, 81, 82 -> R.drawable.ic_w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_w_snow
        95, 96, 99 -> R.drawable.ic_w_storm
        else -> R.drawable.ic_w_cloudy
    }

    fun describe(ctx: Context, code: Int): String = ctx.getString(
        when (code) {
            0 -> R.string.wc_clear
            1 -> R.string.wc_mainly_clear
            2 -> R.string.wc_partly
            3 -> R.string.wc_overcast
            45, 48 -> R.string.wc_fog
            51, 53, 55 -> R.string.wc_drizzle
            56, 57 -> R.string.wc_freezing_drizzle
            61 -> R.string.wc_rain_light
            63 -> R.string.wc_rain
            65 -> R.string.wc_rain_heavy
            66, 67 -> R.string.wc_freezing_rain
            71 -> R.string.wc_snow_light
            73 -> R.string.wc_snow
            75 -> R.string.wc_snow_heavy
            77 -> R.string.wc_snow_grains
            80 -> R.string.wc_showers_light
            81 -> R.string.wc_showers
            82 -> R.string.wc_showers_heavy
            85, 86 -> R.string.wc_snow_showers
            95 -> R.string.wc_storm
            96, 99 -> R.string.wc_storm_hail
            else -> R.string.wc_unknown
        }
    )

    fun windDir(ctx: Context, deg: Double): String {
        val dirs = ctx.resources.getStringArray(R.array.wind_dirs)
        return dirs[((deg / 45.0).roundToInt() % 8 + 8) % 8]
    }

    /** Adriatic wind names – a NE wind is bura, a SE wind is jugo. */
    fun adriaticWind(ctx: Context, lat: Double, lon: Double, deg: Double?, speed: Double?): String? {
        if (deg == null || speed == null || speed < 20) return null
        // Rough band covering the Croatian coast, islands and Lika; excludes the interior.
        val coastal = lat < 45.6 && lon < 13.9 + (45.6 - lat) * 1.45 + 0.7
        if (!coastal) return null
        return when (deg) {
            in 20.0..80.0 -> ctx.getString(R.string.wind_bura)
            in 110.0..160.0 -> ctx.getString(R.string.wind_jugo)
            else -> null
        }
    }

    fun deg(t: Double) = "${t.roundToInt()}°"
}
