package hr.beather.ui

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import hr.beather.BuildConfig
import hr.beather.R
import hr.beather.data.Store
import hr.beather.data.Updater
import kotlinx.coroutines.launch

/**
 * Checks GitHub for a newer Beather. [manual] = the user tapped "Check for updates"
 * (always checks, and says "up to date"); otherwise it runs at most every 12 hours.
 */
fun checkForUpdate(activity: AppCompatActivity, manual: Boolean) {
    if (!manual) {
        if (!Store.autoUpdateCheck(activity)) return
        if (System.currentTimeMillis() - Store.lastUpdateCheck(activity) < 12 * 3600_000L) return
    }
    Store.setLastUpdateCheck(activity, System.currentTimeMillis())
    activity.lifecycleScope.launch {
        val r = Updater.latest()
        when {
            r == null -> if (manual) Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
            !Updater.isNewer(r) -> if (manual) Toast.makeText(activity, R.string.up_to_date, Toast.LENGTH_SHORT).show()
            else -> offerUpdate(activity, r)
        }
    }
}

private fun offerUpdate(activity: AppCompatActivity, r: Updater.Release) {
    if (activity.isFinishing) return
    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.update_available)
        .setMessage(activity.getString(R.string.update_message, r.tag.removePrefix("v"), BuildConfig.VERSION_NAME))
        .setNegativeButton(R.string.later, null)
        .setPositiveButton(R.string.update) { _, _ -> downloadAndInstall(activity, r) }
        .show()
}

private fun downloadAndInstall(activity: AppCompatActivity, r: Updater.Release) {
    if (!Updater.canInstall(activity)) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.allow_installs_title)
            .setMessage(R.string.allow_installs_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open_settings) { _, _ -> Updater.openInstallPermission(activity) }
            .show()
        return
    }
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    val label = TextView(activity).apply { text = activity.getString(R.string.downloading, 0) }
    val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = false }
    val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(label); addView(bar)
    }
    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.update)
        .setView(box)
        .setCancelable(false)
        .show()
    activity.lifecycleScope.launch {
        val file = runCatching {
            Updater.download(activity, r) { pct ->
                bar.progress = pct
                label.text = activity.getString(R.string.downloading, pct)
            }
        }.getOrNull()
        dialog.dismiss()
        if (file == null) {
            Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
        } else {
            Updater.install(activity, file)
        }
    }
}
