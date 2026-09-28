package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.NameChatViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NameChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NameChatApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun NameChatApp(viewModel: NameChatViewModel) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val myId by viewModel.myId.collectAsStateWithLifecycle()
    val discoveredUsersMap by viewModel.discoveredUsers.collectAsStateWithLifecycle()
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val activeChatPeer by viewModel.activeChatPeer.collectAsStateWithLifecycle()
    val chatMessages by viewModel.currentChatMessages.collectAsStateWithLifecycle()
    val statusMessage by viewModel.connectionStatusMessage.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val requiredPermissions = remember {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissions.toTypedArray()
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // Refresh permission state on app resume (e.g. returning from settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = requiredPermissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted != hasPermissions) {
                    hasPermissions = granted
                    if (granted && isLoggedIn) {
                        viewModel.startNearbySession()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = result.values.all { it }
        if (hasPermissions && isLoggedIn) {
            viewModel.startNearbySession()
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && !hasPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    val usersList = remember(discoveredUsersMap) {
        discoveredUsersMap.values.toList().sortedBy { it.estimatedDistanceMeters }
    }

    when {
        !isLoggedIn -> {
            LoginScreen(
                onLoginSuccess = { name, digits ->
                    viewModel.login(name, digits)
                }
            )
        }
        activeChatPeer != null -> {
            ChatScreen(
                peer = activeChatPeer!!,
                messages = chatMessages,
                onSendMessage = { text ->
                    viewModel.sendMessage(text)
                },
                onBack = {
                    viewModel.closeChat()
                },
                onDisconnect = {
                    viewModel.disconnect()
                },
                onClearChat = {
                    viewModel.clearActiveChat()
                }
            )
        }
        else -> {
            HomeScreen(
                myId = myId,
                discoveredUsers = usersList,
                isAdvertising = isAdvertising,
                isDiscovering = isDiscovering,
                statusMessage = statusMessage,
                hasPermissions = hasPermissions,
                onRequestPermissions = {
                    permissionLauncher.launch(requiredPermissions)
                },
                onUserClick = { user ->
                    viewModel.openChat(user)
                },
                onLogout = {
                    viewModel.logout()
                },
                onRefreshRadar = {
                    viewModel.startNearbySession()
                },
                onAddDemoPeers = {
                    viewModel.addDemoPeers()
                },
                onAddCustomDemoPeer = { name, digits, dist ->
                    viewModel.addCustomDemoPeer(name, digits, dist)
                },
                onClearStatusMessage = {
                    viewModel.clearStatusMessage()
                }
            )
        }
    }
}
