package com.hermes.android.domain.model

import java.time.Instant

data class Message(
    val id: String,
    val senderId: String,
    val senderName: String?,
    val senderAvatarUrl: String?,
    val content: MessageContent,
    val timestamp: Instant,
    val isOwn: Boolean,
    val reactions: List<Reaction> = emptyList()
)

data class Reaction(
    val key: String,
    val count: Int,
    val senders: List<ReactionSender>,
    val isOwn: Boolean
)

data class ReactionSender(
    val senderId: String,
    val timestamp: Instant
)
