package me.rerere.rikkahub.data.model

import kotlin.uuid.Uuid

enum class ContextConversationSort {
    UPDATED_DESC,
    UPDATED_ASC,
    CREATED_DESC,
    CREATED_ASC,
    TITLE_ASC,
    TITLE_DESC,
}

sealed interface ContextInsertionPosition {
    data object Start : ContextInsertionPosition
    data object End : ContextInsertionPosition
    data class Before(val nodeId: Uuid) : ContextInsertionPosition
    data class After(val nodeId: Uuid) : ContextInsertionPosition
}

/** Resolve against the current session so End and node anchors keep their meaning while picking a source. */
fun Conversation.insertContextNodes(position: ContextInsertionPosition, nodes: List<MessageNode>): Conversation {
    val index = when (position) {
        ContextInsertionPosition.Start -> 0
        ContextInsertionPosition.End -> messageNodes.size
        is ContextInsertionPosition.Before, is ContextInsertionPosition.After -> {
            val nodeId = when (position) {
                is ContextInsertionPosition.Before -> position.nodeId
                is ContextInsertionPosition.After -> position.nodeId
                else -> error("Unreachable")
            }
            val anchor = messageNodes.indexOfFirst { it.id == nodeId }
            require(anchor >= 0) { "插入位置对应的消息已删除，请重新选择位置" }
            anchor + if (position is ContextInsertionPosition.After) 1 else 0
        }
    }
    return copy(messageNodes = messageNodes.take(index) + nodes + messageNodes.drop(index))
}
