package com.santamota.reminder.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class PermissionsState(
    val notifGranted: Boolean = false,
    val exactAlarmDeepLinkPending: Boolean = false,
    val exactAlarmDeepLinkShown: Boolean = false,
)

@HiltViewModel
class PermissionsViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(PermissionsState())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    fun onNotifPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(notifGranted = granted)
    }

    fun requestExactAlarmDeepLink() {
        if (_state.value.exactAlarmDeepLinkShown) return
        _state.value = _state.value.copy(exactAlarmDeepLinkPending = true)
    }

    fun onExactAlarmDeepLinkOpened() {
        _state.value = _state.value.copy(
            exactAlarmDeepLinkPending = false,
            exactAlarmDeepLinkShown = true,
        )
    }
}
