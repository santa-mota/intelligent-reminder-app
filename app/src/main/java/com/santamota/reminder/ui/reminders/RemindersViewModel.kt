package com.santamota.reminder.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santamota.reminder.data.db.ReminderDao
import com.santamota.reminder.data.db.toDomain
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Groups reminders for UI display: Daily first, then Weekly, then Monthly,
 * then one-time. Within each section, sorted chronologically by next fire
 * time.
 */
data class RemindersUiState(
    val sections: List<Section> = emptyList(),
) {
    data class Section(val title: String, val items: List<Reminder>)
}

@HiltViewModel
class RemindersViewModel @Inject constructor(
    reminderDao: ReminderDao,
) : ViewModel() {

    val state: StateFlow<RemindersUiState> = reminderDao.observeActive()
        .map { entities ->
            val reminders = entities.map { it.toDomain() }
            RemindersUiState(sections = groupBySection(reminders))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersUiState())

    private fun groupBySection(reminders: List<Reminder>): List<RemindersUiState.Section> {
        val daily = mutableListOf<Reminder>()
        val weekly = mutableListOf<Reminder>()
        val monthly = mutableListOf<Reminder>()
        val once = mutableListOf<Reminder>()
        for (r in reminders) {
            when (r.recurrence?.pattern) {
                Recurrence.Pattern.DAILY -> daily += r
                Recurrence.Pattern.WEEKLY -> weekly += r
                Recurrence.Pattern.MONTHLY -> monthly += r
                Recurrence.Pattern.YEARLY -> monthly += r // group with monthly bucket
                null -> once += r
            }
        }
        fun List<Reminder>.sorted() = sortedBy { it.triggerAt }
        return listOfNotNull(
            daily.takeIf { it.isNotEmpty() }?.let { RemindersUiState.Section("Daily", it.sorted()) },
            weekly.takeIf { it.isNotEmpty() }?.let { RemindersUiState.Section("Weekly", it.sorted()) },
            monthly.takeIf { it.isNotEmpty() }?.let { RemindersUiState.Section("Monthly", it.sorted()) },
            once.takeIf { it.isNotEmpty() }?.let { RemindersUiState.Section("One-time", it.sorted()) },
        )
    }
}
