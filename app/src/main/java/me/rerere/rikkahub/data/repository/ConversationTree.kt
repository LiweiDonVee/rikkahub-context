package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.ConversationBranchEntity
import me.rerere.rikkahub.data.db.entity.ConversationPathEntity

data class ConversationTree(
    val links: List<ConversationPathEntity>,
    val branches: List<ConversationBranchEntity>,
) {
    private val byId = links.associateBy { it.id }

    fun path(tipId: String?): List<ConversationPathEntity> =
        generateSequence(tipId?.let(byId::get)) { link -> link.parentId?.let(byId::get) }.toList().asReversed()

    fun branchForNode(nodeId: String): ConversationBranchEntity? =
        branches.firstOrNull { it.active && path(it.tipId).any { link -> link.nodeId == nodeId } }
            ?: branches.firstOrNull { path(it.tipId).any { link -> link.nodeId == nodeId } }

    fun reachableLinks(): Set<String> = buildSet {
        branches.forEach { branch ->
            var head = branch.tipId
            while (head != null && add(head)) head = byId[head]?.parentId
        }
    }
}

internal fun UIMessage.branchLabel(): String =
    parts.firstNotNullOfOrNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.takeIf { it.isNotBlank() }
            is UIMessagePart.Document -> part.fileName
            else -> null
        }
    }?.take(120)?.trim().orEmpty()
