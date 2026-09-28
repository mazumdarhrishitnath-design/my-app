package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.model.ConnectionState
import com.example.data.model.NearbyUser
import com.example.data.repository.ChatRepository
import com.example.nearby.NearbyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NameChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("namechat_prefs", Context.MODE_PRIVATE)
    private val database by lazy { AppDatabase.getDatabase(application) }
    private val repository by lazy { ChatRepository(database.chatDao()) }
    val nearbyManager by lazy { NearbyManager(application) }

    private val _myId = MutableStateFlow(prefs.getString("my_id", "") ?: "")
    val myId: StateFlow<String> = _myId.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(_myId.value.isNotEmpty())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _activeChatPeer = MutableStateFlow<NearbyUser?>(null)
    val activeChatPeer: StateFlow<NearbyUser?> = _activeChatPeer.asStateFlow()

    val discoveredUsers by lazy { nearbyManager.discoveredUsers }
    val isAdvertising by lazy { nearbyManager.isAdvertising }
    val isDiscovering by lazy { nearbyManager.isDiscovering }
    val connectedUser by lazy { nearbyManager.connectedUser }
    val connectionStatusMessage by lazy { nearbyManager.connectionStatusMessage }

    // Observe messages for currently active chat peer
    val currentChatMessages: StateFlow<List<ChatMessageEntity>> = _activeChatPeer
        .flatMapLatest { peer ->
            if (peer != null) {
                repository.getMessagesForPeer(peer.userId)
            } else {
                flowOf(emptyList())
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Collect incoming messages from nearby manager and store in Room asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            nearbyManager.incomingMessages.collect { (senderId, text) ->
                repository.saveMessage(
                    peerId = senderId,
                    senderId = senderId,
                    text = text,
                    isMe = false,
                    status = "RECEIVED"
                )
            }
        }

        // Collect message delivery status updates asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            nearbyManager.messageDeliveryUpdates.collect { (dbId, success) ->
                if (success) {
                    repository.updateMessageStatus(dbId, "DELIVERED")
                } else {
                    repository.updateMessageStatus(dbId, "FAILED")
                }
            }
        }

        // Synchronize active chat peer whenever connected user changes
        viewModelScope.launch {
            nearbyManager.connectedUser.collect { user ->
                if (user != null) {
                    val current = _activeChatPeer.value
                    if (current == null || current.endpointId == user.endpointId || current.userId == user.userId) {
                        _activeChatPeer.value = user
                    }
                }
            }
        }

        // Synchronize active chat peer whenever discovered users list updates (e.g. status changes, RSSI)
        viewModelScope.launch {
            nearbyManager.discoveredUsers.collect { usersMap ->
                val current = _activeChatPeer.value
                if (current != null) {
                    val updated = usersMap[current.endpointId]
                        ?: usersMap.values.find { it.userId == current.userId }
                    if (updated != null && (updated.status != current.status || updated.estimatedDistanceMeters != current.estimatedDistanceMeters)) {
                        _activeChatPeer.value = updated
                    }
                }
            }
        }

        if (_myId.value.isNotEmpty() && nearbyManager.hasRequiredPermissions()) {
            startNearbySession()
        }
    }

    fun login(name: String, digits: String): Boolean {
        val cleanName = name.trim().lowercase()
        val cleanDigits = digits.trim()
        if (cleanName.length < 2 || cleanDigits.length != 2 || !cleanDigits.all { it.isDigit() }) {
            return false
        }
        val id = "$cleanName#$cleanDigits"
        _myId.value = id
        _isLoggedIn.value = true
        prefs.edit().putString("my_id", id).apply()
        if (nearbyManager.hasRequiredPermissions()) {
            startNearbySession()
        }
        return true
    }

    fun logout() {
        nearbyManager.stopNearbySession()
        _myId.value = ""
        _isLoggedIn.value = false
        _activeChatPeer.value = null
        prefs.edit().remove("my_id").apply()
    }

    fun startNearbySession() {
        val id = _myId.value
        if (id.isNotEmpty()) {
            nearbyManager.startNearbySession(id)
        }
    }

    fun stopNearbySession() {
        nearbyManager.stopNearbySession()
    }

    fun connectToUser(user: NearbyUser) {
        nearbyManager.connectToUser(user)
        _activeChatPeer.value = user
    }

    fun openChat(user: NearbyUser) {
        _activeChatPeer.value = user
        if (user.status != ConnectionState.CONNECTED && user.status != ConnectionState.CONNECTING) {
            nearbyManager.connectToUser(user)
        }
    }

    fun closeChat() {
        _activeChatPeer.value = null
    }

    fun disconnect() {
        nearbyManager.disconnect()
    }

    fun sendMessage(text: String) {
        val peer = _activeChatPeer.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val myCurrentId = _myId.value

        viewModelScope.launch {
            val initialStatus = if (peer.status == ConnectionState.CONNECTED) "SENT" else "QUEUED"
            val msgId = repository.saveMessage(
                peerId = peer.userId,
                senderId = myCurrentId,
                text = trimmed,
                isMe = true,
                status = initialStatus
            )
            val sent = nearbyManager.sendMessage(trimmed, msgId)
            if (sent && peer.status == ConnectionState.CONNECTED) {
                repository.updateMessageStatus(msgId, "DELIVERED")
            }
        }
    }

    fun clearActiveChat() {
        val peer = _activeChatPeer.value ?: return
        viewModelScope.launch {
            repository.clearChat(peer.userId)
        }
    }

    fun addDemoPeers() {
        nearbyManager.addPresetSimulatedPeers()
    }

    fun addCustomDemoPeer(name: String, digits: String, distanceMeters: Int) {
        nearbyManager.addSimulatedPeer(name, digits, distanceMeters)
    }

    fun clearStatusMessage() {
        nearbyManager.clearStatusMessage()
    }

    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopNearbySession()
    }
}
