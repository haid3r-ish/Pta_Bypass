package com.nonpta.bridge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nonpta.bridge.ui.theme.*
import com.nonpta.bridge.util.NumberValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(navController: NavController) {
    var number by remember { mutableStateOf("") }
    val isValid = NumberValidator.isValid(number)

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                title = { Text("New Chat", color = TextPrimary) },
                navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(24.dp)) {
            OutlinedTextField(
                value = number, onValueChange = { number = NumberValidator.normalize(it) },
                label = { Text("Phone number") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                isError = number.isNotBlank() && !isValid,
                supportingText = { if (number.isNotBlank() && !isValid) Text("Invalid number format", color = Red400) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan400, unfocusedBorderColor = Surface3,
                    cursorColor = Cyan400, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedLabelColor = Cyan400, unfocusedLabelColor = TextSecondary
                )
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { navController.navigate("chat/$number") { popUpTo("newchat") { inclusive = true } } },
                enabled = isValid, modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Black)
            ) { Text("Start Chat") }
        }
    }
}
