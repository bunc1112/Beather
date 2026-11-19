package hr.beather.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import hr.beather.data.Store
import hr.beather.data.WeatherRepository
import hr.beather.work.RefreshWorker

class BeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.update(ctx, mgr, it) }
        RefreshWorker.schedulePeriodic(ctx)
        // Only hit the network if something is actually stale.
        val stale = ids.any { id ->
            val loc = Store.widgetLocation(ctx, id)
            loc == null || WeatherRepository.cacheAgeMs(ctx, loc) > 15 * 60 * 1000L
        }
        if (stale) RefreshWorker.refreshNow(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRenderer.update(ctx, mgr, id)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Store.removeWidget(ctx, it) }
    }

    override fun onEnabled(ctx: Context) = RefreshWorker.schedulePeriodic(ctx)

    override fun onDisabled(ctx: Context) = RefreshWorker.cancelAll(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_REFRESH -> RefreshWorker.refreshNow(ctx)
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> WidgetRenderer.updateAll(ctx)
        }
    }

    companion object {
        const val ACTION_REFRESH = "hr.beather.action.REFRESH"
    }
}
