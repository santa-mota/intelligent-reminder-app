package com.santamota.reminder.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.santamota.reminder.data.db.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers all active alarms after device reboot, package replace, or
 * timezone change. Without this, every reminder silently dies after a
 * restart.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED -> reschedule(context)
        }
    }

    private fun reschedule(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entry = ReminderAlarmEntryPoint.resolve(context)
                val active = entry.db.reminderDao().activeOnce().map { it.toDomain() }
                entry.scheduler.rescheduleAll(active)
            } finally {
                pending.finish()
            }
        }
    }
}
