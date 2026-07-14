package com.hermes.android.push

import com.hermes.android.domain.model.Message

interface PushService {

    fun sendPush(title: String, message: String, priority: PushPriority = PushPriority.DEFAULT)

    fun sendReactionPush(message: Message, reactionKey: String)

    fun sendTimeoutPush(message: Message, minutes: Int)

    companion object {
        const val REACTION_DONE = "✅"
        const val REACTION_EYES = "👀"
        const val REACTION_WORKING = "⏳"
    }
}
