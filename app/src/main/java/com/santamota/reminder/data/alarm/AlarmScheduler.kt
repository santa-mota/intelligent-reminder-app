package com.santamota.reminder.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.domain.ReminderType
import java.time.DayOfWeek
import java.util.Calendar

/**
 * Sits between the engine and the OS scheduling APIs. Two paths:
 *
 *   - ALARM → hand off to the system Clock app via [AlarmClock.ACTION_SET_ALARM].
 *     The user gets a *real* Android alarm: lockscreen takeover, alarm
 *     sound, snooze, visible in their Clock app's alarm list. Survives
 *     reboot for free. We don't fire it ourselves.
 *
 *   - NOTIFICATION → `AlarmManager.setExactAndAllowWhileIdle` + our
 *     [AlarmFiredReceiver] posts a heads-up notification when it fires.
 *
 * The split matches user expectation: "set an alarm" should *be* an alarm
 * (it goes to the alarm app), "remind me about X" should be a notification
 * the user can dismiss without ceremony.
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
        if (triggerMs <= System.currentTimeMillis() && reminder.recurrence == null) return

        when (reminder.type) {
            ReminderType.ALARM -> scheduleAsSystemAlarm(reminder)
            ReminderType.NOTIFICATION -> scheduleAsAppAlarm(reminder, triggerMs)
        }
    }

    /**
     * Hands the alarm off to the system Clock app. The user sees this in
     * their alarm list and gets the full system experience when it fires.
     *
     * Two limitations of this API worth knowing:
     *   - It only supports time-of-day + optional days-of-week; one-time
     *     alarms with a specific date aren't directly expressible. We pass
     *     no days for one-time, which makes the alarm fire once at the next
     *     occurrence of that time.
     *   - We can't programmatically cancel it. That's actually fine — the
     *     user manages their own alarms in their Clock app. Our [cancel]
     *     only cancels the app-side notification path.
     */
    private fun scheduleAsSystemAlarm(reminder: Reminder) {
        val time = reminder.triggerAt.toLocalTime()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, reminder.title)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            // Without this flag the call from a non-Activity context fails.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            recurrenceDays(reminder.recurrence)?.let {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, it)
            }
        }
        // startActivity to the Clock app. SKIP_UI makes this no-prompt.
        runCatching { context.startActivity(intent) }.onFailure {
            android.util.Log.w(
                "AlarmScheduler",
                "AlarmClock intent failed (${it.message}); falling back to app alarm",
            )
            // Last-resort fallback: schedule through our own path. The user
            // won't get the full system alarm but at least a notification.
            scheduleAsAppAlarm(reminder, reminder.triggerAt.toInstant().toEpochMilli())
        }
    }

    /**
     * Schedules a precise wake-up + posts a notification on fire. Used for
     * NOTIFICATION-type reminders and as a fallback when the AlarmClock
     * hand-off isn't possible.
     */
    private fun scheduleAsAppAlarm(reminder: Reminder, triggerMs: Long) {
        if (triggerMs <= System.currentTimeMillis()) return
        val pi = pendingIntent(reminder)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // Permission missing — fall back to inexact. Better late than never.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    /** Maps our recurrence pattern to the day-of-week ints AlarmClock expects. */
    private fun recurrenceDays(rec: Recurrence?): ArrayList<Int>? {
        if (rec == null) return null
        return when (rec.pattern) {
            Recurrence.Pattern.DAILY -> arrayListOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
            )
            Recurrence.Pattern.WEEKLY -> {
                if (rec.daysOfWeek.isEmpty()) null
                else ArrayList(rec.daysOfWeek.map { it.toCalendarInt() })
            }
            // Monthly / yearly aren't expressible in AlarmClock — fall back
            // to one-shot at the next trigger (no EXTRA_DAYS → one-time).
            else -> null
        }
    }

    /**
     * java.time.DayOfWeek runs MON=1 .. SUN=7; java.util.Calendar runs
     * SUN=1 .. SAT=7. Convert.
     */
    private fun DayOfWeek.toCalendarInt(): Int = when (this) {
        DayOfWeek.SUNDAY -> Calendar.SUNDAY
        DayOfWeek.MONDAY -> Calendar.MONDAY
        DayOfWeek.TUESDAY -> Calendar.TUESDAY
        DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
        DayOfWeek.THURSDAY -> Calendar.THURSDAY
        DayOfWeek.FRIDAY -> Calendar.FRIDAY
        DayOfWeek.SATURDAY -> Calendar.SATURDAY
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
        // Note: alarms handed off to the system Clock app cannot be cancelled
        // programmatically. The engine notes this in the user-facing reply.
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

    companion object {
        const val ACTION_FIRE = "com.santamota.reminder.ALARM_FIRE"
        const val EXTRA_REMINDER_ID = "reminderId"
    }
}
