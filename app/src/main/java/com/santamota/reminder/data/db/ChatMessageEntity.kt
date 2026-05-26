package com.santamota.reminder.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,            // USER or AGENT
    val text: String,
    val createdEpochMs: Long,
    @ColumnInfo(defaultValue = "")
    val intentKind: String = "", // optional: tag the parsed intent kind
)
