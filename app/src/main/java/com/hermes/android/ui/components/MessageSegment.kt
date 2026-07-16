package com.hermes.android.ui.components

sealed interface MessageSegment {
    data class Text(
        val html: String?,
        val plainText: String,
        val isListBlock: Boolean = false,
    ) : MessageSegment

    data class Heading(
        val html: String,
        val plainText: String,
        val level: Int,
    ) : MessageSegment

    data class Thinking(
        val text: String,
    ) : MessageSegment

    data class ToolCall(
        val toolName: String,
        val arguments: String?,
        val status: ToolCallStatus = ToolCallStatus.UNKNOWN,
    ) : MessageSegment

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<TableAlignment>,
    ) : MessageSegment

    data class CodeBlock(
        val code: String,
        val language: String?,
    ) : MessageSegment
}

enum class ToolCallStatus { UNKNOWN, RUNNING, SUCCESS, FAILED }
enum class TableAlignment { LEFT, CENTER, RIGHT }
