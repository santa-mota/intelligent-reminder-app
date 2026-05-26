package com.santamota.reminder.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.santamota.reminder.data.db.ChatDao
import com.santamota.reminder.engine.ReminderEngine
import com.santamota.reminder.engine.Suggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val sending: Boolean = false,
    val pendingSuggestion: Suggestion? = null,
)

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val epochMs: Long,
) {
    enum class Role { USER, AGENT }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: ReminderEngine,
    chatDao: ChatDao,
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = chatDao.observeLatest(200)
        .map { rows -> rows.reversed().map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    fun send(text: String) {
        if (text.isBlank() || _ui.value.sending) return
        _ui.value = _ui.value.copy(sending = true, pendingSuggestion = null)
        viewModelScope.launch {
            val reply = engine.handle(text.trim())
            _ui.value = _ui.value.copy(
                sending = false,
                pendingSuggestion = reply.suggestion,
            )
        }
    }

    fun dismissSuggestion() {
        _ui.value = _ui.value.copy(pendingSuggestion = null)
    }
}

private fun com.santamota.reminder.data.db.ChatMessageEntity.toUi() = ChatMessage(
    id = id,
    role = ChatMessage.Role.valueOf(role),
    text = text,
    epochMs = createdEpochMs,
)
