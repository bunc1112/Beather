package hr.beather.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import hr.beather.R
import hr.beather.data.CalSource
import hr.beather.data.Calendars
import hr.beather.data.Forecast
import hr.beather.data.OpenMeteo
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.data.Units
import hr.beather.data.WeatherCodes
import hr.beather.data.WeatherCodes.deg
import hr.beather.data.WeatherRepository
import hr.beather.data.WidgetPrefs
import hr.beather.ui.ForecastActivity
import hr.beather.ui.MainActivity
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object WidgetRenderer {

    private val CLOCKS = intArrayOf(R.id.clock_0, R.id.clock_1, R.id.clock_2, R.id.clock_3)

    private val DAY_CELL = intArrayOf(R.id.d0_cell, R.id.d1_cell, R.id.d2_cell, R.id.d3_cell, R.id.d4_cell, R.id.d5_cell, R.id.d6_cell)
    private val DAY_NAME = intArrayOf(R.id.d0_name, R.id.d1_name, R.id.d2_name, R.id.d3_name, R.id.d4_name, R.id.d5_name, R.id.d6_name)
    private val DAY_ICON = intArrayOf(R.id.d0_icon, R.id.d1_icon, R.id.d2_icon, R.id.d3_icon, R.id.d4_icon, R.id.d5_icon, R.id.d6_icon)
    private val DAY_TEMP = intArrayOf(R.id.d0_temp, R.id.d1_temp, R.id.d2_temp, R.id.d3_temp, R.id.d4_temp, R.id.d5_temp, R.id.d6_temp)

    private val HOUR_TIME = intArrayOf(R.id.h0_time, R.id.h1_time, R.id.h2_time, R.id.h3_time, R.id.h4_time, R.id.h5_time)
    private val HOUR_ICON = intArrayOf(R.id.h0_icon, R.id.h1_icon, R.id.h2_icon, R.id.h3_icon, R.id.h4_icon, R.id.h5_icon)
    private val HOUR_TEMP = intArrayOf(R.id.h0_temp, R.id.h1_temp, R.id.h2_temp, R.id.h3_temp, R.id.h4_temp, R.id.h5_temp)
    private val HOUR_POP = intArrayOf(R.id.h0_pop, R.id.h1_pop, R.id.h2_pop, R.id.h3_pop, R.id.h4_pop, R.id.h5_pop)

    private val CORNER_SOLID = intArrayOf(R.drawable.bg_solid_r0, R.drawable.bg_solid_r12, R.drawable.bg_solid_r24, R.drawable.bg_solid_r32)
    private val CORNER_DAY = intArrayOf(R.drawable.bg_sky_day_r0, R.drawable.bg_sky_day_r12, R.drawable.bg_sky_day_r24, R.drawable.bg_sky_day_r32)
    private val CORNER_NIGHT = intArrayOf(R.drawable.bg_sky_night_r0, R.drawable.bg_sky_night_r12, R.drawable.bg_sky_night_r24, R.drawable.bg_sky_night_r32)

    // Text views with their base size (sp). Primary = main text colour, secondary = dimmed, accent = accent colour.
    private val MAIN_PRIMARY: List<Pair<Int, Float>> = buildList {
        add(R.id.loc_name to 17f); add(R.id.alarm_text to 15f); add(R.id.date_text to 15f)
        add(R.id.cal_g_text to 15f); add(R.id.cal_o_text to 15f)
        CLOCKS.forEach { add(it to 60f) }
        add(R.id.cur_temp to 48f); add(R.id.cur_max to 18f); add(R.id.cur_desc to 17f)
        HOUR_TEMP.forEach { add(it to 13f) }
        DAY_NAME.forEach { add(it to 14f) }
        DAY_TEMP.forEach { add(it to 15f) }
    }
    private val MAIN_SECONDARY: List<Pair<Int, Float>> = buildList {
        add(R.id.updated to 11f); add(R.id.cur_details to 12f); add(R.id.cur_min to 18f); add(R.id.source to 9f)
        HOUR_TIME.forEach { add(it to 12f) }
    }
    private val MAIN_ACCENT: List<Pair<Int, Float>> = HOUR_POP.map { it to 11f }

    private val COMPACT_PRIMARY = listOf(R.id.loc_name to 14f, R.id.cur_temp to 36f, R.id.cur_desc to 13f)
    private val COMPACT_SECONDARY = listOf(R.id.cur_hilo to 13f)

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, BeatherWidgetProvider::class.java))
            .forEach { update(ctx, mgr, it) }
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val loc = Store.widgetLocation(ctx, id)
        val fc = loc?.let { WeatherRepository.cached(ctx, it) }
        val prefs = Store.widgetPrefs(ctx, id)

        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the launcher swaps layouts as the widget is resized.
            RemoteViews(
                mapOf(
                    SizeF(60f, 60f) to build(ctx, true, id, loc, fc, prefs),
                    SizeF(200f, 100f) to build(ctx, false, id, loc, fc, prefs),
                )
            )
        } else {
            val w = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            build(ctx, w in 1 until 200, id, loc, fc, prefs)
        }
        mgr.updateAppWidget(id, views)
    }

    /** Used by the widget settings screen for its live preview. */
    fun preview(ctx: Context, loc: SavedLocation?, prefs: WidgetPrefs, compact: Boolean = false): RemoteViews {
        val fc = loc?.let { WeatherRepository.cached(ctx, it) }
        return build(ctx, compact, AppWidgetManager.INVALID_APPWIDGET_ID, loc, fc, prefs)
    }

    private fun build(
        ctx: Context, compact: Boolean, id: Int, loc: SavedLocation?, fc: Forecast?, p: WidgetPrefs,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (compact) R.layout.widget_compact else R.layout.widget_main)
        val isDay = fc?.current?.isDay ?: true

        // ---- Background ----
        val corner = p.corner.coerceIn(0, CORNER_SOLID.size - 1)
        val bgRes = when {
            !p.skyBackground -> CORNER_SOLID[corner]
            isDay -> CORNER_DAY[corner]
            else -> CORNER_NIGHT[corner]
        }
        v.setImageViewResource(R.id.bg, bgRes)
        v.setInt(R.id.bg, "setColorFilter", if (p.skyBackground) 0 else (p.bgColor or 0xFF000000.toInt()))
        v.setInt(R.id.bg, "setImageAlpha", p.opacity * 255 / 100)

        // ---- Colours & sizes ----
        val primary = p.textColor or 0xFF000000.toInt()
        val secondary = (primary and 0x00FFFFFF) or 0xB3000000.toInt()
        val scale = p.textScale / 100f
        fun style(list: List<Pair<Int, Float>>, color: Int) = list.forEach { (vid, sp) ->
            v.setTextColor(vid, color)
            v.setTextViewTextSize(vid, TypedValue.COMPLEX_UNIT_SP, sp * scale)
        }
        if (compact) {
            style(COMPACT_PRIMARY, primary); style(COMPACT_SECONDARY, secondary)
        } else {
            style(MAIN_PRIMARY, primary); style(MAIN_SECONDARY, secondary)
            style(MAIN_ACCENT, p.accentColor or 0xFF000000.toInt())
            v.setInt(R.id.alarm_icon, "setColorFilter", primary)
            v.setInt(R.id.refresh, "setColorFilter", primary)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val s = p.iconScale / 100f
            fun size(vid: Int, dp: Float) {
                v.setViewLayoutWidth(vid, dp * s, TypedValue.COMPLEX_UNIT_DIP)
                v.setViewLayoutHeight(vid, dp * s, TypedValue.COMPLEX_UNIT_DIP)
            }
            if (compact) size(R.id.cur_icon, 46f) else {
                size(R.id.cur_icon, 64f)
                DAY_ICON.forEach { size(it, 40f) }
                HOUR_ICON.forEach { size(it, 30f) }
            }
        }

        // ---- Clicks ----
        v.setOnClickPendingIntent(R.id.root, openForecast(ctx, id, loc, 0))
        if (!compact) {
            v.setOnClickPendingIntent(R.id.refresh, refresh(ctx, id))
            v.setOnClickPendingIntent(R.id.clock_group, if (p.clockOpensAlarms) openAlarms(ctx, id) else openForecast(ctx, id, loc, 0))
            DAY_CELL.forEachIndexed { i, cell -> v.setOnClickPendingIntent(cell, openForecast(ctx, id, loc, i)) }
        }

        v.setTextViewText(R.id.loc_name, loc?.name ?: ctx.getString(R.string.no_location))
        return if (compact) fillCompact(ctx, v, fc) else fillMain(ctx, v, id, loc, fc, p)
    }

    private fun fillCompact(ctx: Context, v: RemoteViews, fc: Forecast?): RemoteViews {
        if (fc == null) {
            v.setTextViewText(R.id.cur_desc, ctx.getString(R.string.loading)); return v
        }
        val c = fc.current
        val today = fc.upcomingDays().firstOrNull()
        v.setImageViewResource(R.id.cur_icon, WeatherCodes.icon(c.code, c.isDay))
        v.setTextViewText(R.id.cur_temp, deg(c.temp))
        v.setTextViewText(R.id.cur_desc, WeatherCodes.describe(ctx, c.code))
        v.setTextViewText(R.id.cur_hilo, today?.let { "↑${deg(it.max)}  ↓${deg(it.min)}" } ?: "")
        return v
    }

    private fun fillMain(ctx: Context, v: RemoteViews, id: Int, loc: SavedLocation?, fc: Forecast?, p: WidgetPrefs): RemoteViews {
        val locale = Locale.getDefault()

        // ---- Header: location · next alarm · updated · refresh ----
        val alarm = if (p.showAlarm) nextAlarm(ctx) else null
        v.setViewVisibility(R.id.loc_name, if (p.showLocation) View.VISIBLE else View.INVISIBLE)
        v.setViewVisibility(R.id.alarm_group, if (alarm != null) View.VISIBLE else View.GONE)
        alarm?.let { v.setTextViewText(R.id.alarm_text, it) }
        v.setViewVisibility(R.id.updated, if (p.showUpdated && fc != null) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.refresh, if (p.showUpdated) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.header, if (p.showLocation || alarm != null || p.showUpdated) View.VISIBLE else View.GONE)
        fc?.let { v.setTextViewText(R.id.updated, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.fetchedAt))) }

        // ---- Calendar rows: Google on one, Outlook on the other ----
        fillCalendar(ctx, v, id, p, CalSource.GOOGLE, p.showGoogle, R.id.cal_g_row, R.id.cal_g_icon, R.id.cal_g_text)
        fillCalendar(ctx, v, id, p, CalSource.OUTLOOK, p.showOutlook, R.id.cal_o_row, R.id.cal_o_icon, R.id.cal_o_text)

        // ---- Clock & date ----
        CLOCKS.forEachIndexed { i, cid ->
            v.setViewVisibility(cid, if (p.showClock && i == p.clockFont) View.VISIBLE else View.GONE)
        }
        v.setViewVisibility(R.id.date_text, if (p.showDate) View.VISIBLE else View.GONE)
        val clockShown = p.showClock || p.showDate
        v.setViewVisibility(R.id.clock_group, if (clockShown) View.VISIBLE else View.GONE)
        v.setInt(R.id.cur_group, "setGravity", if (clockShown) Gravity.END else Gravity.START)

        // ---- Sections on/off ----
        v.setViewVisibility(R.id.cur_desc, if (p.showDescription) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.hourly_row, if (p.showHourly) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.days_row, if (p.showDays) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.source, if (p.showSource && fc != null) View.VISIBLE else View.GONE)
        DAY_CELL.forEachIndexed { i, cell -> v.setViewVisibility(cell, if (i < p.days) View.VISIBLE else View.GONE) }

        if (fc == null) {
            v.setTextViewText(R.id.cur_temp, "--°")
            v.setTextViewText(R.id.cur_desc, ctx.getString(R.string.loading))
            v.setViewVisibility(R.id.cur_details, View.GONE)
            return v
        }

        // ---- Current ----
        val c = fc.current
        val days = fc.upcomingDays()
        val today = days.firstOrNull()
        v.setImageViewResource(R.id.cur_icon, WeatherCodes.icon(c.code, c.isDay))
        v.setTextViewText(R.id.cur_temp, deg(c.temp))
        v.setTextViewText(R.id.cur_max, today?.let { deg(it.max) } ?: "")
        v.setTextViewText(R.id.cur_min, today?.let { deg(it.min) } ?: "")
        v.setTextViewText(R.id.cur_desc, WeatherCodes.describe(ctx, c.code))

        val details = buildList {
            c.feelsLike?.let { add(ctx.getString(R.string.feels_like, deg(it))) }
            if (c.wind != null) {
                val dir = c.windDir?.let { WeatherCodes.windDir(ctx, it) + " " } ?: ""
                val gust = c.gusts?.takeIf { it >= c.wind + 10 }
                    ?.let { " (" + ctx.getString(R.string.gusts, Units.windNumber(it)) + ")" } ?: ""
                val local = loc?.let { WeatherCodes.adriaticWind(ctx, it.lat, it.lon, c.windDir, c.wind) }?.let { "$it " } ?: ""
                add("$local$dir${Units.windSpeed(c.wind)}$gust")
            }
            c.humidity?.let { add("💧$it%") }
        }
        v.setTextViewText(R.id.cur_details, details.joinToString(" · "))
        v.setViewVisibility(R.id.cur_details, if (p.showDetails && details.isNotEmpty()) View.VISIBLE else View.GONE)

        // ---- Hourly: next 12 hours, every 2 hours ----
        val hours = fc.upcomingHours()
        for (i in HOUR_TIME.indices) {
            val h = hours.getOrNull(i * 2)
            if (h == null) {
                v.setViewVisibility(HOUR_ICON[i], View.INVISIBLE); continue
            }
            v.setViewVisibility(HOUR_ICON[i], View.VISIBLE)
            v.setTextViewText(HOUR_TIME[i], if (i == 0) ctx.getString(R.string.now) else "%02d".format(h.time.hour))
            v.setImageViewResource(HOUR_ICON[i], WeatherCodes.icon(h.code, h.isDay))
            v.setTextViewText(HOUR_TEMP[i], deg(h.temp))
            v.setTextViewText(HOUR_POP[i], h.pop?.takeIf { it >= 20 }?.let { "$it%" } ?: "")
        }

        // ---- Days ----
        for (i in DAY_CELL.indices) {
            val d = days.getOrNull(i)
            if (d == null) {
                v.setViewVisibility(DAY_CELL[i], if (i < p.days) View.INVISIBLE else View.GONE); continue
            }
            v.setTextViewText(DAY_NAME[i], d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale).trimEnd('.'))
            v.setImageViewResource(DAY_ICON[i], WeatherCodes.icon(d.code, true))
            v.setTextViewText(DAY_TEMP[i], "${deg(d.max)}/${deg(d.min)}")
        }

        v.setTextViewText(R.id.source, buildString {
            append(OpenMeteo.label(fc.model))
            c.station?.let { append(" · DHMZ ").append(it.name).append(" ").append(it.distanceKm.roundToInt()).append(" km") }
        })
        return v
    }

    private fun fillCalendar(
        ctx: Context, v: RemoteViews, id: Int, p: WidgetPrefs, source: CalSource, show: Boolean,
        row: Int, icon: Int, text: Int,
    ) {
        val events = if (show) Calendars.upcoming(ctx, source, p.calDays, p.calEvents) else emptyList()
        if (events.isEmpty()) {
            v.setViewVisibility(row, View.GONE); return
        }
        v.setViewVisibility(row, View.VISIBLE)
        val today = ctx.getString(R.string.today)
        val tomorrow = ctx.getString(R.string.tomorrow)
        v.setTextViewText(text, events.joinToString("   ·   ") { Calendars.format(ctx, it, today, tomorrow) })
        v.setInt(icon, "setColorFilter", events.first().color or 0xFF000000.toInt())
        val intent = Calendars.openIntent(ctx, source, events.first()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val code = id * 16 + if (source == CalSource.GOOGLE) 13 else 14
        v.setOnClickPendingIntent(row, PendingIntent.getActivity(ctx, code, intent, flags()))
    }

    private fun nextAlarm(ctx: Context): String? {
        val info = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "EEE H:mm" else "EEE h:mm a"
        return Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openForecast(ctx: Context, id: Int, loc: SavedLocation?, day: Int): PendingIntent {
        val intent = if (loc == null) Intent(ctx, MainActivity::class.java)
        else Intent(ctx, ForecastActivity::class.java)
            .putExtra(ForecastActivity.EXTRA_LOCATION, loc.id)
            .putExtra(ForecastActivity.EXTRA_DAY, day)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, id * 16 + day + 1, intent, flags())
    }

    private fun openAlarms(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, id * 16 + 12, Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags(),
    )

    private fun refresh(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, BeatherWidgetProvider::class.java).setAction(BeatherWidgetProvider.ACTION_REFRESH),
        flags(),
    )
}
