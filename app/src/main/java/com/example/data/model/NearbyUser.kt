package com.example.data.model

data class NearbyUser(
    val endpointId: String,
    val userId: String, // e.g. "rahul#77"
    val displayName: String, // e.g. "rahul"
    val tagDigits: String, // e.g. "77"
    val estimatedDistanceMeters: Int = 15,
    val rssiStrength: Int = -55, // in dBm
    val status: ConnectionState = ConnectionState.DISCOVERED,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isSimulated: Boolean = false
)

enum class ConnectionState {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    REJECTED
}
