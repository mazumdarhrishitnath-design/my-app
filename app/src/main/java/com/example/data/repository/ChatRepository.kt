package com.example.data.repository

import com.example.data.local.ChatDao
import com.example.data.local.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {
    fun getMessagesForPeer(peerId: String): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesForPeer(peerId)
    }

    suspend fun saveMessage(
        peerId: String,
        senderId: String,
        text: String,
        isMe: Boolean,
        status: String = "DELIVERED"
    ): Long {
        val entity = ChatMessageEntity(
            peerId = peerId,
            senderId = senderId,
            text = text,
            timestamp = System.currentTimeMillis(),
            isMe = isMe,
            deliveryStatus = status
        )
        return chatDao.insertMessage(entity)
    }

    suspend fun updateMessageStatus(id: Long, status: String) {
        chatDao.updateDeliveryStatus(id, status)
    }

    suspend fun clearChat(peerId: String) {
        chatDao.clearChatForPeer(peerId)
    }

    fun getRecentPeers(): Flow<List<String>> {
        return chatDao.getRecentPeers()
    }
}
