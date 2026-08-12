package com.nonpta.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.PhoneViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadScreen(vm: PhoneViewModel = viewModel(), navController: NavController) {
    val number by vm.dialedNumber.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                title = { Text("Dial", color = TextPrimary) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))

            // Number display
            Text(
                number.ifEmpty { "Enter number" },
                fontSize = if (number.length > 12) 26.sp else 34.sp,
                color = if (number.isEmpty()) TextMuted else TextPrimary,
                fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
                letterSpacing = 2.sp, maxLines = 1,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
            if (number.isNotEmpty()) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.backspace() }) {
                    Icon(Icons.Filled.Backspace, "Backspace", tint = TextSecondary)
                }
            } else Spacer(Modifier.height(48.dp))

            Spacer(Modifier.weight(1f))

            // Dialpad grid
            val keys = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("*","0","#"))
            keys.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { d ->
                        val sub = mapOf("2" to "ABC","3" to "DEF","4" to "GHI","5" to "JKL","6" to "MNO","7" to "PQRS","8" to "TUV","9" to "WXYZ","0" to "+")
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(Surface2)
                                .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.appendDigit(d) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(d, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                sub[d]?.let { Text(it, fontSize = 9.sp, color = TextMuted, letterSpacing = 1.5.sp) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))
            FloatingActionButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.dial() },
                containerColor = Green400, contentColor = Color.White, modifier = Modifier.size(64.dp), shape = CircleShape
            ) { Icon(Icons.Filled.Call, "Dial", Modifier.size(28.dp)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}
