package com.nonpta.bridge.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nonpta.bridge.data.db.entity.MessageEntity
import com.nonpta.bridge.model.ContactInfo
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(threadNumber: String, navController: NavController, vm: ChatViewModel = viewModel()) {
    LaunchedEffect(threadNumber) { vm.setThread(threadNumber) }

    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.inputText.collectAsStateWithLifecycle()
    val contact by produceState<ContactInfo?>(
        initialValue = null, 
        key1 = threadNumber
    ) {
        value = vm.suspendResolve(threadNumber)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Scaffold(
        containerColor = Black,
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(contact?.name ?: threadNumber, color = TextPrimary) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1)
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(messages, key = { it.id }) { msg -> ChatBubble(msg) }
            }

            Surface(color = Surface1) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { vm.inputText.value = it },
                        placeholder = { Text("Message…", color = TextMuted) },
                        modifier = Modifier.weight(1f), maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan400, unfocusedBorderColor = Surface3,
                            cursorColor = Cyan400, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { vm.sendMessage() },
                        enabled = input.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Cyan400, contentColor = Black, disabledContainerColor = Surface3)
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: MessageEntity) {
    val tf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val align = if (msg.isOutgoing) Alignment.End else Alignment.Start
    val bg = if (msg.isOutgoing) Cyan800.copy(0.7f) else Surface3
    val shape = if (msg.isOutgoing) RoundedCornerShape(16.dp,16.dp,4.dp,16.dp) else RoundedCornerShape(16.dp,16.dp,16.dp,4.dp)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(Modifier.widthIn(max = 280.dp).clip(shape).background(bg).padding(12.dp, 8.dp).animateContentSize()) {
            Column {
                Text(msg.body, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(tf.format(Date(msg.timestamp)), color = TextMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}
