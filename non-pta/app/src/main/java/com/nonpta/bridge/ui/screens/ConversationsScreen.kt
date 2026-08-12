package com.nonpta.bridge.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.ConversationsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(navController: NavController, vm: ConversationsViewModel = viewModel()) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Black,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("newchat") },
                containerColor = Cyan400, contentColor = Black
            ) { Icon(Icons.Filled.Edit, "New Chat") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { vm.searchQuery.value = it },
                placeholder = { Text("Search conversations…", color = TextMuted) },
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

            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conversations yet", color = TextMuted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.threadNumber }) { conv ->
                        val contact by produceState<ContactInfo?>(
                            initialValue = null, 
                            key1 = conv.threadNumber
                        ) {
                            value = vm.suspendResolve(conv.threadNumber)
                        }
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = { navController.navigate("chat/${conv.threadNumber}") },
                                onLongClick = { deleteTarget = conv.threadNumber } // FIX #6
                            ),
                            headlineContent = {
                                Text(
                                    contact?.name ?: conv.threadNumber,
                                    color = TextPrimary, fontWeight = FontWeight.Medium
                                )
                            },
                            supportingContent = {
                                Text(
                                    conv.latestBody, color = TextSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        formatTs(conv.latestTimestamp),
                                        color = TextMuted, style = MaterialTheme.typography.labelSmall
                                    )
                                    if (conv.unreadCount > 0) {
                                        Spacer(Modifier.height(4.dp))
                                        Badge(containerColor = Cyan400, contentColor = Black) {
                                            Text("${conv.unreadCount}")
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape).background(Surface3),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (contact?.name ?: conv.threadNumber).take(1).uppercase(),
                                        color = Cyan400, fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Black)
                        )
                    }
                }
            }
        }
    }

    // FIX #6: Delete confirmation dialog on long-press
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = { vm.deleteThread(target); deleteTarget = null }) {
                    Text("Delete", color = Red400)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            title = { Text("Delete Conversation", color = TextPrimary) },
            text = { Text("Delete all messages with $target? This cannot be undone.", color = TextSecondary) },
            containerColor = Surface2,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

private fun formatTs(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
