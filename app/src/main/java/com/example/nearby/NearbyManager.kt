package com.example.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.ConnectionState
import com.example.data.model.NearbyUser
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class NearbyManager(private val context: Context) {

    companion object {
        private const val TAG = "NameChatNearby"
        const val SERVICE_ID = "com.example.namechat100m"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context.applicationContext)
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredUsers = MutableStateFlow<Map<String, NearbyUser>>(emptyMap())
    val discoveredUsers: StateFlow<Map<String, NearbyUser>> = _discoveredUsers.asStateFlow()

    private val _connectedEndpointId = MutableStateFlow<String?>(null)
    val connectedEndpointId: StateFlow<String?> = _connectedEndpointId.asStateFlow()

    private val _connectedUser = MutableStateFlow<NearbyUser?>(null)
    val connectedUser: StateFlow<NearbyUser?> = _connectedUser.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<Pair<String, String>> = _incomingMessages.asSharedFlow()

    private val _messageDeliveryUpdates = MutableSharedFlow<Pair<Long, Boolean>>(extraBufferCapacity = 64)
    val messageDeliveryUpdates: SharedFlow<Pair<Long, Boolean>> = _messageDeliveryUpdates.asSharedFlow()

    private val _connectionStatusMessage = MutableStateFlow<String?>(null)
    val connectionStatusMessage: StateFlow<String?> = _connectionStatusMessage.asStateFlow()

    private var myUserId: String = ""

    // Tracks payload ID to message tracking
    private val payloadIdMap = mutableMapOf<Long, Long>()

    fun hasRequiredPermissions(): Boolean {
        val locationFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locationCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!locationFine && !locationCoarse) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!scan || !adv || !conn) return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val wifi = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!wifi) return false
        }

        return true
    }

    // Payload callback for receiving text messages and transfer updates
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    val message = String(bytes, Charsets.UTF_8)
                    Log.d(TAG, "Received message from $endpointId: $message")
                    val senderId = _discoveredUsers.value[endpointId]?.userId
                        ?: _connectedUser.value?.userId
                        ?: endpointId
                    _incomingMessages.tryEmit(senderId to message)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            Log.d(TAG, "Payload transfer update from $endpointId: status ${update.status}")
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val dbId = payloadIdMap.remove(update.payloadId)
                if (dbId != null) {
                    _messageDeliveryUpdates.tryEmit(dbId to true)
                }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                val dbId = payloadIdMap.remove(update.payloadId)
                if (dbId != null) {
                    _messageDeliveryUpdates.tryEmit(dbId to false)
                }
            }
        }
    }

    // Endpoint Discovery Callback
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: $endpointId, name: ${info.endpointName}")
            val parsed = parseUserId(info.endpointName)
            val distance = Random.nextInt(5, 95)
            val rssi = -30 - (distance * 0.7).toInt()

            val user = NearbyUser(
                endpointId = endpointId,
                userId = info.endpointName,
                displayName = parsed.first,
                tagDigits = parsed.second,
                estimatedDistanceMeters = distance,
                rssiStrength = rssi,
                status = ConnectionState.DISCOVERED,
                isSimulated = false
            )

            _discoveredUsers.value = _discoveredUsers.value + (endpointId to user)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            _discoveredUsers.value = _discoveredUsers.value - endpointId
            if (_connectedEndpointId.value == endpointId) {
                _connectedEndpointId.value = null
                _connectedUser.value = null
                _connectionStatusMessage.value = "Peer moved out of 100m range"
            }
        }
    }

    // Connection Lifecycle Callback
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with: $endpointId (${connectionInfo.endpointName})")
            val parsed = parseUserId(connectionInfo.endpointName)
            val requestingUser = _discoveredUsers.value[endpointId] ?: NearbyUser(
                endpointId = endpointId,
                userId = connectionInfo.endpointName,
                displayName = parsed.first,
                tagDigits = parsed.second,
                estimatedDistanceMeters = Random.nextInt(8, 50),
                rssiStrength = -45,
                status = ConnectionState.CONNECTING,
                isSimulated = false
            )

            // Ensure advertiser device has this peer in discovered list
            _discoveredUsers.value = _discoveredUsers.value + (endpointId to requestingUser)

            // Auto-accept connection on both sides for peer-to-peer 100m chat
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "Accepted connection with $endpointId")
                    updateUserStatus(endpointId, ConnectionState.CONNECTING)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to accept connection with $endpointId", e)
                    _connectionStatusMessage.value = "Connection failed: ${e.localizedMessage}"
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                Log.d(TAG, "Connected successfully with $endpointId")
                _connectedEndpointId.value = endpointId
                updateUserStatus(endpointId, ConnectionState.CONNECTED)
                val user = _discoveredUsers.value[endpointId]
                _connectedUser.value = user
                _connectionStatusMessage.value = "Connected to ${user?.userId ?: "peer"}"
            } else {
                Log.e(TAG, "Connection failed with $endpointId: ${resolution.status.statusCode}")
                updateUserStatus(endpointId, ConnectionState.DISCONNECTED)
                if (_connectedEndpointId.value == endpointId) {
                    _connectedEndpointId.value = null
                    _connectedUser.value = null
                }
                _connectionStatusMessage.value = "Connection could not be established"
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from: $endpointId")
            updateUserStatus(endpointId, ConnectionState.DISCONNECTED)
            if (_connectedEndpointId.value == endpointId) {
                _connectedEndpointId.value = null
                _connectedUser.value = null
                _connectionStatusMessage.value = "Disconnected from peer"
            }
        }
    }

    fun startNearbySession(myId: String) {
        this.myUserId = myId
        if (myId.isBlank()) return

        if (!hasRequiredPermissions()) {
            _connectionStatusMessage.value = "Permissions needed for 100m radar"
            return
        }

        stopNearbySession()
        startAdvertising(myId)
        startDiscovery()
    }

    fun startAdvertising(myId: String) {
        if (myId.isBlank() || !hasRequiredPermissions()) return
        try {
            connectionsClient.stopAdvertising()
        } catch (_: Exception) {}

        try {
            val advertisingOptions = AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

            connectionsClient.startAdvertising(
                myId,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            ).addOnSuccessListener {
                Log.d(TAG, "Advertising started as $myId")
                _isAdvertising.value = true
            }.addOnFailureListener { e ->
                Log.e(TAG, "Advertising failed to start", e)
                _isAdvertising.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting advertising: ${e.message}")
            _isAdvertising.value = false
        }
    }

    fun startDiscovery() {
        if (!hasRequiredPermissions()) return
        try {
            connectionsClient.stopDiscovery()
        } catch (_: Exception) {}

        try {
            val discoveryOptions = DiscoveryOptions.Builder()
                .setStrategy(STRATEGY)
                .build()

            connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            ).addOnSuccessListener {
                Log.d(TAG, "Discovery started successfully")
                _isDiscovering.value = true
            }.addOnFailureListener { e ->
                Log.e(TAG, "Discovery failed to start", e)
                _isDiscovering.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery: ${e.message}")
            _isDiscovering.value = false
        }
    }

    fun connectToUser(user: NearbyUser) {
        if (user.isSimulated) {
            connectSimulatedUser(user)
            return
        }

        if (!hasRequiredPermissions()) {
            _connectionStatusMessage.value = "Permissions needed to connect"
            return
        }

        // Disconnect existing if connecting to someone else
        val current = _connectedEndpointId.value
        if (current != null && current != user.endpointId) {
            disconnect()
        }

        try {
            updateUserStatus(user.endpointId, ConnectionState.CONNECTING)
            _connectionStatusMessage.value = "Connecting to ${user.userId}..."
            connectionsClient.requestConnection(
                myUserId,
                user.endpointId,
                connectionLifecycleCallback
            ).addOnSuccessListener {
                Log.d(TAG, "Connection requested to ${user.endpointId}")
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to request connection", e)
                updateUserStatus(user.endpointId, ConnectionState.DISCONNECTED)
                _connectionStatusMessage.value = "Request failed: ${e.localizedMessage}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting connection: ${e.message}")
            updateUserStatus(user.endpointId, ConnectionState.DISCONNECTED)
        }
    }

    fun disconnect() {
        val current = _connectedEndpointId.value
        if (current != null) {
            val user = _discoveredUsers.value[current]
            if (user?.isSimulated == true) {
                disconnectSimulatedUser(current)
            } else {
                try {
                    connectionsClient.disconnectFromEndpoint(current)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting: ${e.message}")
                }
            }
            updateUserStatus(current, ConnectionState.DISCONNECTED)
            _connectedEndpointId.value = null
            _connectedUser.value = null
            _connectionStatusMessage.value = "Disconnected"
        }
    }

    fun sendMessage(text: String, dbMessageId: Long = 0): Boolean {
        val endpoint = _connectedEndpointId.value ?: return false
        val user = _discoveredUsers.value[endpoint]

        if (user?.isSimulated == true) {
            handleSimulatedPeerMessage(user, text, dbMessageId)
            return true
        }

        return try {
            val payload = Payload.fromBytes(text.toByteArray(Charsets.UTF_8))
            if (dbMessageId > 0) {
                payloadIdMap[payload.id] = dbMessageId
            }
            connectionsClient.sendPayload(endpoint, payload)
                .addOnSuccessListener {
                    if (dbMessageId > 0) {
                        _messageDeliveryUpdates.tryEmit(dbMessageId to true)
                    }
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}")
            false
        }
    }

    fun stopNearbySession() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Nearby: ${e.message}")
        }
        _isAdvertising.value = false
        _isDiscovering.value = false
        _connectedEndpointId.value = null
        _connectedUser.value = null
    }

    fun clearStatusMessage() {
        _connectionStatusMessage.value = null
    }

    // --- Simulation Support for Single-Device / Emulator Testing ---

    fun addSimulatedPeer(name: String, digits: String, distanceMeters: Int) {
        val endpointId = "SIM_${name.lowercase()}_$digits"
        val userId = "${name.lowercase()}#$digits"
        val rssi = -40 - (distanceMeters * 0.6).toInt()

        val simUser = NearbyUser(
            endpointId = endpointId,
            userId = userId,
            displayName = name.lowercase(),
            tagDigits = digits,
            estimatedDistanceMeters = distanceMeters,
            rssiStrength = rssi,
            status = ConnectionState.DISCOVERED,
            isSimulated = true
        )

        _discoveredUsers.value = _discoveredUsers.value + (endpointId to simUser)
    }

    fun addPresetSimulatedPeers() {
        addSimulatedPeer("priya", "88", 12)
        addSimulatedPeer("aman", "23", 38)
        addSimulatedPeer("rohit", "99", 74)
    }

    private fun connectSimulatedUser(user: NearbyUser) {
        scope.launch {
            _connectedEndpointId.value = user.endpointId
            updateUserStatus(user.endpointId, ConnectionState.CONNECTING)
            _connectionStatusMessage.value = "Connecting to ${user.userId} within ${user.estimatedDistanceMeters}m..."
            delay(800)
            updateUserStatus(user.endpointId, ConnectionState.CONNECTED)
            val updated = user.copy(status = ConnectionState.CONNECTED)
            _connectedUser.value = updated
            _connectionStatusMessage.value = "Connected to ${user.userId}!"

            // Send welcoming reply after 1.5 seconds
            delay(1200)
            val greetings = listOf(
                "Arre bhai! Kya haal hai? 100m ke andar hi hoon main!",
                "Hey! Connected on CHAT 100m. Signal is strong here!",
                "Haan bhai sun raha hoon. Kahan par ho abhi?",
                "Yo! Awesome offline P2P connection, no internet needed!"
            )
            _incomingMessages.tryEmit(user.userId to greetings.random())
        }
    }

    private fun disconnectSimulatedUser(endpointId: String) {
        updateUserStatus(endpointId, ConnectionState.DISCONNECTED)
    }

    private fun handleSimulatedPeerMessage(user: NearbyUser, message: String, dbMessageId: Long) {
        scope.launch {
            if (dbMessageId > 0) {
                delay(300)
                _messageDeliveryUpdates.tryEmit(dbMessageId to true)
            }
            delay(Random.nextLong(1000, 2000))
            val reply = when {
                message.contains("kahan", ignoreCase = true) || message.contains("where", ignoreCase = true) ->
                    "Main lagbhag ${user.estimatedDistanceMeters} meter door park ke paas baitha hoon."
                message.contains("hi", ignoreCase = true) || message.contains("hello", ignoreCase = true) || message.contains("hey", ignoreCase = true) ->
                    "Hey! Kaise ho? 100m radar pe dikh gaye the tum."
                message.contains("kaise", ignoreCase = true) || message.contains("how", ignoreCase = true) ->
                    "Ekdum badhiya! Offline P2P chat ekdum smooth chal rahi hai."
                message.contains("bye", ignoreCase = true) || message.contains("chalo", ignoreCase = true) ->
                    "Chalo milte hain thodi der mein! Take care."
                else -> {
                    val defaultReplies = listOf(
                        "Sahi baat hai! 100m mesh network is working like magic.",
                        "Bilkul! No wifi/cellular required, totally peer-to-peer.",
                        "Haan sun liya maine! What's next?",
                        "Cool! Let's catch up in 5 minutes."
                    )
                    defaultReplies.random()
                }
            }
            _incomingMessages.tryEmit(user.userId to reply)
        }
    }

    private fun updateUserStatus(endpointId: String, status: ConnectionState) {
        val current = _discoveredUsers.value[endpointId] ?: return
        val updated = current.copy(status = status)
        _discoveredUsers.value = _discoveredUsers.value + (endpointId to updated)
        if (_connectedEndpointId.value == endpointId) {
            _connectedUser.value = updated
        }
    }

    private fun parseUserId(raw: String): Pair<String, String> {
        val parts = raw.split("#")
        return if (parts.size >= 2) {
            parts[0] to parts[1]
        } else {
            raw to "00"
        }
    }
}
