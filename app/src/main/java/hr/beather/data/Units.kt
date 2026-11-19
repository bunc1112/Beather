package hr.beather.data

import android.content.Context
import kotlin.math.roundToInt

/** Display units, loaded from settings at app start and whenever they change. */
object Units {
    const val WIND_KMH = "kmh"
    const val WIND_MS = "ms"
    const val WIND_KN = "kn"
    const val WIND_BFT = "bft"

    @Volatile var fahrenheit = false
    @Volatile var wind = WIND_KMH

    fun load(ctx: Context) {
        fahrenheit = Store.fahrenheit(ctx)
        wind = Store.windUnit(ctx)
    }

    fun temp(celsius: Double): String =
        "${(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).roundToInt()}°"

    fun windNumber(kmh: Double): String = when (wind) {
        WIND_MS -> (kmh / 3.6).roundToInt().toString()
        WIND_KN -> (kmh / 1.852).roundToInt().toString()
        WIND_BFT -> beaufort(kmh).toString()
        else -> kmh.roundToInt().toString()
    }

    fun windUnitLabel(): String = when (wind) {
        WIND_MS -> "m/s"
        WIND_KN -> "kn"
        WIND_BFT -> "Bf"
        else -> "km/h"
    }

    fun windSpeed(kmh: Double) = "${windNumber(kmh)} ${windUnitLabel()}"

    private fun beaufort(kmh: Double): Int {
        val limits = doubleArrayOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)
        return limits.indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }
    }
}
