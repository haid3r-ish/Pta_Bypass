package com.nonpta.bridge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nonpta.bridge.model.ConnectionState
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val gateway by vm.gatewayIp.collectAsStateWithLifecycle()
    val saved by vm.savedIps.collectAsStateWithLifecycle()
    val lastIp by vm.lastConnectedIp.collectAsStateWithLifecycle()
    val connState by vm.connectionState.collectAsStateWithLifecycle()
    val newIp by vm.newIpInput.collectAsStateWithLifecycle()
    val recording by vm.callRecordingEnabled.collectAsStateWithLifecycle()
    val blocked by vm.blockedNumbers.collectAsStateWithLifecycle()
    val selectedIp by vm.selectedSavedIp.collectAsStateWithLifecycle()

    // Dropdown expanded state
    var dropdownExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── NETWORK SECTION ──
        item {
            Text(
                "NETWORK", color = Cyan400, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        // Status indicator
        item {
            val (icon, color, label) = when (connState) {
                ConnectionState.CONNECTED -> Triple(Icons.Filled.CheckCircle, Green400, "Connected")
                ConnectionState.CONNECTING -> Triple(Icons.Filled.Sync, Yellow400, "Connecting…")
                ConnectionState.DISCONNECTED -> Triple(Icons.Filled.Cancel, Red400, "Disconnected")
            }
            ListItem(
                headlineContent = { Text(label, color = color, fontWeight = FontWeight.Medium) },
                supportingContent = { lastIp?.let { Text("Last: $it", color = TextSecondary) } },
                leadingContent = { Icon(icon, null, tint = color) },
                trailingContent = {
                    if (connState == ConnectionState.CONNECTED)
                        TextButton(onClick = { vm.disconnect() }) { Text("Disconnect", color = Red400) }
                },
                colors = ListItemDefaults.colors(containerColor = Surface1)
            )
        }

        // Auto-detected gateway
        item {
            ListItem(
                headlineContent = { Text("Auto-Detected Gateway", color = TextPrimary) },
                supportingContent = { Text(gateway.ifBlank { "No gateway found" }, color = TextSecondary) },
                leadingContent = { Icon(Icons.Filled.Router, null, tint = Cyan400) },
                trailingContent = {
                    Button(
                        onClick = { vm.connect(gateway) },
                        enabled = gateway.isNotBlank() && connState != ConnectionState.CONNECTING,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Black),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                    ) { Text("Connect") }
                },
                colors = ListItemDefaults.colors(containerColor = Surface1)
            )
        }

        // ── FIX #2: Saved IPs as ExposedDropdownMenuBox ──
        if (saved.isNotEmpty()) {
            item {
                Text(
                    "Saved IPs", color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }
            item {
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedIp.ifBlank { "Select a saved IP…" },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan400, unfocusedBorderColor = Surface3,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        containerColor = Surface2
                    ) {
                        saved.forEach { ip ->
                            DropdownMenuItem(
                                text = { Text(ip, color = TextPrimary) },
                                onClick = {
                                    vm.selectedSavedIp.value = ip
                                    dropdownExpanded = false
                                },
                                leadingIcon = { Icon(Icons.Filled.Dns, null, tint = TextSecondary) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { vm.removeIp(ip) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.Delete, "Delete", tint = Red400)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            // Connect selected IP button
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { vm.connect(selectedIp) },
                        enabled = selectedIp.isNotBlank() && connState != ConnectionState.CONNECTING,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Black),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                    ) { Text("Connect Selected") }
                }
            }
        }

        // Add new IP
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newIp, onValueChange = { vm.newIpInput.value = it },
                    placeholder = { Text("Add IP address…", color = TextMuted) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan400, unfocusedBorderColor = Surface3,
                        cursorColor = Cyan400, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { vm.addIp(newIp) }, enabled = newIp.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Cyan400, contentColor = Black
                    )
                ) { Icon(Icons.Filled.Add, "Add") }
            }
        }

        // ── APP SETTINGS SECTION ──
        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Surface3)
            Text(
                "SETTINGS", color = Cyan400, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Call Recording", color = TextPrimary) },
                supportingContent = { Text("Save calls to MediaStore/Recordings", color = TextSecondary) },
                leadingContent = {
                    Icon(Icons.Filled.FiberManualRecord, null, tint = if (recording) Red400 else TextMuted)
                },
                trailingContent = {
                    Switch(
                        checked = recording, onCheckedChange = { vm.setCallRecording(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan400, checkedThumbColor = Black)
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Surface1)
            )
        }

        // Blocked numbers
        item {
            Text(
                "Blocked Numbers", color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
        }
        if (blocked.isEmpty()) {
            item {
                Text("No blocked numbers", color = TextMuted, modifier = Modifier.padding(16.dp))
            }
        } else {
            items(blocked, key = { it.number }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.number, color = TextPrimary) },
                    leadingContent = { Icon(Icons.Filled.Block, null, tint = Red400) },
                    trailingContent = {
                        IconButton(onClick = { vm.unblockNumber(entry.number) }) {
                            Icon(Icons.Filled.Close, "Unblock", tint = TextSecondary)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Surface1)
                )
            }
        }
    }
}
