package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val peerId: String, // e.g. "priya#42"
    val senderId: String, // e.g. "rahul#77" or "priya#42"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean,
    val deliveryStatus: String = "DELIVERED" // SENDING, DELIVERED, RECEIVED, FAILED
)
