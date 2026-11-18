package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ConversationPathEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationTree
import me.rerere.rikkahub.data.repository.branchLabel
import me.rerere.rikkahub.ui.components.context.ContextSheet
import kotlin.uuid.Uuid

@Composable
internal fun ConversationBranchSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    loadTree: suspend () -> ConversationTree,
    onSelect: (branchId: String?, nodeId: Uuid?) -> Unit,
    onSelectVersion: ((Uuid, Int) -> Unit)? = null,
    title: String = stringResource(R.string.chat_branch_tree_title),
) {
    var tree by remember(conversation.id) { mutableStateOf<ConversationTree?>(null) }
    var error by remember(conversation.id) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showMessages by remember { mutableStateOf(false) }
    LaunchedEffect(conversation.id) {
        try {
            tree = loadTree()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message.orEmpty()
        }
    }
    val rows = remember(tree, conversation.messageNodes) {
        tree?.let { if (it.branches.isEmpty()) linearHistory(conversation) else it.branchRows() }.orEmpty()
    }
    val nodeById = remember(conversation.messageNodes) { conversation.messageNodes.associateBy { it.id.toString() } }
    val hasBranches = (tree?.branches?.size ?: 0) > 1
    val visible = remember(rows, hasBranches, showMessages, query, nodeById) {
        val term = query.trim()
        when {
            term.isNotEmpty() -> rows.filter { it.label.contains(term, ignoreCase = true) }
            showMessages || !hasBranches -> rows
            else -> rows.filter { it.isBranchPoint || (nodeById[it.nodeId]?.messages?.size ?: 0) > 1 }
        }
    }
    ContextSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, stringResource(R.string.cancel)) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.chat_branch_tree_search), maxLines = 1) },
            leadingIcon = { Icon(HugeIcons.Search01, null) },
        )
        if (hasBranches) {
            FilterChip(
                selected = showMessages,
                onClick = { showMessages = !showMessages },
                label = { Text(stringResource(R.string.chat_branch_tree_all_messages)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (tree == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message ->
                item { Text(message.ifBlank { stringResource(R.string.chat_branch_tree_error) }, color = MaterialTheme.colorScheme.error) }
            }
            if (tree != null && visible.isEmpty()) item {
                Text(stringResource(if (rows.isEmpty()) R.string.chat_branch_tree_empty else R.string.search_page_no_results))
            }
            items(visible, key = { it.key }) { row ->
                val activeNode = row.nodeId?.let(nodeById::get)?.takeIf { row.active && row.number == null && onSelectVersion != null }
                BranchRow(
                    row = row,
                    node = activeNode,
                    onClick = { onSelect(row.branchId, row.nodeId?.let(Uuid::parse)) },
                    onSelectVersion = { index -> activeNode?.let { onSelectVersion?.invoke(it.id, index) } },
                )
            }
        }
    }
}

@Composable
private fun BranchRow(row: BranchTreeRow, node: MessageNode?, onClick: () -> Unit, onSelectVersion: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = when {
            row.active && row.number != null -> colors.primaryContainer
            row.active -> colors.surfaceContainerHigh
            else -> colors.surfaceContainerLow
        },
        modifier = Modifier.padding(start = (row.depth.coerceAtMost(5) * 12).dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (row.number != null) {
                Icon(if (row.active) HugeIcons.Tick01 else HugeIcons.ArrowRight01, null, Modifier.size(20.dp))
            } else {
                Box(
                    Modifier.width(3.dp).height(22.dp).background(
                        if (row.active) colors.primary else colors.outlineVariant,
                        CircleShape,
                    )
                )
            }
            val label = when {
                row.number != null && row.active -> stringResource(R.string.chat_branch_tree_active)
                row.number != null -> stringResource(R.string.chat_branch_tree_path, row.number)
                row.label.isNotBlank() -> row.label
                row.role == "ASSISTANT" -> stringResource(R.string.chat_branch_tree_reply)
                else -> stringResource(R.string.chat_branch_tree_message)
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (node != null && node.messages.size > 1) {
                var showVersions by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showVersions = true }) {
                        Text(stringResource(R.string.chat_branch_tree_versions, node.messages.size))
                    }
                    DropdownMenu(expanded = showVersions, onDismissRequest = { showVersions = false }) {
                        node.messages.forEachIndexed { index, message ->
                            DropdownMenuItem(
                                text = {
                                    Text(message.branchLabel().takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.chat_branch_tree_version, index + 1), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                leadingIcon = { Icon(if (node.selectIndex == index) HugeIcons.Tick01 else HugeIcons.Message01, null) },
                                onClick = { showVersions = false; onSelectVersion(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class BranchTreeRow(
    val key: String,
    val branchId: String?,
    val nodeId: String?,
    val label: String,
    val role: String,
    val depth: Int,
    val active: Boolean,
    val number: Int? = null,
    val isBranchPoint: Boolean = true,
)

private fun linearHistory(conversation: Conversation): List<BranchTreeRow> =
    conversation.messageNodes.map { node ->
        val message = node.currentMessage
        BranchTreeRow(node.id.toString(), null, node.id.toString(), message.branchLabel(), message.role.name, 0, true)
    }

private fun ConversationTree.branchRows(): List<BranchTreeRow> {
    val children = links.groupBy { it.parentId }
    val heads = branches.withIndex().groupBy { it.value.tipId }
    val activeBranch = branches.firstOrNull { it.active }
    val activePath = path(activeBranch?.tipId).mapTo(mutableSetOf()) { it.id }
    val preferredBranches = mutableMapOf<String, String>()
    val byId = links.associateBy { it.id }
    (listOfNotNull(activeBranch) + branches.filterNot { it.active }).forEach { branch ->
        var linkId = branch.tipId
        while (linkId != null && preferredBranches.putIfAbsent(linkId, branch.id) == null) {
            linkId = byId[linkId]?.parentId
        }
    }
    return buildList {
        val pending = ArrayDeque<Pair<ConversationPathEntity, Int>>()
        fun enqueue(parentId: String?, depth: Int) {
            val siblings = children[parentId].orEmpty().sortedBy { it.id !in activePath }
            for (child in siblings.asReversed()) pending.addLast(child to depth)
        }
        heads[null].orEmpty().forEach { (index, branch) ->
            add(BranchTreeRow("branch:${branch.id}", branch.id, null, "", "", 0, branch.active, index + 1))
        }
        enqueue(null, 0)
        while (pending.isNotEmpty()) {
            val (link, depth) = pending.removeLast()
            val branchId = preferredBranches[link.id] ?: continue
            val forks = children[link.id].orEmpty().size > 1 || heads[link.id].orEmpty().isNotEmpty()
            add(BranchTreeRow(
                "message:${link.id}", branchId, link.nodeId, link.label, link.role, depth,
                link.id in activePath, isBranchPoint = forks || link.parentId == null,
            ))
            heads[link.id].orEmpty().forEach { (index, branch) ->
                add(BranchTreeRow("branch:${branch.id}", branch.id, link.nodeId, link.label, link.role, depth, branch.active, index + 1))
            }
            enqueue(link.id, if (forks) depth + 1 else depth)
        }
    }
}
