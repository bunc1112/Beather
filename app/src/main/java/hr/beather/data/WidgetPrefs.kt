package hr.beather.data

import org.json.JSONObject

/** Every look-and-feel option of a single widget. */
data class WidgetPrefs(
    // What to show
    val showLocation: Boolean = true,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showAlarm: Boolean = true,
    val showDescription: Boolean = true,
    val showDetails: Boolean = false,
    val showHourly: Boolean = false,
    val showDays: Boolean = true,
    val showUpdated: Boolean = false,
    val showSource: Boolean = false,
    val days: Int = 5,          // 3..7
    val showGoogle: Boolean = true,
    val showOutlook: Boolean = true,
    val calDays: Int = 7,       // look ahead 1..14 days
    val calEvents: Int = 1,     // events per row 1..3
    val clockFont: Int = 0,     // 0 thin, 1 light, 2 regular, 3 bold
    val vAlign: Int = 0,        // 0 top, 1 center, 2 bottom
    // Background
    val skyBackground: Boolean = false,   // true = blue day / navy night gradient
    val bgColor: Int = 0xFF000000.toInt(),
    val opacity: Int = 55,      // 0..100
    val corner: Int = 2,        // index into CORNERS
    // Text & icons
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val accentColor: Int = 0xFF90CAF9.toInt(),
    val textScale: Int = 100,   // 70..150 %
    val iconScale: Int = 100,   // 60..160 %
    // Behaviour
    val clockOpensAlarms: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("loc", showLocation).put("clock", showClock).put("date", showDate).put("alarm", showAlarm)
        .put("desc", showDescription).put("details", showDetails).put("hourly", showHourly)
        .put("daysOn", showDays).put("updated", showUpdated).put("source", showSource)
        .put("days", days).put("gcal", showGoogle).put("ocal", showOutlook)
        .put("calDays", calDays).put("calEvents", calEvents).put("font", clockFont).put("valign", vAlign).put("sky", skyBackground).put("bg", bgColor)
        .put("opacity", opacity).put("corner", corner).put("text", textColor).put("accent", accentColor)
        .put("tscale", textScale).put("iscale", iconScale).put("clockAlarms", clockOpensAlarms)
        .toString()

    companion object {
        val CORNERS = intArrayOf(0, 12, 24, 32)

        val PRESET_CLOCK = WidgetPrefs()
        val PRESET_FULL = WidgetPrefs(
            showClock = false, showDate = false, showAlarm = false, showDetails = true,
            showHourly = true, showUpdated = true, showSource = true, skyBackground = true, opacity = 90,
            showGoogle = false, showOutlook = false,
        )
        val PRESET_MINIMAL = WidgetPrefs(
            showClock = false, showDate = false, showAlarm = false, showDays = false, opacity = 0,
            showGoogle = false, showOutlook = false,
        )

        fun fromJson(s: String?): WidgetPrefs {
            if (s == null) return WidgetPrefs()
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return WidgetPrefs()
            val d = WidgetPrefs()
            return WidgetPrefs(
                showLocation = o.optBoolean("loc", d.showLocation),
                showClock = o.optBoolean("clock", d.showClock),
                showDate = o.optBoolean("date", d.showDate),
                showAlarm = o.optBoolean("alarm", d.showAlarm),
                showDescription = o.optBoolean("desc", d.showDescription),
                showDetails = o.optBoolean("details", d.showDetails),
                showHourly = o.optBoolean("hourly", d.showHourly),
                showDays = o.optBoolean("daysOn", d.showDays),
                showUpdated = o.optBoolean("updated", d.showUpdated),
                showSource = o.optBoolean("source", d.showSource),
                days = o.optInt("days", d.days).coerceIn(3, 7),
                showGoogle = o.optBoolean("gcal", d.showGoogle),
                showOutlook = o.optBoolean("ocal", d.showOutlook),
                calDays = o.optInt("calDays", d.calDays).coerceIn(1, 14),
                calEvents = o.optInt("calEvents", d.calEvents).coerceIn(1, 3),
                clockFont = o.optInt("font", d.clockFont).coerceIn(0, 3),
                vAlign = o.optInt("valign", d.vAlign).coerceIn(0, 2),
                skyBackground = o.optBoolean("sky", d.skyBackground),
                bgColor = o.optInt("bg", d.bgColor),
                opacity = o.optInt("opacity", d.opacity).coerceIn(0, 100),
                corner = o.optInt("corner", d.corner).coerceIn(0, CORNERS.size - 1),
                textColor = o.optInt("text", d.textColor),
                accentColor = o.optInt("accent", d.accentColor),
                textScale = o.optInt("tscale", d.textScale).coerceIn(70, 150),
                iconScale = o.optInt("iscale", d.iconScale).coerceIn(60, 160),
                clockOpensAlarms = o.optBoolean("clockAlarms", d.clockOpensAlarms),
            )
        }
    }
}
