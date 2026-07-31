package com.hermes.android.presentation.card

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.repository.MatrixRepository
import com.hermes.android.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
import javax.inject.Inject

private const val TAG = "CardViewModel"

/**
 * Backs [CardScreen]. Reads the original non-thread message text (passed via
 * the `card/{eventId}?text={...}` route arg, URL-decoded by Navigation) and
 * sends the user's reply to the room's **main** (Live) timeline — NOT a
 * thread-focused timeline, and with no `inReplyTo`. The agent treats the
 * sent message as a new thread root (agent-side behavior; the client does
 * not model that).
 *
 * Send payload format (strict):
 * ```
 * ```
 * {originalText}
 * ```
 * {userInput}
 * ```
 */
@HiltViewModel
class CardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val matrixRepository: MatrixRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Event id of the original non-thread message (route arg, may be empty). */
    val eventId: String = savedStateHandle.get<String>("eventId").orEmpty()

    private val _originalText = MutableStateFlow(savedStateHandle.get<String>("text").orEmpty())
    val originalText: StateFlow<String> = _originalText.asStateFlow()

    /**
     * Send [userInput] as a non-thread message on the bound room's main
     * timeline, prefixed with the original text in a fenced block. Opens a
     * fresh room handle + Live timeline just for this send and closes both
     * afterwards (the session list already maintains its own room handle via
     * [com.hermes.android.data.repository.RoomSessionListStore]).
     *
     * Returns the result on [onResult] (success Unit / failure) so the caller
     * can decide whether to pop back to the session list.
     */
    fun sendReply(userInput: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val outcome = runCatching {
                val roomId = settingsRepository.getBoundRoomId()
                    ?: error("No bound room id")
                val client = matrixRepository.getClient()
                    ?: error("Matrix client is not initialized")
                val formatted = "```\n${_originalText.value}\n```\n$userInput"
                withContext(Dispatchers.IO) {
                    val room = client.getRoom(roomId) ?: error("Room $roomId not found")
                    try {
                        val timeline = room.timeline()
                        try {
                            val messageType = MessageType.Text(TextMessageContent(formatted, null))
                            val content = timeline.createMessageContent(messageType)
                                ?: error("createMessageContent returned null")
                            timeline.send(content)
                            Log.d(TAG, "sendReply: sent on main timeline of $roomId")
                        } finally {
                            timeline.close()
                        }
                    } finally {
                        room.close()
                    }
                }
            }
            outcome.onFailure { Log.e(TAG, "sendReply failed", it) }
            // Normalize to Result<Unit>: discard the SendHandle payload so the
            // caller only cares about success/failure, not the SDK handle.
            onResult(outcome.map { })
        }
    }
}
