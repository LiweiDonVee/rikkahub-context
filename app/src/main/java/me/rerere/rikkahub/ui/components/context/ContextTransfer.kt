package me.rerere.rikkahub.ui.components.context

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.LocalSettings
import org.koin.compose.koinInject
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private val ContextConversationSort.label: String get() = when (this) {
    ContextConversationSort.UPDATED_DESC -> "最近更新"
    ContextConversationSort.UPDATED_ASC -> "最早更新"
    ContextConversationSort.CREATED_DESC -> "最新创建"
    ContextConversationSort.CREATED_ASC -> "最早创建"
    ContextConversationSort.TITLE_ASC -> "标题 A → Z"
    ContextConversationSort.TITLE_DESC -> "标题 Z → A"
}

private fun contextMessageText(message: UIMessage): String = message.parts.joinToString(" · ") { part ->
    when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Reasoning -> "思考：${part.reasoning}"
        is UIMessagePart.Tool -> "${part.toolName} ${part.input} ${part.output.joinToString { output -> if (output is UIMessagePart.Text) output.text else contextPartLabel(output) }}"
        is UIMessagePart.Document -> part.fileName
        else -> contextPartLabel(part)
    }
}

private fun contextMessageSummary(message: UIMessage) = contextMessageText(message).replace('\n', ' ').take(180).ifBlank { "空消息" }

private fun positionLabel(conversation: Conversation, position: ContextInsertionPosition): String = when (position) {
    ContextInsertionPosition.Start -> "会话开头"
    ContextInsertionPosition.End -> "会话结尾"
    is ContextInsertionPosition.Before, is ContextInsertionPosition.After -> {
        val id = if (position is ContextInsertionPosition.Before) position.nodeId else (position as ContextInsertionPosition.After).nodeId
        val index = conversation.messageNodes.indexOfFirst { it.id == id }
        val message = conversation.messageNodes.getOrNull(index)?.currentMessage
        if (message == null) "原位置已删除，请重新选择" else
            "第 ${index + 1} 条 · ${contextRoleLabel(message.role)}${if (position is ContextInsertionPosition.Before) "之前" else "之后"}\n${contextMessageSummary(message)}"
    }
}

@Composable
private fun ContextPickerHeader(title: String, onDismiss: () -> Unit, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭$title") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        action()
    }
}

/** A shared lightweight, paged browser for both the source and the destination. */
@Composable
fun ContextConversationPicker(title: String, excludedId: Uuid, onDismiss: () -> Unit, onSelect: (Conversation) -> Unit) {
    val repository: ConversationRepository = koinInject()
    val settings = LocalSettings.current
    var assistantId by remember { mutableStateOf<Uuid?>(null) }
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ContextConversationSort.UPDATED_DESC) }
    var showAssistants by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    LaunchedEffect(query) { delay(250); search = query }
    val flow = remember(assistantId, search, sort, excludedId) { repository.contextConversations(assistantId, search, sort, excludedId) }
    val conversations = flow.collectAsLazyPagingItems()
    ContextSheet(onDismiss) {
        ContextPickerHeader(title, onDismiss)
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            shape = MaterialTheme.shapes.extraLarge, placeholder = { Text("搜索会话标题") }, leadingIcon = { Icon(HugeIcons.Search01, null) })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(modifier = Modifier.weight(1f, fill = false), selected = assistantId != null, onClick = { showAssistants = true }, label = { Text(settings.assistants.find { it.id == assistantId }?.name ?: "全部助手", maxLines = 1, overflow = TextOverflow.Ellipsis) })
            Box {
                FilterChip(selected = false, onClick = { showSort = true }, label = { Text(sort.label) })
                DropdownMenu(showSort, { showSort = false }) { ContextConversationSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; showSort = false })
                } }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (conversations.loadState.refresh is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (conversations.loadState.refresh is LoadState.Error) item { TextButton(onClick = { conversations.retry() }) { Text("加载失败，重试") } }
            if (conversations.itemCount == 0 && conversations.loadState.refresh is LoadState.NotLoading) item { Text("没有匹配的会话") }
            items(conversations.itemCount, key = { conversations.peek(it)?.id?.toString() ?: "loading_$it" }) { index -> conversations[index]?.let { conversation ->
                CardGroup { item(onClick = { onSelect(conversation) }, leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                    headlineContent = { Text(conversation.title.ifBlank { "未命名会话" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val date = if (sort == ContextConversationSort.CREATED_ASC || sort == ContextConversationSort.CREATED_DESC) conversation.createAt else conversation.updateAt
                        Text("${settings.assistants.find { it.id == conversation.assistantId }?.name ?: "已删除的助手"} · ${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(date)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }, trailingContent = { Icon(HugeIcons.ArrowRight01, null) }) }
            } }
            if (conversations.loadState.append is LoadState.Loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
            if (conversations.loadState.append is LoadState.Error) item { TextButton(onClick = { conversations.retry() }) { Text("加载更多，重试") } }
        }
    }
    if (showAssistants) {
        var assistantQuery by remember { mutableStateOf("") }
        ContextSheet({ showAssistants = false }) {
            ContextPickerHeader("筛选助手", { showAssistants = false })
            TextField(assistantQuery, { assistantQuery = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true, shape = MaterialTheme.shapes.extraLarge, placeholder = { Text("搜索助手名称") }, leadingIcon = { Icon(HugeIcons.Search01, null) })
            LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { CardGroup { item(onClick = { assistantId = null; showAssistants = false }, headlineContent = { Text("全部助手") }) } }
                items(settings.assistants.filter { it.name.contains(assistantQuery, ignoreCase = true) }.sortedBy { it.name.lowercase() }, key = { it.id }) { assistant ->
                    CardGroup { item(onClick = { assistantId = assistant.id; showAssistants = false }, headlineContent = { Text(assistant.name) }, trailingContent = { if (assistant.id == assistantId) Icon(HugeIcons.Tick01, null) }) }
                }
            }
        }
    }
}

@Composable
fun ContextPositionPicker(conversation: Conversation, onDismiss: () -> Unit, onSelect: (ContextInsertionPosition) -> Unit) {
    var query by remember { mutableStateOf("") }
    val rows = remember(conversation.messageNodes) { conversation.messageNodes.mapIndexed { index, node -> Triple(index, node, contextMessageText(node.currentMessage)) } }
    ContextSheet(onDismiss) {
        ContextPickerHeader("选择插入位置", onDismiss)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onSelect(ContextInsertionPosition.Start) }) { Text("会话开头") }
            FilledTonalButton(onClick = { onSelect(ContextInsertionPosition.End) }) { Text("会话结尾") }
        }
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true, shape = MaterialTheme.shapes.extraLarge, placeholder = { Text("搜索位置附近的消息") }, leadingIcon = { Icon(HugeIcons.Search01, null) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) item { Text("空会话，内容将从第一条开始") }
            items(rows.filter { it.third.contains(query, ignoreCase = true) }, key = { it.second.id }) { (index, node, _) ->
                CardGroup { item(headlineContent = { Text("第 ${index + 1} 条 · ${contextRoleLabel(node.currentMessage.role)}") }, supportingContent = { Text(contextMessageSummary(node.currentMessage), maxLines = 3, overflow = TextOverflow.Ellipsis) })
                    item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onSelect(ContextInsertionPosition.Before(node.id)) }) { Text("插在第 ${index + 1} 条之前") }
                        TextButton(onClick = { onSelect(ContextInsertionPosition.After(node.id)) }) { Text("插在第 ${index + 1} 条之后") }
                    } }
                }
            }
        }
    }
}

@Composable
private fun ContextMessagePicker(conversation: Conversation, selected: Set<Uuid>, onDismiss: () -> Unit, onSelect: (Set<Uuid>) -> Unit) {
    var selection by remember(conversation.id) { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val rows = remember(conversation.currentMessages) { conversation.currentMessages.map { it to contextMessageText(it) } }
    val visible = rows.filter { it.second.contains(query, ignoreCase = true) }
    ContextSheet(onDismiss) {
        ContextPickerHeader("选择来源消息", onDismiss) { FilledTonalButton(onClick = { onSelect(selection) }) { Text("使用 ${selection.size} 条") } }
        TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = MaterialTheme.shapes.extraLarge, placeholder = { Text("搜索消息内容") }, leadingIcon = { Icon(HugeIcons.Search01, null) })
        Row(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { selection = selection + visible.map { it.first.id } }) { Text(if (query.isBlank()) "全选" else "选中搜索结果") }
            TextButton(onClick = { selection = emptySet() }) { Text("清空选择") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (visible.isEmpty()) item { Text("没有匹配的消息") }
            items(visible, key = { it.first.id }) { (message, _) ->
                fun select() { selection = if (message.id in selection) selection - message.id else selection + message.id }
                CardGroup { item(onClick = { select() }, leadingContent = { Checkbox(message.id in selection, { select() }) },
                    headlineContent = { Text(contextRoleLabel(message.role)) }, supportingContent = { Text(contextMessageSummary(message), maxLines = 3, overflow = TextOverflow.Ellipsis) }) }
            }
        }
    }
}

/** The same controls support copying out of a source and importing into a fixed target position. */
@Composable
fun ContextTransferPanel(fixedSource: Conversation? = null, fixedTarget: Conversation? = null, initialMessageId: Uuid? = null,
    initialPosition: ContextInsertionPosition = ContextInsertionPosition.End, enabled: Boolean = true,
    onPositionChange: (ContextInsertionPosition) -> Unit = {}, onDone: (Uuid, List<Uuid>) -> Unit) {
    val service: ChatService = koinInject()
    val scope = rememberCoroutineScope()
    var chosenSource by remember { mutableStateOf<Conversation?>(null) }
    var chosenTarget by remember { mutableStateOf<Conversation?>(null) }
    val source = fixedSource ?: chosenSource
    val target = fixedTarget ?: chosenTarget
    var selected by remember(fixedSource?.id) { mutableStateOf(setOfNotNull(initialMessageId)) }
    var position by remember(fixedTarget?.id) { mutableStateOf(initialPosition) }
    var picker by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("original") }
    var modeMenu by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load(conversation: Conversation, isSource: Boolean) {
        picker = null
        scope.launch {
            working = true; error = null
            try {
                val full = service.getConversation(conversation.id) ?: error("会话已删除")
                if (isSource) { chosenSource = full; selected = emptySet(); picker = "messages" }
                else { chosenTarget = full; position = ContextInsertionPosition.End }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            finally { working = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardGroup {
            if (fixedSource == null) item(onClick = { picker = "source" }, leadingContent = { CardGroupIcon(HugeIcons.Search01) }, overlineContent = { Text("来源会话") }, headlineContent = { Text(source?.title?.ifBlank { "未命名会话" } ?: "查找来源会话") }, trailingContent = { Icon(HugeIcons.ArrowRight01, null) })
            item(onClick = { if (source != null) picker = "messages" }, leadingContent = { CardGroupIcon(HugeIcons.Message01) }, headlineContent = { Text("选择消息") }, supportingContent = { Text("已选 ${selected.size} 条 · 支持搜索和非连续选择") }, trailingContent = { Icon(HugeIcons.ArrowRight01, null) })
            if (fixedTarget == null) item(onClick = { picker = "target" }, leadingContent = { CardGroupIcon(HugeIcons.Search01) }, overlineContent = { Text("目标会话") }, headlineContent = { Text(target?.title?.ifBlank { "未命名会话" } ?: "查找目标会话") }, trailingContent = { Icon(HugeIcons.ArrowRight01, null) })
            item(onClick = { if (target != null) picker = "position" }, leadingContent = { CardGroupIcon(HugeIcons.Add01) }, overlineContent = { Text("插入位置") }, headlineContent = { Text(target?.let { positionLabel(it, position) } ?: "先选择目标会话", maxLines = 3, overflow = TextOverflow.Ellipsis) }, trailingContent = { Icon(HugeIcons.ArrowRight01, null) })
        }
        Box {
            CardGroup { item(onClick = { modeMenu = true }, overlineContent = { Text("导入方式") }, headlineContent = { Text(when (mode) { "text" -> "转普通文本"; "merged" -> "合并为一条用户消息"; else -> "原样复制" }) }, trailingContent = { Icon(HugeIcons.ArrowDown01, null) }) }
            DropdownMenu(modeMenu, { modeMenu = false }) { listOf("original" to "原样复制", "text" to "转普通文本", "merged" to "合并为一条用户消息").forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { mode = id; modeMenu = false }) } }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = enabled && !working && source != null && target != null && selected.isNotEmpty(), onClick = {
            scope.launch {
                working = true; error = null
                try { val nodes = service.copyContextMessages(source!!.id, selected, target!!.id, position, mode); onDone(target.id, nodes) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "导入失败" }
                finally { working = false }
            }
        }) { Text(if (fixedTarget != null) "导入到此位置" else "复制并打开目标") }
    }
    when (picker) {
        "source" -> fixedTarget?.let { ContextConversationPicker("选择来源会话", it.id, { picker = null }) { chosen -> load(chosen, true) } }
        "target" -> source?.let { ContextConversationPicker("选择目标会话", it.id, { picker = null }) { chosen -> load(chosen, false) } }
        "messages" -> source?.let { ContextMessagePicker(it, selected, { picker = null }) { ids -> selected = ids; picker = null } }
        "position" -> target?.let { ContextPositionPicker(it, { picker = null }) { picked -> position = picked; onPositionChange(picked); picker = null } }
    }
}

@Composable
fun ContextInsertionSheet(conversation: Conversation, initialPosition: ContextInsertionPosition = ContextInsertionPosition.End,
    onDismiss: () -> Unit, onInserted: (List<Uuid>) -> Unit) {
    val service: ChatService = koinInject()
    var position by remember(conversation.id) { mutableStateOf(initialPosition) }
    var mode by remember { mutableStateOf("choose") }
    var choosingPosition by remember { mutableStateOf(false) }
    if (mode == "new") {
        val draft = remember { UIMessage(role = MessageRole.USER, parts = emptyList()) }
        MessagePartsEditor(draft, { mode = "choose" }, title = "新建上下文", allowRoleChange = true, isNew = true,
            loadTools = { service.authorizedContextTools(conversation.id) }, onSave = { edited, _ ->
                val nodes = service.insertContextMessages(conversation.id, position, listOf(edited)); onInserted(nodes)
            })
    } else ContextSheet(onDismiss) {
        ContextPickerHeader(if (mode == "import") "导入上下文" else "插入上下文", onDismiss)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(conversation.title.ifBlank { "当前会话" }, style = MaterialTheme.typography.titleMediumEmphasized) }
            if (mode == "choose") {
                item { CardGroup { item(onClick = { choosingPosition = true }, overlineContent = { Text("插入位置") }, headlineContent = { Text(positionLabel(conversation, position), maxLines = 3, overflow = TextOverflow.Ellipsis) }, trailingContent = { Icon(HugeIcons.ArrowDown01, null) }) } }
                item { CardGroup {
                    item(onClick = { mode = "new" }, leadingContent = { CardGroupIcon(HugeIcons.Add01) }, headlineContent = { Text("新建上下文") }, supportingContent = { Text("创建正文、思考、附件或工具内容") })
                    item(onClick = { mode = "import" }, leadingContent = { CardGroupIcon(HugeIcons.Copy01) }, headlineContent = { Text("从其他会话导入") }, supportingContent = { Text("按助手筛选和搜索来源，选择需要的消息") })
                } }
            } else {
                item { ContextTransferPanel(fixedTarget = conversation, initialPosition = position, onPositionChange = { position = it }, onDone = { _, nodes -> onInserted(nodes) }) }
                item { TextButton(onClick = { mode = "choose" }) { Text("返回插入方式") } }
            }
        }
    }
    if (choosingPosition) ContextPositionPicker(conversation, { choosingPosition = false }) { position = it; choosingPosition = false }
}
