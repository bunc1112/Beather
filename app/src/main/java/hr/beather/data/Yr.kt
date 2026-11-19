package hr.beather.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Yr / MET Norway Locationforecast 2.0 (https://api.met.no). Free, no key;
 * MET's terms require an identifying User-Agent (set in [Http]) and at most
 * 4 decimals in coordinates.
 */
object Yr {
    const val MODEL_ID = "yr"

    private class Entry(
        val time: LocalDateTime,
        val temp: Double,
        val humidity: Double?,
        val windMs: Double?,
        val windDir: Double?,
        val gustMs: Double?,
        val symbol1: String?,
        val symbol6: String?,
        val symbol12: String?,
        val precip1: Double?,
        val precip6: Double?,
        val pop1: Double?,
        val pop6: Double?,
        val max6: Double?,
        val min6: Double?,
    ) {
        val symbol get() = symbol1 ?: symbol6 ?: symbol12
    }

    fun forecast(lat: Double, lon: Double, station: StationObs?, fetchedAt: Long): Forecast {
        val url = String.format(
            Locale.US,
            "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.4f&lon=%.4f",
            lat, lon,
        )
        return parse(Http.get(url), station, fetchedAt, ZoneId.systemDefault())
    }

    fun parse(raw: String, station: StationObs?, fetchedAt: Long, zone: ZoneId): Forecast {
        val series = JSONObject(raw).getJSONObject("properties").getJSONArray("timeseries")
        val entries = (0 until series.length()).mapNotNull { i ->
            val o = series.getJSONObject(i)
            val data = o.getJSONObject("data")
            val inst = data.getJSONObject("instant").getJSONObject("details")
            val temp = inst.d("air_temperature") ?: return@mapNotNull null
            val n1 = data.optJSONObject("next_1_hours")
            val n6 = data.optJSONObject("next_6_hours")
            val n12 = data.optJSONObject("next_12_hours")
            Entry(
                time = Instant.parse(o.getString("time")).atZone(zone).toLocalDateTime(),
                temp = temp,
                humidity = inst.d("relative_humidity"),
                windMs = inst.d("wind_speed"),
                windDir = inst.d("wind_from_direction"),
                gustMs = inst.d("wind_speed_of_gust"),
                symbol1 = n1?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotBlank() },
                symbol6 = n6?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotBlank() },
                symbol12 = n12?.optJSONObject("summary")?.optString("symbol_code")?.takeIf { it.isNotBlank() },
                precip1 = n1?.optJSONObject("details").d("precipitation_amount"),
                precip6 = n6?.optJSONObject("details").d("precipitation_amount"),
                pop1 = n1?.optJSONObject("details").d("probability_of_precipitation"),
                pop6 = n6?.optJSONObject("details").d("probability_of_precipitation"),
                max6 = n6?.optJSONObject("details").d("air_temperature_max"),
                min6 = n6?.optJSONObject("details").d("air_temperature_min"),
            )
        }
        require(entries.isNotEmpty()) { "Empty Yr forecast" }

        val hours = entries.map { e ->
            Hour(
                time = e.time,
                temp = e.temp,
                code = code(e.symbol),
                isDay = isDay(e.symbol, e.time),
                pop = (e.pop1 ?: e.pop6)?.roundToInt(),
                precip = e.precip1 ?: e.precip6,
            )
        }

        val today = LocalDate.now(zone)
        val days = entries.groupBy { it.time.toLocalDate() }
            .filterKeys { !it.isBefore(today) }
            .toSortedMap()
            .map { (date, list) ->
                val temps = list.map { it.temp } + list.mapNotNull { it.max6 } + list.mapNotNull { it.min6 }
                // Precipitation: hourly amounts where available, 6-hourly blocks after that.
                val precip = list.sumOf { it.precip1 ?: if (it.time.hour % 6 == 0) it.precip6 ?: 0.0 else 0.0 }
                val noon = list.minByOrNull { kotlin.math.abs(it.time.hour - 12) }
                val symbol = noon?.symbol6 ?: noon?.symbol ?: list.firstNotNullOfOrNull { it.symbol }
                val thunder = list.any { it.time.hour in 9..21 && it.symbol?.contains("thunder") == true }
                Day(
                    date = date,
                    code = if (thunder) 95 else code(symbol),
                    max = temps.max(),
                    min = temps.min(),
                    precip = precip,
                    pop = list.mapNotNull { it.pop1 ?: it.pop6 }.maxOrNull()?.roundToInt(),
                    windMax = list.mapNotNull { it.windMs }.maxOrNull()?.times(3.6),
                    gustMax = list.mapNotNull { it.gustMs }.maxOrNull()?.times(3.6),
                )
            }

        val now = entries.first()
        val current = Current(
            temp = station?.temp ?: now.temp,
            feelsLike = feelsLike(now.temp, now.windMs),
            humidity = station?.humidity ?: now.humidity?.roundToInt(),
            code = code(now.symbol),
            isDay = isDay(now.symbol, now.time),
            wind = now.windMs?.times(3.6),
            windDir = now.windDir,
            gusts = now.gustMs?.times(3.6),
            station = station,
        )
        return Forecast(fetchedAt, MODEL_ID, current, hours, days)
    }

    /** Wind chill below 10 °C, otherwise the air temperature. */
    private fun feelsLike(t: Double, windMs: Double?): Double? {
        val v = (windMs ?: return null) * 3.6
        if (t > 10 || v < 4.8) return t
        val p = Math.pow(v, 0.16)
        return 13.12 + 0.6215 * t - 11.37 * p + 0.3965 * t * p
    }

    private fun isDay(symbol: String?, time: LocalDateTime): Boolean = when {
        symbol == null -> time.hour in 7..18
        symbol.endsWith("_night") -> false
        symbol.endsWith("_day") || symbol.endsWith("_polartwilight") -> true
        else -> time.hour in 7..18
    }

    /** Maps a Yr symbol_code to the WMO-style codes the rest of Beather uses. */
    fun code(symbol: String?): Int {
        val s = symbol?.substringBefore('_') ?: return 3
        if ("thunder" in s) return 95
        return when (s) {
            "clearsky" -> 0
            "fair" -> 1
            "partlycloudy" -> 2
            "cloudy" -> 3
            "fog" -> 45
            "lightrain" -> 61
            "rain" -> 63
            "heavyrain" -> 65
            "lightrainshowers" -> 80
            "rainshowers" -> 81
            "heavyrainshowers" -> 82
            "lightsnow" -> 71
            "snow" -> 73
            "heavysnow" -> 75
            "lightsnowshowers", "snowshowers" -> 85
            "heavysnowshowers" -> 86
            else -> if ("sleet" in s) WeatherCodes.SLEET else 3
        }
    }
}
