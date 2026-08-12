package com.nonpta.bridge.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.nonpta.bridge.DeepLinkHandler
import com.nonpta.bridge.model.CallState
import com.nonpta.bridge.model.ConnectionState
import com.nonpta.bridge.ui.screens.*
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.*
import androidx.compose.ui.graphics.Color

private val TAB_ROOTS = listOf("calls", "messages", "settings")

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val settingsVM: SettingsViewModel = viewModel()
    val phoneVM: PhoneViewModel = viewModel()
    val activeCallState by phoneVM.activeCallState.collectAsStateWithLifecycle()
    val connState by settingsVM.connectionState.collectAsStateWithLifecycle()
    val isCallMinimized by phoneVM.isCallMinimized.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DeepLinkHandler.pendingNavigate.collect { route ->
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Black)) {
        Column(Modifier.fillMaxSize()) {
            // ── Connection Banner — FIX #5: sits below system status bar ──
            ConnectionBanner(connState) { settingsVM.reconnect() }

            // ── NavHost ──
            Box(Modifier.weight(1f)) {
                NavHost(navController, startDestination = "calls") {
                    composable("calls") { CallLogsScreen(navController, phoneVM = phoneVM) }
                    composable("messages") { ConversationsScreen(navController) }
                    composable("settings") { SettingsScreen(settingsVM) }
                    composable("dialpad") { DialpadScreen(phoneVM, navController) }
                    composable("newchat") { NewChatScreen(navController) }
                    composable(
                        "chat/{threadNumber}",
                        arguments = listOf(navArgument("threadNumber") { type = NavType.StringType })
                    ) { back ->
                        ChatScreen(back.arguments?.getString("threadNumber") ?: "", navController)
                    }
                }
            }

            // ── Active Call Minimized Bar ──
            if ((activeCallState == CallState.ACTIVE || activeCallState == CallState.HELD) && isCallMinimized) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { phoneVM.toggleMinimize() },
                    color = Green400,
                    contentColor = Color.White
                ) {
                    val callerNumber by phoneVM.incomingCaller.collectAsStateWithLifecycle()
                    val seconds by phoneVM.callDurationSeconds.collectAsStateWithLifecycle()
                    val timeStr = "%02d:%02d".format(seconds / 60, seconds % 60)
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val displayName = callerNumber.ifBlank { "Active Call" }
                        Text(
                            text = "Tap to Return • $displayName",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text(
                            text = timeStr,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // ── Bottom Nav ──
            val currentEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentEntry?.destination?.route
            
            NavigationBar(containerColor = Surface1, contentColor = TextPrimary) {
                listOf(
                    Triple("calls", "Calls", Icons.Filled.Phone),
                    Triple("messages", "Messages", Icons.AutoMirrored.Filled.Message),
                    Triple("settings", "Settings", Icons.Filled.Settings)
                ).forEach { (route, label, icon) ->
                    // FIX #5: Determine if this tab's root is currently active
                    val isSelected = when (route) {
                        "calls" -> currentRoute == "calls" || currentRoute == "dialpad"
                        "messages" -> currentRoute == "messages" || currentRoute?.startsWith("chat/") == true || currentRoute == "newchat"
                        "settings" -> currentRoute == "settings"
                        else -> false
                    }
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (isSelected) {
                                navController.popBackStack(route = route, inclusive = false)
                            } else {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationRoute ?: "calls") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(icon, label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan400,
                            selectedTextColor = Cyan400,
                            indicatorColor = CyanDim
                        )
                    )
                }
            }
        }

        // ── Active Call Overlay (only if an active call exists) ──
        if (activeCallState != CallState.IDLE && activeCallState != CallState.ENDED) {
            if (!isCallMinimized) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    ActiveCallScreen(phoneVM)
                }
            } 
        }
    }
}

/* ── Connection Status Banner ──────────────────────────────────────── */

@Composable
private fun ConnectionBanner(state: ConnectionState, onReconnect: () -> Unit) {
    AnimatedVisibility(
        visible = state != ConnectionState.CONNECTED,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val (bg, text, showProgress) = when (state) {
            ConnectionState.DISCONNECTED -> Triple(Red400.copy(0.85f), "Disconnected", false)
            ConnectionState.CONNECTING -> Triple(Yellow400.copy(0.85f), "Connecting…", true)
            ConnectionState.CONNECTED -> Triple(Green400.copy(0.85f), "Connected", false)
        }
        Row(
            Modifier.fillMaxWidth().background(bg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showProgress) CircularProgressIndicator(Modifier.size(14.dp), color = Black, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = Black)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Black, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (state == ConnectionState.DISCONNECTED) {
                IconButton(onClick = onReconnect, Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Refresh, "Reconnect", tint = Black)
                }
            }
        }
    }
}
