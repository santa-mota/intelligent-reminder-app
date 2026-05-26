package com.santamota.reminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.santamota.reminder.domain.GroupId
import com.santamota.reminder.domain.Recurrence
import com.santamota.reminder.domain.Reminder
import com.santamota.reminder.domain.ReminderCategory
import com.santamota.reminder.domain.ReminderId
import com.santamota.reminder.domain.ReminderStatus
import com.santamota.reminder.domain.ReminderType
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Entity(
    tableName = "reminders",
    indices = [
        Index("triggerEpochMs"),
        Index("status"),
        Index("parentId"),
        Index("groupId"),
    ],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val type: String,                  // ReminderType
    val triggerEpochMs: Long,
    val triggerZone: String,
    val recurrencePattern: String?,    // Recurrence.Pattern
    val recurrenceInterval: Int?,
    val recurrenceDays: String?,       // CSV of DayOfWeek values
    val recurrenceEndDate: String?,    // ISO LocalDate
    val parentId: String?,
    val relativeOffsetSeconds: Long?,
    val groupId: String?,
    val status: String,                // ReminderStatus
    val exceptions: String,            // CSV of ISO LocalDate
    val category: String,              // ReminderCategory
    val tags: String,                  // CSV
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
)

fun ReminderEntity.toDomain(): Reminder {
    val zone = ZoneId.of(triggerZone)
    return Reminder(
        id = ReminderId(id),
        title = title,
        description = description,
        type = ReminderType.valueOf(type),
        triggerAt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerEpochMs), zone),
        recurrence = recurrencePattern?.let {
            Recurrence(
                pattern = Recurrence.Pattern.valueOf(it),
                interval = recurrenceInterval ?: 1,
                daysOfWeek = recurrenceDays
                    ?.split(",")?.filter { d -> d.isNotBlank() }
                    ?.mapTo(mutableSetOf()) { d -> DayOfWeek.valueOf(d) }
                    .orEmpty(),
                endDate = recurrenceEndDate?.let { d -> LocalDate.parse(d) },
            )
        },
        parentId = parentId?.let { ReminderId(it) },
        relativeOffset = relativeOffsetSeconds?.let { Duration.ofSeconds(it) },
        groupId = groupId?.let { GroupId(it) },
        status = ReminderStatus.valueOf(status),
        exceptions = exceptions.split(",")
            .filter { it.isNotBlank() }
            .map { LocalDate.parse(it) }
            .toSet(),
        category = ReminderCategory.valueOf(category),
        tags = tags.split(",").filter { it.isNotBlank() }.toSet(),
        createdAt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(createdEpochMs), zone),
        updatedAt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(updatedEpochMs), zone),
    )
}

fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id.value,
    title = title,
    description = description,
    type = type.name,
    triggerEpochMs = triggerAt.toInstant().toEpochMilli(),
    triggerZone = triggerAt.zone.id,
    recurrencePattern = recurrence?.pattern?.name,
    recurrenceInterval = recurrence?.interval,
    recurrenceDays = recurrence?.daysOfWeek?.joinToString(",") { it.name },
    recurrenceEndDate = recurrence?.endDate?.toString(),
    parentId = parentId?.value,
    relativeOffsetSeconds = relativeOffset?.seconds,
    groupId = groupId?.value,
    status = status.name,
    exceptions = exceptions.joinToString(",") { it.toString() },
    category = category.name,
    tags = tags.joinToString(","),
    createdEpochMs = createdAt.toInstant().toEpochMilli(),
    updatedEpochMs = updatedAt.toInstant().toEpochMilli(),
)
