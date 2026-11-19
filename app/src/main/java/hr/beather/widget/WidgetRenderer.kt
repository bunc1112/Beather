package hr.beather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import hr.beather.R
import hr.beather.data.Forecast
import hr.beather.data.OpenMeteo
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.data.WeatherCodes
import hr.beather.data.WeatherCodes.deg
import hr.beather.data.WeatherRepository
import hr.beather.ui.MainActivity
import java.text.DateFormat
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object WidgetRenderer {

    private enum class Kind(val layout: Int) {
        SMALL(R.layout.widget_small),
        MEDIUM(R.layout.widget_medium),
        LARGE(R.layout.widget_large),
    }

    private val DAY_NAME = intArrayOf(R.id.d0_name, R.id.d1_name, R.id.d2_name, R.id.d3_name, R.id.d4_name)
    private val DAY_ICON = intArrayOf(R.id.d0_icon, R.id.d1_icon, R.id.d2_icon, R.id.d3_icon, R.id.d4_icon)
    private val DAY_MAX = intArrayOf(R.id.d0_max, R.id.d1_max, R.id.d2_max, R.id.d3_max, R.id.d4_max)
    private val DAY_MIN = intArrayOf(R.id.d0_min, R.id.d1_min, R.id.d2_min, R.id.d3_min, R.id.d4_min)

    private val HOUR_TIME = intArrayOf(R.id.h0_time, R.id.h1_time, R.id.h2_time, R.id.h3_time, R.id.h4_time, R.id.h5_time)
    private val HOUR_ICON = intArrayOf(R.id.h0_icon, R.id.h1_icon, R.id.h2_icon, R.id.h3_icon, R.id.h4_icon, R.id.h5_icon)
    private val HOUR_TEMP = intArrayOf(R.id.h0_temp, R.id.h1_temp, R.id.h2_temp, R.id.h3_temp, R.id.h4_temp, R.id.h5_temp)
    private val HOUR_POP = intArrayOf(R.id.h0_pop, R.id.h1_pop, R.id.h2_pop, R.id.h3_pop, R.id.h4_pop, R.id.h5_pop)

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, BeatherWidgetProvider::class.java))
            .forEach { update(ctx, mgr, it) }
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val loc = Store.widgetLocation(ctx, id)
        val fc = loc?.let { WeatherRepository.cached(ctx, it) }

        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the launcher picks the layout that fits as the user resizes.
            RemoteViews(
                mapOf(
                    SizeF(100f, 80f) to build(ctx, Kind.SMALL, id, loc, fc),
                    SizeF(220f, 100f) to build(ctx, Kind.MEDIUM, id, loc, fc),
                    SizeF(220f, 210f) to build(ctx, Kind.LARGE, id, loc, fc),
                )
            )
        } else {
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val kind = when {
                w in 1 until 220 -> Kind.SMALL
                h >= 210 -> Kind.LARGE
                else -> Kind.MEDIUM
            }
            build(ctx, kind, id, loc, fc)
        }
        mgr.updateAppWidget(id, views)
    }

    private fun build(ctx: Context, kind: Kind, id: Int, loc: SavedLocation?, fc: Forecast?): RemoteViews {
        val v = RemoteViews(ctx.packageName, kind.layout)
        v.setOnClickPendingIntent(R.id.root, openApp(ctx, id))
        if (kind != Kind.SMALL) v.setOnClickPendingIntent(R.id.refresh, refresh(ctx, id))

        v.setTextViewText(R.id.loc_name, loc?.name ?: ctx.getString(R.string.no_location))

        if (fc == null) {
            v.setTextViewText(R.id.cur_temp, "--°")
            v.setTextViewText(R.id.cur_desc, ctx.getString(R.string.loading))
            v.setTextViewText(R.id.cur_hilo, "")
            return v
        }

        val c = fc.current
        val today = fc.days.firstOrNull()
        v.setInt(R.id.root, "setBackgroundResource", if (c.isDay) R.drawable.widget_bg_day else R.drawable.widget_bg_night)
        v.setImageViewResource(R.id.cur_icon, WeatherCodes.icon(c.code, c.isDay))
        v.setTextViewText(R.id.cur_temp, deg(c.temp))
        v.setTextViewText(R.id.cur_desc, WeatherCodes.describe(ctx, c.code))
        v.setTextViewText(
            R.id.cur_hilo,
            if (today != null) "↑${deg(today.max)}  ↓${deg(today.min)}" else ""
        )

        if (kind == Kind.SMALL) return v

        v.setTextViewText(R.id.updated, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fc.fetchedAt)))

        // Details line: feels like · wind (+ bura/jugo) · humidity
        val details = buildList {
            c.feelsLike?.let { add(ctx.getString(R.string.feels_like, deg(it))) }
            if (c.wind != null) {
                val dir = c.windDir?.let { WeatherCodes.windDir(ctx, it) + " " } ?: ""
                val gust = c.gusts?.takeIf { it >= c.wind + 10 }?.let { " (" + ctx.getString(R.string.gusts, it.roundToInt()) + ")" } ?: ""
                val local = loc?.let { WeatherCodes.adriaticWind(ctx, it.lat, it.lon, c.windDir, c.wind) }?.let { "$it " } ?: ""
                add("$local$dir${c.wind.roundToInt()} km/h$gust")
            }
            c.humidity?.let { add("💧$it%") }
        }
        v.setTextViewText(R.id.cur_details, details.joinToString(" · "))

        // Daily row
        val locale = Locale.getDefault()
        for (i in DAY_NAME.indices) {
            val d = fc.days.getOrNull(i)
            if (d == null) {
                v.setViewVisibility(DAY_NAME[i], View.INVISIBLE)
                v.setViewVisibility(DAY_ICON[i], View.INVISIBLE)
                continue
            }
            val name = if (i == 0) ctx.getString(R.string.today)
            else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) }
            v.setTextViewText(DAY_NAME[i], name)
            v.setImageViewResource(DAY_ICON[i], WeatherCodes.icon(d.code, true))
            v.setTextViewText(DAY_MAX[i], deg(d.max))
            v.setTextViewText(DAY_MIN[i], deg(d.min))
        }

        if (kind == Kind.LARGE) {
            // Next 12 hours in 2-hour steps
            for (i in HOUR_TIME.indices) {
                val h = fc.hours.getOrNull(i * 2)
                if (h == null) {
                    v.setViewVisibility(HOUR_ICON[i], View.INVISIBLE)
                    continue
                }
                v.setTextViewText(HOUR_TIME[i], if (i == 0) ctx.getString(R.string.now) else "%02d".format(h.time.hour))
                v.setImageViewResource(HOUR_ICON[i], WeatherCodes.icon(h.code, h.isDay))
                v.setTextViewText(HOUR_TEMP[i], deg(h.temp))
                v.setTextViewText(HOUR_POP[i], h.pop?.takeIf { it >= 20 }?.let { "$it%" } ?: "")
            }
            val src = buildString {
                append(OpenMeteo.label(fc.model))
                c.station?.let { append(" · DHMZ ").append(it.name).append(" ").append(it.distanceKm.roundToInt()).append(" km") }
            }
            v.setTextViewText(R.id.source, src)
        }
        return v
    }

    private fun openApp(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, id,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun refresh(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, BeatherWidgetProvider::class.java).setAction(BeatherWidgetProvider.ACTION_REFRESH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
