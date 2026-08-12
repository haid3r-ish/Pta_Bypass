package com.nonpta.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nonpta.bridge.data.db.entity.CallLogEntity
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.CallLogViewModel
import com.nonpta.bridge.viewmodel.PhoneViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unified action type — used by both Recent logs and Contacts BottomSheets.
 */
private data class SheetTarget(val number: String, val isContact: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogsScreen(
    navController: NavController,
    vm: CallLogViewModel = viewModel(),
    phoneVM: PhoneViewModel = viewModel()
) {
    val logs by vm.callLogs.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val showContacts by vm.showContacts.collectAsStateWithLifecycle()
    val blocked by vm.blockedNumbers.collectAsStateWithLifecycle()
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.loadContacts() }

    Scaffold(
        containerColor = Black,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("dialpad") },
                containerColor = Cyan400, contentColor = Black
            ) { Icon(Icons.Filled.Dialpad, "Dial") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Search
            OutlinedTextField(
                value = query, onValueChange = { vm.searchQuery.value = it },
                placeholder = { Text("Search calls or contacts…", color = TextMuted) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.searchQuery.value = "" }) {
                            Icon(Icons.Filled.Close, "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan400, unfocusedBorderColor = Surface3,
                    cursorColor = Cyan400, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )

            // Toggle: Recent / Contacts
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilterChip(
                    selected = !showContacts, onClick = { vm.showContacts.value = false },
                    label = { Text("Recent") }, modifier = Modifier.padding(end = 8.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan400, selectedLabelColor = Black)
                )
                FilterChip(
                    selected = showContacts, onClick = { vm.showContacts.value = true },
                    label = { Text("Contacts") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan400, selectedLabelColor = Black)
                )
            }

            if (!showContacts) {
                // ── Recent call logs ──
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No call logs yet", color = TextMuted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(logs, key = { it.id }) { log ->
                            val contact by produceState<ContactInfo?>(
                                initialValue = null, 
                                key1 = log.number
                            ) {
                                value = vm.suspendResolve(log.number)
                            }
                            ListItem(
                                modifier = Modifier.clickable { sheetTarget = SheetTarget(log.number) },
                                headlineContent = {
                                    Text(contact?.name ?: log.number, color = TextPrimary, fontWeight = FontWeight.Medium)
                                },
                                supportingContent = {
                                    Text(
                                        "${log.type.replaceFirstChar { it.uppercase() }} · ${formatTime(log.timestamp)}",
                                        color = TextSecondary
                                    )
                                },
                                leadingContent = {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Surface3), contentAlignment = Alignment.Center) {
                                        Icon(
                                            when (log.type) {
                                                "outgoing" -> Icons.Filled.CallMade
                                                "missed" -> Icons.Filled.CallMissed
                                                else -> Icons.Filled.CallReceived
                                            },
                                            null, tint = when (log.type) {
                                                "missed" -> Red400; "outgoing" -> Cyan400; else -> Green400
                                            }
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Black)
                            )
                        }
                    }
                }
            } else {
                // ── FIX #4: Contacts list with BottomSheet support ──
                if (contacts.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No contacts found", color = TextMuted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(contacts, key = { it.name }) { c ->
                            val parts = c.name.split("\n", limit = 2)
                            val contactNumber = parts.getOrElse(1) { "" }.trim()
                            ListItem(
                                modifier = Modifier.clickable {
                                    if (contactNumber.isNotBlank()) {
                                        sheetTarget = SheetTarget(contactNumber, isContact = true)
                                    }
                                },
                                headlineContent = { Text(parts.getOrElse(0) { "" }, color = TextPrimary) },
                                supportingContent = {
                                    if (contactNumber.isNotBlank()) Text(contactNumber, color = TextSecondary)
                                },
                                leadingContent = {
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Surface3), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Person, null, tint = Cyan400)
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Black)
                            )
                        }
                    }
                }
            }
        }
    }

    // ── FIX #4: Unified Action BottomSheet for both logs and contacts ──
    if (sheetTarget != null) {
        val target = sheetTarget!!
        val isNumberBlocked = remember(target.number, blocked) { vm.isBlocked(target.number) }

        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            containerColor = Surface2
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(target.number, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Spacer(Modifier.height(20.dp))

                // Call Back — FIX #4: actually triggers PhoneViewModel.dial()
                ListItem(
                    modifier = Modifier.clickable {
                        sheetTarget = null
                        vm.callBack(phoneVM, target.number)
                    },
                    headlineContent = { Text("Call", color = TextPrimary) },
                    leadingContent = { Icon(Icons.Filled.Call, null, tint = Green400) },
                    colors = ListItemDefaults.colors(containerColor = Surface2)
                )

                // Delete log (only for call logs, not contacts)
                if (!target.isContact) {
                    // Find the log entry to get its ID for deletion
                    val logEntry = logs.find { it.number == target.number }
                    if (logEntry != null) {
                        ListItem(
                            modifier = Modifier.clickable { vm.deleteLog(logEntry.id); sheetTarget = null },
                            headlineContent = { Text("Delete Log", color = TextPrimary) },
                            leadingContent = { Icon(Icons.Filled.Delete, null, tint = Red400) },
                            colors = ListItemDefaults.colors(containerColor = Surface2)
                        )
                    }
                }

                // FIX #4: Reactive Block / Unblock
                if (isNumberBlocked) {
                    ListItem(
                        modifier = Modifier.clickable { vm.unblockNumber(target.number); sheetTarget = null },
                        headlineContent = { Text("Unblock Number", color = Green400) },
                        leadingContent = { Icon(Icons.Filled.CheckCircle, null, tint = Green400) },
                        colors = ListItemDefaults.colors(containerColor = Surface2)
                    )
                } else {
                    ListItem(
                        modifier = Modifier.clickable { vm.blockNumber(target.number); sheetTarget = null },
                        headlineContent = { Text("Block Number", color = TextPrimary) },
                        leadingContent = { Icon(Icons.Filled.Block, null, tint = Orange400) },
                        colors = ListItemDefaults.colors(containerColor = Surface2)
                    )
                }

                // Save Contact
                ListItem(
                    modifier = Modifier.clickable { vm.saveContact(ctx, target.number); sheetTarget = null },
                    headlineContent = { Text("Save Contact", color = TextPrimary) },
                    leadingContent = { Icon(Icons.Filled.PersonAdd, null, tint = Cyan400) },
                    colors = ListItemDefaults.colors(containerColor = Surface2)
                )

                // Edit Contact — only for contacts (system intent)
                if (target.isContact) {
                    ListItem(
                        modifier = Modifier.clickable {
                            sheetTarget = null
                            vm.saveContact(ctx, target.number) // Opens system editor
                        },
                        headlineContent = { Text("Edit Contact", color = TextPrimary) },
                        leadingContent = { Icon(Icons.Filled.Edit, null, tint = Cyan400) },
                        colors = ListItemDefaults.colors(containerColor = Surface2)
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
