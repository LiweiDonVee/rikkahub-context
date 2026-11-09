package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Preserve Rikka's preset history semantics; only local attachment ownership differs per conversation. */
internal fun copyPresetMessageFiles(messages: List<UIMessage>, copyFile: (String) -> String): List<UIMessage> {
    val owned = mutableMapOf<String, String>()
    fun copy(url: String) = owned.getOrPut(url) { copyFile(url) }
    return messages.map { message ->
        val result = message.copy(
            parts = message.parts.map { it.mapLocalFileUrls(::copy) },
            contextState = message.contextState.copy(
                originalParts = message.contextState.originalParts?.map { it.mapLocalFileUrls(::copy) },
                toolRuns = message.contextState.toolRuns.map {
                    it.copy(
                        tool = it.tool.mapLocalFileUrls(::copy) as UIMessagePart.Tool
                    )
                },
            ),
        )
        if (result == message) message else result
    }
}
