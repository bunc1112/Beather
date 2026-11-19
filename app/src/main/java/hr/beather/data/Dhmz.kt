package hr.beather.data

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A real measurement from a DHMZ (meteo.hr) weather station. */
data class StationObs(
    val name: String,
    val lat: Double,
    val lon: Double,
    val temp: Double,
    val humidity: Int?,
    val distanceKm: Double,
    val observedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("lat", lat).put("lon", lon).put("temp", temp)
        .put("hum", humidity ?: -1).put("dist", distanceKm).put("at", observedAt)

    companion object {
        fun fromJson(o: JSONObject) = StationObs(
            o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("temp"),
            o.optInt("hum", -1).takeIf { it >= 0 }, o.getDouble("dist"), o.getLong("at"),
        )
    }
}

/**
 * DHMZ publishes hourly observations from ~100 Croatian stations at
 * https://vrijeme.hr/hrvatska_n.xml. Beather uses the nearest station
 * (if close and fresh) for the "now" temperature and humidity, so the
 * widget shows what is actually measured, not just what a model predicts.
 */
object Dhmz {
    private const val URL = "https://vrijeme.hr/hrvatska_n.xml"
    private const val MAX_DISTANCE_KM = 15.0
    private const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
    private val ZAGREB: ZoneId = ZoneId.of("Europe/Zagreb")

    private var cached: List<StationObs> = emptyList()
    private var cachedAt = 0L

    @Synchronized
    private fun stations(): List<StationObs> {
        if (System.currentTimeMillis() - cachedAt < 10 * 60 * 1000L && cached.isNotEmpty()) return cached
        cached = parse(Http.get(URL))
        cachedAt = System.currentTimeMillis()
        return cached
    }

    fun nearest(lat: Double, lon: Double): StationObs? {
        val now = System.currentTimeMillis()
        return stations()
            .filter { now - it.observedAt < MAX_AGE_MS }
            .map { it.copy(distanceKm = haversineKm(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceKm <= MAX_DISTANCE_KM }
            .minByOrNull { it.distanceKm }
    }

    fun parse(xml: String): List<StationObs> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var date: String? = null
        var hour: Int? = null
        val fields = HashMap<String, String>()
        val raw = mutableListOf<Map<String, String>>()
        var tag = ""
        var inCity = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "Grad") { inCity = true; fields.clear() }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) {
                        when {
                            tag == "Datum" -> date = text
                            tag == "Termin" -> hour = text.toIntOrNull()
                            inCity -> fields[tag] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "Grad") { raw += HashMap(fields); inCity = false }
                    tag = ""
                }
            }
            event = parser.next()
        }

        val observedAt = runCatching {
            LocalDate.parse(date!!.trim().trimEnd('.'), DateTimeFormatter.ofPattern("d.M.yyyy"))
                .atTime(hour!!, 0).atZone(ZAGREB).toInstant().toEpochMilli()
        }.getOrNull() ?: return emptyList()

        return raw.mapNotNull { f ->
            val name = f["GradIme"] ?: return@mapNotNull null
            val la = f["Lat"].num() ?: return@mapNotNull null
            val lo = f["Lon"].num() ?: return@mapNotNull null
            val t = f["Temp"].num() ?: return@mapNotNull null
            StationObs(name, la, lo, t, f["Vlaga"].num()?.toInt(), 0.0, observedAt)
        }
    }

    private fun String?.num(): Double? = this?.trim()?.replace(',', '.')?.toDoubleOrNull()

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }
}
