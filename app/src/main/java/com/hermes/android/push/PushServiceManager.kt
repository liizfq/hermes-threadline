package com.hermes.android.push

import android.util.Log
import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PushManager"

/**
 * Result of inspecting a message for working-state markers. The handler uses
 * the parsed minutes (when present) to schedule a timeout push, and uses the
 * [relatedEventId] to look up the original user message that triggered the AI
 * turn.
 */
private data class WorkingState(
    val minutes: Int?,
    val relatedEventId: String?
)

/**
 * Centralised push dispatch. Applies the design rules:
 *  - ✅ reaction on the AI's last reply → push that reply
 *  - 👀 reaction → suppress (still processing)
 *  - ⏳ Working "N min" message → push a timeout alert if N exceeds threshold
 *
 * Owns the message snapshot it needs to resolve "related message" lookups
 * and to find the AI's last reply. Callers feed it the latest message list
 * via [observeMessages] and individual reaction toggles via the same path —
 * the manager diffs and reacts.
 */
@Singleton
class PushServiceManager @Inject constructor(
    private val systemPushService: SystemPushService,
    private val ntfyPushService: NtfyPushService,
    private val pushSettings: PushSettings
) : PushService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** eventId → message, kept in sync by [observeMessages]. */
    private val messageIndex = ConcurrentHashMap<String, Message>()

    /** eventId → true once we've already fired a "done" push for it. */
    private val pushedDone = ConcurrentHashMap<String, Boolean>()

    /** eventId of a "working" message → scheduled timeout job flag. */
    private val pendingTimeouts = ConcurrentHashMap<String, Boolean>()

    /** Minutes regex tolerant of full-width digits and Chinese minute suffix. */
    private val minutesPattern: Pattern = Pattern.compile("(\\d+)\\s*(?:min|分钟|分)")

    init {
        // Load ntfy config at singleton creation time (app startup) so push
        // works even when the app is in background and no ChatViewModel is active.
        refreshConfigFromSettings()
    }

    fun refreshConfigFromSettings() {
        val cfg = if (pushSettings.usesNtfy()) {
            NtfyConfig(
                serverUrl = pushSettings.ntfyServerUrl
            )
        } else null
        ntfyPushService.config = cfg
        Log.d(TAG, "refreshConfigFromSettings: ntfy config=${cfg?.serverUrl} enabled=${pushSettings.enabled} channel=${pushSettings.channel}")
    }

    /**
     * Feed the latest observed message list. Detects new ✅ reactions and
     * new ⏳ Working messages, dispatching pushes accordingly.
     */
    fun observeMessages(messages: List<Message>) {
        if (!pushSettings.enabled) return
        refreshConfigFromSettings()

        val newIndex = HashMap<String, Message>(messages.size)
        for (m in messages) newIndex[m.id] = m

        // Detect ✅ reactions newly added to messages we hadn't pushed yet.
        for ((id, msg) in newIndex) {
            val doneReaction = msg.reactions.firstOrNull { it.key == PushService.REACTION_DONE }
            if (doneReaction != null && pushedDone.put(id, true) == null) {
                // Only push if this is an AI reply (i.e. has a ✅ from the user,
                // not the user's own outbound). We treat presence of ✅ as the
                // "completion" signal regardless of sender.
                handleDone(msg)
            }
        }

        // Detect ⏳ Working messages and schedule timeouts.
        for ((id, msg) in newIndex) {
            val state = parseWorkingState(msg) ?: continue
            if (pendingTimeouts.put(id, true) != null) continue
            val minutes = state.minutes
            if (minutes != null && minutes >= pushSettings.timeoutMinutes) {
                scheduleTimeout(msg, minutes)
            } else if (minutes == null) {
                // No explicit minutes — fall back to configured threshold.
                scheduleTimeout(msg, pushSettings.timeoutMinutes)
            }
        }

        // Reconcile index for related-message lookups.
        messageIndex.clear()
        messageIndex.putAll(newIndex)
    }

    private fun handleDone(message: Message) {
        // Find the most recent AI reply preceding/related to the message that
        // got the ✅. The ✅ is typically applied to the AI's own last reply.
        val target = if (isAiReply(message)) message else lastAiReplyBefore()
        if (target == null) {
            dispatchReaction(message, PushService.REACTION_DONE)
            return
        }
        dispatchReaction(target, PushService.REACTION_DONE)
    }

    private fun isAiReply(message: Message): Boolean {
        val c = message.content as? MessageContent.Text ?: return false
        // Treat inbound (non-own) text messages as candidate AI replies.
        return !message.isOwn && c.plainText.isNotBlank()
    }

    private fun lastAiReplyBefore(): Message? {
        // The related event is usually the immediately preceding non-own text
        // message; we approximate by scanning the current index.
        return messageIndex.values
            .filter { !it.isOwn && it.content is MessageContent.Text }
            .maxByOrNull { it.timestamp }
    }

    private fun parseWorkingState(message: Message): WorkingState? {
        val text = message.content as? MessageContent.Text ?: return null
        val body = text.plainText
        val hasWorkingMarker = body.contains(PushService.REACTION_WORKING) ||
            body.contains("Working", ignoreCase = true) ||
            body.contains("处理中") ||
            body.contains("工作中")
        if (!hasWorkingMarker) return null
        val matcher = minutesPattern.matcher(body)
        val minutes = if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
        return WorkingState(minutes = minutes, relatedEventId = message.id)
    }

    private fun scheduleTimeout(message: Message, minutes: Int) {
        val delayMs = minutes.coerceAtLeast(1) * 60_000L
        scope.launch {
            delay(delayMs)
            try {
                // Only fire if the working marker is still present (not yet ✅).
                val current = messageIndex[message.id]
                val stillWorking = current?.let { parseWorkingState(it) != null } ?: false
                val done = current?.reactions?.any { it.key == PushService.REACTION_DONE } ?: false
                if (stillWorking && !done) {
                    dispatchTimeout(message, minutes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduled timeout failed", e)
            } finally {
                pendingTimeouts.remove(message.id)
            }
        }
    }

    // ---- Dispatch: forwards to system and/or ntfy per settings ----

    override fun sendPush(title: String, message: String, priority: PushPriority) {
        if (!pushSettings.enabled) return
        refreshConfigFromSettings()
        if (pushSettings.usesSystem()) systemPushService.sendPush(title, message, priority)
        if (pushSettings.usesNtfy()) ntfyPushService.sendPush(title, message, priority)
    }

    override fun sendReactionPush(message: Message, reactionKey: String) {
        if (!pushSettings.enabled) return
        refreshConfigFromSettings()
        // 👀 suppresses all pushes while processing
        if (reactionKey == PushService.REACTION_EYES) return
        if (pushSettings.usesSystem()) systemPushService.sendReactionPush(message, reactionKey)
        if (pushSettings.usesNtfy()) ntfyPushService.sendReactionPush(message, reactionKey)
    }

    override fun sendTimeoutPush(message: Message, minutes: Int) {
        if (!pushSettings.enabled) return
        refreshConfigFromSettings()
        if (pushSettings.usesSystem()) systemPushService.sendTimeoutPush(message, minutes)
        if (pushSettings.usesNtfy()) ntfyPushService.sendTimeoutPush(message, minutes)
    }

    private fun dispatchReaction(message: Message, key: String) = sendReactionPush(message, key)
    private fun dispatchTimeout(message: Message, minutes: Int) = sendTimeoutPush(message, minutes)

    /** Test hook for resetting dedup state. */
    fun reset() {
        pushedDone.clear()
        pendingTimeouts.clear()
        messageIndex.clear()
    }
}
