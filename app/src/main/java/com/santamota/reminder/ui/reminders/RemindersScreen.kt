package com.santamota.reminder.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.ui.theme.CornerRadius
import com.santamota.reminder.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RemindersScreen(
    modifier: Modifier = Modifier,
    viewModel: RemindersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (state.sections.isEmpty()) {
        EmptyState(modifier)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(vertical = Spacing.l),
    ) {
        for (section in state.sections) {
            item(key = "header-${section.title}") {
                SectionHeader(section.title)
            }
            items(section.items, key = { it.id.value }) { reminder ->
                ReminderRow(reminder)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.s),
    )
}

@Composable
private fun ReminderRow(r: Reminder) {
    Surface(
        shape = RoundedCornerShape(CornerRadius.m),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.xl)) {
            Text(
                text = r.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = humanLine(r),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xxxl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No reminders yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Tap Chat and tell me what to remind you about.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

private fun humanLine(r: Reminder): String {
    val time = r.triggerAt.toLocalTime().format(timeFmt)
    val date = r.triggerAt.toLocalDate().format(dateFmt)
    val typeWord = if (r.type == com.santamota.reminder.domain.ReminderType.ALARM) "alarm" else "reminder"
    val cadence = r.recurrence?.let {
        " · " + when (it.pattern) {
            com.santamota.reminder.domain.Recurrence.Pattern.DAILY -> "daily"
            com.santamota.reminder.domain.Recurrence.Pattern.WEEKLY ->
                if (it.daysOfWeek.isEmpty()) "weekly" else
                    "weekly on " + it.daysOfWeek.joinToString { d ->
                        d.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                    }
            com.santamota.reminder.domain.Recurrence.Pattern.MONTHLY -> "monthly"
            com.santamota.reminder.domain.Recurrence.Pattern.YEARLY -> "yearly"
        }
    } ?: ""
    return "$typeWord at $time · $date$cadence"
}
