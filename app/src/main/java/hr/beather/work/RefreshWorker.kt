package hr.beather.work

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import hr.beather.data.SavedLocation
import hr.beather.data.Store
import hr.beather.data.WeatherRepository
import hr.beather.widget.BeatherWidgetProvider
import hr.beather.widget.WidgetRenderer
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids: IntArray = mgr.getAppWidgetIds(ComponentName(ctx, BeatherWidgetProvider::class.java))
        val locations: List<SavedLocation> = ids.toList()
            .mapNotNull { widgetId: Int -> Store.widgetLocation(ctx, widgetId) }
            .distinctBy { loc: SavedLocation -> loc.id }

        var failures = 0
        for (loc in locations) {
            if (runCatching { WeatherRepository.refresh(ctx, loc) }.isFailure) failures++
        }
        WidgetRenderer.updateAll(ctx)

        return if (locations.isNotEmpty() && failures == locations.size && runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "beather_periodic"
        private const val NOW = "beather_now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(ctx: Context) {
            schedule(ctx, ExistingPeriodicWorkPolicy.KEEP)
        }

        /** Call after the refresh interval setting changes. */
        fun reschedule(ctx: Context) = schedule(ctx, ExistingPeriodicWorkPolicy.UPDATE)

        private fun schedule(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
            val minutes = Store.refreshMinutes(ctx).toLong().coerceAtLeast(15)
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, policy, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(ctx).cancelUniqueWork(NOW)
        }
    }
}
