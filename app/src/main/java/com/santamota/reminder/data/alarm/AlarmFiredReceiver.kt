package com.santamota.reminder.data.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.santamota.reminder.R
import com.santamota.reminder.data.db.AppDatabase
import com.santamota.reminder.data.db.toDomain
import com.santamota.reminder.data.db.toEntity
import com.santamota.reminder.domain.nextOccurrenceFrom
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when an alarm goes off. Loads the reminder row, posts a notification,
 * and (if recurring) reschedules the next occurrence.
 */
class AlarmFiredReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAlarmScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_REMINDER_ID) ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entry = ReminderAlarmEntryPoint.resolve(context)
                val row = entry.db.reminderDao().byId(id) ?: return@launch
                postNotification(context, id, row.title, row.description)

                // Reschedule next occurrence for recurring reminders.
                if (row.recurrencePattern != null) {
                    val domain = row.toDomain()
                    val next = domain.nextOccurrenceFrom(
                        java.time.ZonedDateTime.now(domain.triggerAt.zone).plusSeconds(1)
                    )
                    if (next != null) {
                        val updated = domain.copy(triggerAt = next)
                        entry.db.reminderDao().upsert(updated.toEntity())
                        entry.scheduler.schedule(updated)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun postNotification(context: Context, id: String, title: String, body: String?) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_NOTIFY, context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.notification_channel_desc) }
            )
        }
        val openIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.putExtra(AndroidAlarmScheduler.EXTRA_REMINDER_ID, id)
        val openPI = PendingIntent.getActivity(
            context, id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body ?: "")
            .setAutoCancel(true)
            .setContentIntent(openPI)
            .build()
        nm.notify(id.hashCode(), notif)
    }

    private companion object {
        const val CHANNEL_NOTIFY = "reminder_notifications"
    }
}

// Lightweight entry point shim — the receiver isn't part of the regular DI
// graph so we resolve dependencies via Hilt EntryPoint.
internal data class ReminderAlarmEntryPoint(
    val db: AppDatabase,
    val scheduler: AlarmScheduler,
) {
    companion object {
        fun resolve(context: Context): ReminderAlarmEntryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AlarmEntryPoint::class.java,
            ).let { ReminderAlarmEntryPoint(it.db(), it.scheduler()) }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface AlarmEntryPoint {
    fun db(): AppDatabase
    fun scheduler(): AlarmScheduler
}
