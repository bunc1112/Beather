package hr.beather.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class CalSource { GOOGLE, OUTLOOK, HIDDEN }

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val color: Int,
)

data class CalEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
)

/**
 * Reads events from the phone's calendar storage. Google accounts sync there
 * automatically; the Outlook app does too when "Sync calendars" is on. No
 * separate Google/Microsoft sign-in is needed.
 */
object Calendars {
    const val OUTLOOK_PACKAGE = "com.microsoft.office.outlook"
    private val MS_DOMAINS = listOf("@outlook.", "@hotmail.", "@live.", "@msn.")

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun list(ctx: Context): List<DeviceCalendar> {
        if (!hasPermission(ctx)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val out = mutableListOf<DeviceCalendar>()
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection,
                "${CalendarContract.Calendars.VISIBLE} = 1", null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += DeviceCalendar(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        account = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        color = c.getInt(4),
                    )
                }
            }
        }
        return out
    }

    /** Best guess for which row a calendar belongs to. The user can override it in Settings. */
    fun autoSource(cal: DeviceCalendar): CalSource {
        val type = cal.accountType.lowercase(Locale.ROOT)
        val account = cal.account.lowercase(Locale.ROOT)
        val microsoft = listOf("outlook", "microsoft", "exchange", "office").any { it in type } ||
            MS_DOMAINS.any { it in account }
        return when {
            microsoft -> CalSource.OUTLOOK
            type == "com.google" -> CalSource.GOOGLE
            else -> CalSource.HIDDEN
        }
    }

    fun source(ctx: Context, cal: DeviceCalendar): CalSource = Store.calendarSource(ctx, cal.id) ?: autoSource(cal)

    /** Upcoming (and ongoing) events for one row, soonest first. */
    fun upcoming(ctx: Context, source: CalSource, days: Int, max: Int): List<CalEvent> {
        if (!hasPermission(ctx) || source == CalSource.HIDDEN) return emptyList()
        val ids = list(ctx).filter { source(ctx, it) == source }.map { it.id }.toSet()
        if (ids.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 24 * 3600_000L) // catch all-day events of today
            ContentUris.appendId(it, now + days * 24 * 3600_000L)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(5) !in ids) continue
                    val allDay = c.getInt(4) == 1
                    val end = c.getLong(3)
                    // All-day events are stored in UTC; compare their end with local midnight.
                    val ended = if (allDay) {
                        val lastDay = Instant.ofEpochMilli(end - 1).atZone(ZoneOffset.UTC).toLocalDate()
                        lastDay.isBefore(LocalDate.now())
                    } else end <= now
                    if (ended) continue
                    out += CalEvent(
                        eventId = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "—",
                        begin = c.getLong(2),
                        end = end,
                        allDay = allDay,
                        color = c.getInt(6),
                    )
                }
            }
        }
        return out.take(max)
    }

    /** "Danas 9:00 Nokti", "Sutra Rođendan", "čet 26.11. 17:30 Sastanak". */
    fun format(ctx: Context, e: CalEvent, todayLabel: String, tomorrowLabel: String): String {
        val locale = Locale.getDefault()
        val zone = if (e.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(e.begin).atZone(zone)
        val today = LocalDate.now()
        val date = start.toLocalDate().let { if (e.allDay && it.isBefore(today)) today else it }
        val day = when (date) {
            today -> todayLabel
            today.plusDays(1) -> tomorrowLabel
            else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.') + " " +
                date.format(DateTimeFormatter.ofPattern(if (locale.language == "en") "M/d" else "d.M.", locale))
        }
        val time = if (e.allDay) "" else " " + start.format(
            DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm a", locale)
        )
        return "$day$time  ${e.title}"
    }

    /** Opens the event – in Outlook for the Outlook row when the app is installed. */
    fun openIntent(ctx: Context, source: CalSource, e: CalEvent?): Intent {
        if (source == CalSource.OUTLOOK) {
            ctx.packageManager.getLaunchIntentForPackage(OUTLOOK_PACKAGE)?.let { return it }
        }
        if (e == null) {
            return Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
        }
        return Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
    }
}
