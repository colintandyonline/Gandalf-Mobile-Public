package group.ctdigital.gandalf.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Personal reminders only. Daily routines are notifications, not autonomous phone tasks. */
data class GandalfReminder(
    val id: Int,
    val title: String,
    val scheduled: Long,
    val daily: Boolean,
    val enabled: Boolean = true,
    val lastResult: String = ""
)

object GandalfReminderEngine {
    private const val KEY = "reminder_entries"
    private const val PREFS = "gandalf_reminder_store"
    private const val CHANNEL = "gandalf_tasks_routines"

    fun list(context: Context): List<GandalfReminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val json = try { JSONArray(raw) } catch (error: Exception) {
            throw IllegalStateException("Saved reminders could not be read; data was not overwritten.")
        }
        return (0 until json.length()).map { index ->
            val x = json.getJSONObject(index)
            GandalfReminder(
                x.getInt("id"),
                x.getString("title"),
                x.getLong("scheduled"),
                x.getBoolean("daily"),
                x.getBoolean("enabled"),
                x.optString("lastResult", "")
            )
        }
    }

    private fun persist(context: Context, list: List<GandalfReminder>) {
        val json = JSONArray()
        list.forEach { entry ->
            json.put(JSONObject().apply {
                put("id", entry.id)
                put("title", entry.title)
                put("scheduled", entry.scheduled)
                put("daily", entry.daily)
                put("enabled", entry.enabled)
                put("lastResult", entry.lastResult)
            })
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).commit()) {
            "Unable to save the reminder"
        }
    }

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun nextDailyTime(reference: Long): Long {
        val referenceTime = Calendar.getInstance().apply { timeInMillis = reference }
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, referenceTime.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, referenceTime.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DATE, 1)
        }.timeInMillis
    }

    fun add(context: Context, title: String, whenMillis: Long, daily: Boolean): String {
        require(canNotify(context)) {
            "Allow Android notifications first. Nothing was scheduled."
        }
        val name = title.trim()
        require(name.isNotEmpty() && name.length <= 160) {
            "Enter a reminder description of up to 160 characters."
        }
        require(whenMillis > System.currentTimeMillis()) { "Choose a future date or time." }
        val existing = list(context)
        require(existing.size < 100) { "Limit of 100 tasks and routines reached." }
        val id = (existing.maxOfOrNull { it.id } ?: 0) + 1
        val item = GandalfReminder(id, name, whenMillis, daily)
        persist(context, existing + item)
        try {
            setAlarm(context, item)
        } catch (error: Exception) {
            persist(context, existing)
            throw IllegalStateException("Android refused the schedule: " + error.message)
        }
        return if (daily) "Daily routine reminder created." else "One-time task reminder created."
    }

    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        val old = list(context)
        val previous = old.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Reminder not found.")
        if (enabled && !previous.daily && previous.scheduled <= System.currentTimeMillis()) {
            throw IllegalArgumentException("The scheduled time passed. Create a new task.")
        }
        val replacement = previous.copy(
            enabled = enabled,
            scheduled = if (enabled && previous.daily) {
                nextDailyTime(previous.scheduled)
            } else previous.scheduled
        )
        persist(context, old.map { if (it.id == id) replacement else it })
        if (enabled) setAlarm(context, replacement) else cancel(context, id)
    }

    fun remove(context: Context, id: Int) {
        val old = list(context)
        require(old.any { it.id == id }) { "Reminder no longer exists." }
        persist(context, old.filterNot { it.id == id })
        cancel(context, id)
    }

    private fun alarmIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, GandalfReminderReceiver::class.java).putExtra("reminder_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun setAlarm(context: Context, reminder: GandalfReminder) {
        val service = context.getSystemService(AlarmManager::class.java)
        // Inexact alarm. Doze may delay delivery; no precision claim.
        service.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.scheduled,
            alarmIntent(context, reminder.id)
        )
    }

    private fun cancel(context: Context, id: Int) =
        context.getSystemService(AlarmManager::class.java)
            .cancel(alarmIntent(context, id))

    fun fire(context: Context, id: Int) {
        val item = list(context).firstOrNull { it.id == id && it.enabled } ?: return
        var outcome = "Notification permission unavailable"
        if (canNotify(context)) {
            try {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Gandalf reminders", NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
                val open = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                manager.notify(
                    item.id,
                    Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(if (item.daily) "Gandalf routine" else "Gandalf task")
                        .setContentText(item.title)
                        .setStyle(Notification.BigTextStyle().bigText(item.title))
                        .setContentIntent(open)
                        .setAutoCancel(true).build()
                )
                outcome = "Notification delivered to Android"
            } catch (error: Exception) {
                outcome = "Notification failed: " + error.message
            }
        }
        val updated = item.copy(
            scheduled = if (item.daily) nextDailyTime(item.scheduled) else item.scheduled,
            enabled = item.daily,
            lastResult = outcome
        )
        persist(context, list(context).map { if (it.id == id) updated else it })
        if (item.daily) setAlarm(context, updated)
    }

    fun restore(context: Context) {
        val current = list(context)
        for (item in current.filter { it.enabled }) {
            if (item.daily) {
                setAlarm(context, item.copy(scheduled = nextDailyTime(item.scheduled)))
            } else if (item.scheduled > System.currentTimeMillis()) {
                setAlarm(context, item)
            } else {
                persist(context, list(context).map {
                    if (it.id == item.id) it.copy(
                        enabled = false,
                        lastResult = "Missed while device was off"
                    ) else it
                })
            }
        }
    }
}

class GandalfReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("reminder_id", -1)
        if (id <= 0) return
        try {
            GandalfReminderEngine.fire(context, id)
        } catch (error: Exception) {
            android.util.Log.e("GandalfReminder", "Reminder delivery failed", error)
        }
    }
}

class GandalfReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED
            )
        ) {
            try {
                GandalfReminderEngine.restore(context)
            } catch (error: Exception) {
                android.util.Log.e("GandalfReminder", "Could not restore schedules", error)
            }
        }
    }
}
