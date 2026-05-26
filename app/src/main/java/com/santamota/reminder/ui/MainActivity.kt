package com.santamota.reminder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.santamota.reminder.R
import com.santamota.reminder.ui.chat.ChatScreen
import com.santamota.reminder.ui.reminders.RemindersScreen
import com.santamota.reminder.ui.theme.IntelligentReminderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IntelligentReminderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Tab(val title: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Chat(R.string.tab_chat, Icons.Default.Chat),
    Reminders(R.string.tab_reminders, Icons.Default.AccessTime),
}

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.Chat) }
    RequestRequiredPermissions()
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (entry in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.title)) },
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            Tab.Chat -> ChatScreen(modifier = Modifier.padding(padding))
            Tab.Reminders -> RemindersScreen(modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun stringResource(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
