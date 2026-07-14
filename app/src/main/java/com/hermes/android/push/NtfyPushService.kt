package com.hermes.android.push

import android.util.Log
import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.strEnZh
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NtfyPush"

/**
 * Configuration required to drive a single ntfy.sh-compatible endpoint.
 * Constructed by [PushServiceManager] from the user's persisted settings.
 */
data class NtfyConfig(
    val serverUrl: String,
    val topic: String = "",
    val username: String? = null,
    val password: String? = null,
    val token: String? = null
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
}

/**
 * Pushes notifications to a self-hosted or hosted ntfy instance via HTTP JSON.
 * No SDK required — only OkHttp + a plain POST to the configured server URL.
 */
@Singleton
class NtfyPushService @Inject constructor() : PushService {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    var config: NtfyConfig? = null

    private val jsonMediaType = "application/json".toMediaType()

    override fun sendPush(title: String, message: String, priority: PushPriority) {
        val cfg = config ?: run {
            Log.w(TAG, "sendPush skipped: ntfy not configured")
            return
        }
        if (!cfg.isConfigured) return
        post(
            cfg,
            title = title,
            message = message,
            tags = "bell",
            priorityCode = ntfyPriority(priority)
        )
    }

    override fun sendReactionPush(message: Message, reactionKey: String) {
        val cfg = config ?: return
        if (!cfg.isConfigured) return
        val locale = LocaleManager.currentLocale()
        val (title, body, tag) = when (reactionKey) {
            PushService.REACTION_DONE ->
                Triple(strEnZh(locale, "Hermes Agent reply complete", "Hermes Agent 回复完成"), bodyFor(message), "white_check_mark")
            PushService.REACTION_EYES ->
                Triple(strEnZh(locale, "Hermes Agent processing", "Hermes Agent 处理中"), bodyFor(message), "eyes")
            PushService.REACTION_WORKING ->
                Triple(strEnZh(locale, "Hermes Agent processing", "Hermes Agent 处理中"), bodyFor(message), "hourglass")
            else ->
                Triple("Hermes Agent", bodyFor(message), "bell")
        }
        post(cfg, title = title, message = body, tags = tag, priorityCode = ntfyPriority(PushPriority.HIGH))
    }

    override fun sendTimeoutPush(message: Message, minutes: Int) {
        val cfg = config ?: return
        if (!cfg.isConfigured) return
        val locale = LocaleManager.currentLocale()
        val body = bodyFor(message) + " (" + strEnZh(locale, "over ", "已超过 ") + minutes + strEnZh(locale, " min)", " 分钟)")
        post(
            cfg,
            title = strEnZh(locale, "Hermes Agent processing timeout", "Hermes Agent 处理超时"),
            message = body,
            tags = "warning",
            priorityCode = ntfyPriority(PushPriority.URGENT)
        )
    }

    private fun bodyFor(message: Message): String {
        val locale = LocaleManager.currentLocale()
        val sender = message.senderName ?: message.senderId
        val preview = when (val c = message.content) {
            is MessageContent.Text -> c.plainText.take(160)
            is MessageContent.File -> strEnZh(locale, "File: ", "文件: ") + c.fileName
            is MessageContent.Image -> strEnZh(locale, "Image", "图片")
            is MessageContent.Video -> strEnZh(locale, "Video", "视频")
            is MessageContent.Audio -> strEnZh(locale, "Audio", "音频")
            is MessageContent.Voice -> strEnZh(locale, "Voice message", "语音消息")
        }
        return sender + ": " + preview
    }

    private fun ntfyPriority(priority: PushPriority): Int = when (priority) {
        PushPriority.LOW -> 1
        PushPriority.DEFAULT -> 3
        PushPriority.HIGH -> 4
        PushPriority.URGENT -> 5
    }

    private fun post(
        cfg: NtfyConfig,
        title: String,
        message: String,
        tags: String,
        priorityCode: Int
    ) {
        val url = buildEndpoint(cfg)
        val payload = JSONObject().apply {
            put("topic", cfg.topic)
            put("message", message)
            put("title", title)
            put("tags", tags)
            put("priority", priorityCode)
        }
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))

        when {
            !cfg.token.isNullOrBlank() ->
                builder.addHeader("Authorization", "Bearer " + cfg.token)
            !cfg.username.isNullOrBlank() && !cfg.password.isNullOrBlank() -> {
                val auth = okhttp3.Credentials.basic(cfg.username, cfg.password)
                builder.addHeader("Authorization", auth)
            }
            else -> {}
        }

        Thread({
            try {
                httpClient.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "ntfy post failed: HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ntfy post error", e)
            }
        }, "ntfy-push").apply { isDaemon = true }.start()
    }

    private fun buildEndpoint(cfg: NtfyConfig): String {
        val base = cfg.serverUrl.trimEnd('/')
        return if (base.endsWith(cfg.topic)) base else "$base/${cfg.topic}"
    }
}
