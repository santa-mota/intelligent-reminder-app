package com.santamota.reminder.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.domain.ReminderType

/**
 * Sits between the engine and `AlarmManager`. Abstracts so tests can swap a
 * fake in. Owns the conversion from [Reminder] to a PendingIntent and the
 * choice of `setAlarmClock` (ALARM) vs `setExactAndAllowWhileIdle`
 * (NOTIFICATION).
 */
interface AlarmScheduler {
    fun schedule(reminder: Reminder)
    fun cancel(reminderId: String)
    fun rescheduleAll(reminders: List<Reminder>)
}

class AndroidAlarmScheduler(
    private val context: Context,
) : AlarmScheduler {

    private val am: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(reminder: Reminder) {
        val triggerMs = reminder.triggerAt.toInstant().toEpochMilli()
        if (triggerMs <= System.currentTimeMillis()) return
        val pi = pendingIntent(reminder)

        when (reminder.type) {
            ReminderType.ALARM -> {
                // setAlarmClock surfaces in lockscreen, bypasses doze.
                val showIntent = launchIntent(reminder)
                val showPI = PendingIntent.getActivity(
                    context, reminder.id.value.hashCode(),
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMs, showPI), pi)
            }
            ReminderType.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    // Permission missing — fall back to inexact. Better late
                    // than never.
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                }
            }
        }
    }

    override fun cancel(reminderId: String) {
        val pi = PendingIntent.getBroadcast(
            context, reminderId.hashCode(),
            Intent(context, AlarmFiredReceiver::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_REMINDER_ID, reminderId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) am.cancel(pi)
    }

    override fun rescheduleAll(reminders: List<Reminder>) {
        reminders.forEach { schedule(it) }
    }

    private fun pendingIntent(reminder: Reminder): PendingIntent {
        val intent = Intent(context, AlarmFiredReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_REMINDER_ID, reminder.id.value)
        return PendingIntent.getBroadcast(
            context, reminder.id.value.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchIntent(reminder: Reminder): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_REMINDER_ID, reminder.id.value)
            ?: Intent()

    companion object {
        const val ACTION_FIRE = "com.santamota.reminder.ALARM_FIRE"
        const val EXTRA_REMINDER_ID = "reminderId"
    }
}
