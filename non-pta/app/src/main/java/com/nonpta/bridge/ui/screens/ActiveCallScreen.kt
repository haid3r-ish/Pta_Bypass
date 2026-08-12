package com.nonpta.bridge.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonpta.bridge.model.CallState
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.PhoneViewModel
import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(vm: PhoneViewModel) {
    val activeCallState by vm.activeCallState.collectAsStateWithLifecycle()
    val dialed       by vm.dialedNumber.collectAsStateWithLifecycle()
    val caller       by vm.incomingCaller.collectAsStateWithLifecycle()
    val isMuted      by vm.isMuted.collectAsStateWithLifecycle()
    val isSpeaker    by vm.isSpeaker.collectAsStateWithLifecycle()
    val isHeld       by vm.isHeld.collectAsStateWithLifecycle()
    val isRecording  by vm.isRecording.collectAsStateWithLifecycle()

    val haptic           = LocalHapticFeedback.current
    val context          = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager     = LocalFocusManager.current

    BackHandler {
        vm.toggleMinimize()
    }

    // FIX #3: Force keyboard down and strip focus from any previous screen's search bar
    LaunchedEffect(Unit) {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
    }

    // Volume rocker → STREAM_VOICE_CALL while this screen is alive.
    // DisposableEffect resets automatically when navigation pops this screen OR
    // when the call transitions to IDLE/ENDED and the overlay is removed.
    val activity = remember(context) { context.findActivity() }
    
    DisposableEffect(activity) {
        activity?.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        onDispose {
            activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        }
    }

    // FIX #1/3: Caller identity resolved asymptotically off the main thread
    val displayNumber = remember(caller) { caller.ifBlank { "Active Call" } }
//    val contact by produceState<ContactInfo?>(
//        initialValue = null,
//        key1 = displayNumber
//    ) {
//        value = vm.suspendResolve(displayNumber)
//    }
    val contactName = remember(displayNumber) { displayNumber }
    val resolvedName  = contactName

    val statusText = when (activeCallState) {
        CallState.DIALING      -> "Calling…"
        CallState.HELD         -> "On Hold"
        CallState.ACTIVE       -> "Active"
        else                   -> ""
    }

    var showDtmfPad by remember { mutableStateOf(false) }

    // ── FIX #1: Solid-wall root. pointerInput consumes every gesture so nothing
    //            bleeds through to the nav-stack screens underneath.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { /* swallow all background taps */ } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopStart) {
                IconButton(onClick = { vm.toggleMinimize() }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Minimize", tint = TextSecondary, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(12.dp))

            // Avatar — static, no clickable
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(Surface3),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = resolvedName.take(1).uppercase(),
                    fontSize = 40.sp,
                    color = Cyan400,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(20.dp))

            // FIX #2: Plain Text only — never TextField/BasicTextField/shared MutableState
            Text(
                text = resolvedName,
                fontSize = 28.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            if (resolvedName != displayNumber) {
                Text(
                    text = displayNumber,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
            }

            // Timer or status
            when (activeCallState) {
                CallState.ACTIVE -> IsolatedCallTimer(vm)
                else -> Text(
                    text = statusText,
                    color = Yellow400,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 3.sp
                )
            }

            Spacer(Modifier.weight(1f))

            // ── ACTIVE / HELD: 2×3 grid ────────────────────────
            if (activeCallState == CallState.ACTIVE || activeCallState == CallState.HELD) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionButton(Icons.Filled.Pause, "Hold", isHeld,
                            if (isHeld) Yellow400 else null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.toggleHold()
                        }
                        ActionButton(Icons.Filled.FiberManualRecord, "Record", isRecording,
                            if (isRecording) Red400 else null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.toggleRecording()
                        }
                        ActionButton(Icons.Filled.MicOff, "Mute", isMuted,
                            if (isMuted) Red400 else null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.toggleMute()
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionButton(Icons.Filled.VolumeUp, "Speaker", isSpeaker,
                            if (isSpeaker) Cyan400 else null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.toggleSpeaker()
                        }
                        ActionButton(Icons.Filled.Dialpad, "Keypad", false) {
                            showDtmfPad = true
                        }
                        ActionButton(Icons.Filled.Contacts, "Contacts", false) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))

            // End call button
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.endCall()
                },
                containerColor = Red400,
                contentColor = Color.White,
                modifier = Modifier.size(72.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Filled.CallEnd, "End", Modifier.size(32.dp))
            }

            Spacer(Modifier.height(48.dp))
        }

        // ── DTMF sheet ─────────────────────────────────────────────────────────
        if (showDtmfPad) {
            ModalBottomSheet(
                onDismissRequest = { showDtmfPad = false },
                containerColor = Surface2
            ) {
                DtmfPad { digit -> vm.sendDtmf(digit) }
            }
        }

    }
}

// ── FIX #2: Timer composable — only this recomposes every second ────────────

@Composable
private fun IsolatedCallTimer(vm: PhoneViewModel) {
    val seconds by vm.callDurationSeconds.collectAsStateWithLifecycle()
    Text(
        text = "%02d:%02d".format(seconds / 60, seconds % 60),
        color = Green400,
        fontSize = 18.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 3.sp
    )
}



// ── Shared private composables ───────────────────────────────────────────────

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeTint: Color? = null,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active && activeTint != null) activeTint.copy(0.2f) else Surface3)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = activeTint ?: TextPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun CallFab(icon: ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = bg,
        contentColor = Color.White,
        modifier = Modifier.size(64.dp),
        shape = CircleShape
    ) { Icon(icon, desc, Modifier.size(28.dp)) }
}

@Composable
private fun DtmfPad(onDigit: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val rows   = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("*","0","#"))
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("DTMF Keypad", color = TextPrimary, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { d ->
                    Box(
                        Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Surface3)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDigit(d)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(d, fontSize = 24.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
}

internal fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}