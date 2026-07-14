package com.hermes.android.domain.model

import java.time.Instant

data class Session(
    val id: String,                    // Thread Root Event ID
    val title: String,                 // First message (truncated)
    val lastMessage: String?,          // Latest message preview
    val lastActivityTime: Instant,     // Last activity time
    val replyCount: Int,               // Reply count
    val unreadCount: Int,              // Unread count
    val isProcessing: Boolean,         // Whether agent is processing
    val senderAvatarUrl: String?,      // Sender avatar
    val latestEventId: String? = null  // ThreadList latest event ID (reconciliation)
)