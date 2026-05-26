package com.santamota.reminder.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * One-shot helpers for the two runtime permissions this app needs:
 *
 *  - POST_NOTIFICATIONS (API 33+): standard runtime permission.
 *  - SCHEDULE_EXACT_ALARM (API 31+): not a regular runtime perm — it's a
 *    Settings toggle the user has to enable. We can only deep-link them
 *    to the Settings page and check `canScheduleExactAlarms()`.
 *
 * Composables that need these permissions call [RequestRequiredPermissions]
 * once. It silently no-ops on older API levels or when already granted.
 */
@Composable
fun RequestRequiredPermissions(viewModel: PermissionsViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onNotifPermissionResult(granted) }

    LaunchedEffect(Unit) {
        // Request POST_NOTIFICATIONS at start. The system handles "don't
        // ask again" — repeated calls are cheap.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // SCHEDULE_EXACT_ALARM can only be granted via Settings on API 31+.
        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService<AlarmManager>()
            if (am != null && !am.canScheduleExactAlarms()) {
                viewModel.requestExactAlarmDeepLink()
            }
        }
    }

    if (state.exactAlarmDeepLinkPending) {
        LaunchedEffect(state.exactAlarmDeepLinkPending) {
            openExactAlarmSettings(ctx)
            viewModel.onExactAlarmDeepLinkOpened()
        }
    }
}

private fun openExactAlarmSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }
}
