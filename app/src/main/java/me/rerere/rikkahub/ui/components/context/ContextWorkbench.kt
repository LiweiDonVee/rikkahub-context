package me.rerere.rikkahub.ui.components.context

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.context.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.diffRequests
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.ai.RequestReviewGate
import me.rerere.rikkahub.data.db.entity.ContextRequestEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private enum class ContextTab(override val icon: androidx.compose.ui.graphics.vector.ImageVector, private val title: String) : PagerTab {
    Content(HugeIcons.Edit03, "内容"), Request(HugeIcons.Settings03, "本次请求"), Records(HugeIcons.Search01, "请求记录"), Transfer(HugeIcons.Copy01, "跨会话");
    override val label: String @Composable get() = title
}

@Composable
fun ContextWorkbench(conversationId: Uuid, initialMessageId: Uuid, onDismiss: () -> Unit) {
    val service: ChatService = koinInject()
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    val conversation by remember(conversationId) { service.getConversationFlow(conversationId) }.collectAsState()
    val job by remember(conversationId) { service.getGenerationJobStateFlow(conversationId) }.collectAsState(null)
    val snapshots by remember(conversationId) { service.contextRequestStore.observe(conversationId) }.collectAsState(emptyList())
    var messageId by remember { mutableStateOf(initialMessageId) }
    val message = conversation.currentMessages.find { it.id == messageId }
    val pager = rememberPagerState { ContextTab.entries.size }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var editorMessage by remember { mutableStateOf<UIMessage?>(null) }
    var appendedPart by remember { mutableStateOf<UIMessagePart?>(null) }
    var insertion by remember { mutableStateOf<UIMessage?>(null) }
    var insertionAfter by remember { mutableStateOf<Uuid?>(null) }
    var choosingMessage by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var replayPart by remember { mutableStateOf<Int?>(null) }
    var selection by remember { mutableStateOf(ContextSelection()) }
    LaunchedEffect(messageId) { selection = message?.contextState?.requestSelection ?: ContextSelection() }
    var createBranch by remember { mutableStateOf(false) }
    var newVariant by remember { mutableStateOf(false) }
    var gate by remember { mutableStateOf<RequestReviewGate?>(null) }
    var requestConversationId by remember { mutableStateOf(conversationId) }
    val busy = working || job?.isActive == true
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            working = true; error = null; feedback = null
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            finally { working = false }
        }
    }
    fun edit(newPart: UIMessagePart? = null) { appendedPart = newPart; editorMessage = message }
    fun insert(before: Boolean) {
        val index = conversation.currentMessages.indexOfFirst { it.id == messageId }
        insertionAfter = if (before) conversation.currentMessages.getOrNull(index - 1)?.id else message?.id
        insertion = UIMessage(role = MessageRole.USER, parts = emptyList())
    }
    fun preview(part: Int?, continueAfter: Boolean) = perform {
        val target = if (createBranch) service.forkConversationAtMessage(conversationId, messageId).id else conversationId
        requestConversationId = target
        gate = service.previewContextRequest(target, messageId, part, continueAfter, selection, newVariant)
    }
    ContextSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭") }
            Text("上下文工作台", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        PagerTabs(pager, ContextTab.entries, contentPadding = PaddingValues(horizontal = 16.dp))
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (error != null || feedback != null || working) item {
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
                if (page != 3) item {
                    CardGroup {
                        item(onClick = { choosingMessage = true }, leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                            overlineContent = { Text(message?.let { contextRoleLabel(it.role) } ?: "历史消息") },
                            headlineContent = { Text(message?.let { partPreview(it.parts.firstOrNull()) }.orEmpty().ifBlank { "选择消息" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${message?.parts?.size ?: 0} 项内容 · 点按切换消息") },
                            trailingContent = { if (message != null) ItemActionMenu(listOf(
                                ItemAction("插入消息之前", HugeIcons.Add01, enabled = !busy) { insert(true) },
                                ItemAction("插入消息之后", HugeIcons.Add01, enabled = !busy) { insert(false) },
                                ItemAction("创建会话分支", HugeIcons.Copy01, enabled = !busy) { perform {
                                    val branch = service.forkConversationAtMessage(conversationId, messageId)
                                    onDismiss(); navigateToChatPage(navigator, branch.id)
                                } },
                                ItemAction("恢复原始内容", HugeIcons.Refresh03, enabled = !busy && message.contextState.originalParts != null) { perform { service.restoreContextMessage(conversationId, message.id) } },
                            )) else Icon(HugeIcons.ArrowDown01, null) },
                        )
                    }
                }
                when (page) {
                    0 -> {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("消息内容", Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
                                Box {
                                    FilledTonalIconButton(onClick = { adding = true }, enabled = !busy && message != null) { Icon(HugeIcons.Add01, "添加内容") }
                                    DropdownMenu(adding, { adding = false }) {
                                        DropdownMenuItem(text = { Text("正文") }, leadingIcon = { Icon(HugeIcons.Text, null) }, onClick = { adding = false; edit(UIMessagePart.Text("")) })
                                        DropdownMenuItem(text = { Text("思考") }, leadingIcon = { Icon(HugeIcons.Brain02, null) }, onClick = { adding = false; edit(UIMessagePart.Reasoning("").withUserReasoning("")) })
                                        DropdownMenuItem(text = { Text("工具调用") }, leadingIcon = { Icon(HugeIcons.Code, null) }, onClick = { adding = false; edit(UIMessagePart.Tool(Uuid.random().toString(), "", "{}")) })
                                    }
                                }
                            }
                            if (message != null) {
                                CardGroup {
                                    switchItem(checked = !message.contextState.excluded, enabled = !busy, onCheckedChange = { included -> perform { service.setContextExclusion(conversationId, message.id, excluded = !included) } }, headlineContent = { Text("下次请求包含此消息") })
                                }
                                Spacer(Modifier.height(12.dp))
                                FilledTonalButton(onClick = { edit() }, enabled = !busy) { Icon(HugeIcons.Edit03, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("编辑完整内容") }
                            } else FilledTonalButton(onClick = { insert(false) }, enabled = !busy) { Text("插入消息") }
                        }
                        if (message != null) {
                            item {
                                CardGroup {
                                    if (message.parts.isEmpty()) item(headlineContent = { Text("这条消息还没有内容") }, supportingContent = { Text("点按上方 + 添加正文或思考。") }, leadingContent = { CardGroupIcon(HugeIcons.Add01) })
                                    message.parts.forEachIndexed { index, part ->
                                        item(onClick = { edit() }, leadingContent = { CardGroupIcon(contextPartIcon(part)) },
                                            headlineContent = { Text(contextPartLabel(part)) },
                                            supportingContent = { Text(partPreview(part).ifBlank { "空内容" }, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                                            trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                                Switch(index !in message.contextState.excludedParts, enabled = !busy, onCheckedChange = { included -> perform { service.setContextExclusion(conversationId, message.id, index, !included) } })
                                                ItemActionMenu(buildList {
                                                    add(ItemAction("编辑内容", HugeIcons.Edit03, enabled = !busy) { edit() })
                                                    add(ItemAction("从这里重新生成", HugeIcons.Refresh03, enabled = !busy) { preview(index, false) })
                                                    if (part is UIMessagePart.Tool) {
                                                        add(ItemAction("重新执行工具", HugeIcons.Play, enabled = !busy) { replayPart = index })
                                                        add(ItemAction("从结果继续", HugeIcons.ArrowRight01, enabled = !busy) { preview(index, true) })
                                                    }
                                                })
                                            } },
                                        )
                                    }
                                }
                                Text("编辑保存后保留后续消息；需要独立路径时可创建会话分支。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (message.contextState.toolRuns.isNotEmpty()) item {
                                CardGroup(title = { Text("工具执行记录") }) {
                                    message.contextState.toolRuns.forEach { run -> formItem(headlineContent = { Text(run.tool.toolName) }, supportingContent = { Text(run.status) }, leadingContent = { CardGroupIcon(HugeIcons.Code) }) { JsonTree(JsonInstant.encodeToJsonElement(UIMessagePart.serializer(), run.tool), "执行结果") } }
                                }
                            }
                        }
                    }
                    1 -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { preview(null, false) }, enabled = message != null && !busy) { Text("预览重新生成") }
                                FilledTonalButton(onClick = { preview(null, true) }, enabled = message != null && !busy) { Text("预览继续") }
                            }
                            Spacer(Modifier.height(16.dp))
                            CardGroup(title = { Text("本次携带内容") }) {
                                switchItem(selection.includeReasoning, { selection = selection.copy(includeReasoning = it) }, leadingContent = { CardGroupIcon(HugeIcons.Brain02) }, headlineContent = { Text("历史 Thinking") })
                                switchItem(selection.includeSkills, { selection = selection.copy(includeSkills = it) }, leadingContent = { CardGroupIcon(HugeIcons.Code) }, headlineContent = { Text("Skills") })
                                switchItem(selection.includeToolCalls, { selection = selection.copy(includeToolCalls = it) }, headlineContent = { Text("历史 Tool Calls") })
                                switchItem(selection.includeToolResults, { selection = selection.copy(includeToolResults = it) }, supportingContent = { Text("调用与结果按协议成对携带。") }, headlineContent = { Text("历史 Tool Results") })
                            }
                            Spacer(Modifier.height(16.dp))
                            CardGroup(title = { Text("保留路径") }) {
                                switchItem(createBranch, { createBranch = it }, headlineContent = { Text("创建会话分支后发送") })
                                switchItem(newVariant, { newVariant = it }, headlineContent = { Text("保留为可切换的回复版本") })
                            }
                        }
                        val ordered = if (selection.messageOrder.isEmpty()) conversation.currentMessages else selection.messageOrder.mapNotNull { id -> conversation.currentMessages.find { it.id.toString() == id } }
                        item {
                            CardGroup(title = { Text("消息与顺序") }) {
                                ordered.forEachIndexed { index, selected -> item(
                                    leadingContent = { Checkbox(selected.id.toString() !in selection.excludedMessages, { included -> selection = selection.copy(excludedMessages = if (included) selection.excludedMessages - selected.id.toString() else selection.excludedMessages + selected.id.toString()) }) },
                                    headlineContent = { Text(contextRoleLabel(selected.role)) }, supportingContent = { Text(partPreview(selected.parts.firstOrNull()), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { ItemActionMenu(listOf(-1 to "上移", 1 to "下移").map { (delta, label) -> ItemAction(label, if (delta < 0) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, enabled = index + delta in ordered.indices) {
                                        val ids = ordered.map { it.id.toString() }.toMutableList(); ids.add(index + delta, ids.removeAt(index)); selection = selection.copy(messageOrder = ids)
                                    } }) },
                                ) }
                            }
                        }
                    }
                    2 -> item { ContextInspector(snapshots, messageId, service, onSkill = { name -> onDismiss(); navigator.navigate(me.rerere.rikkahub.Screen.SkillDetail(name)) }, onSource = { source -> perform {
                        val id = Uuid.parse(source.conversationId)
                        val sourceConversation = service.getConversation(id) ?: error("来源会话已删除")
                        val node = sourceConversation.getMessageNodeByMessageId(Uuid.parse(source.messageId)) ?: error("来源是运行时内容或已删除")
                        onDismiss(); navigateToChatPage(navigator, id, nodeId = node.id)
                    } }) }
                    3 -> item { ContextTransfer(conversation, messageId, service, !busy) { ids, target, after, mode -> perform { service.copyContextMessages(conversationId, ids, target, after, mode); feedback = "已复制到目标会话" } } }
                }
            }
        }
    }
    if (choosingMessage) ContextSheet({ choosingMessage = false }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("选择消息", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { choosingMessage = false }) { Icon(HugeIcons.Cancel01, "关闭") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(conversation.currentMessages, key = { it.id }) { selected -> CardGroup { item(onClick = { messageId = selected.id; choosingMessage = false }, headlineContent = { Text(contextRoleLabel(selected.role)) }, supportingContent = { Text(partPreview(selected.parts.firstOrNull()), maxLines = 3, overflow = TextOverflow.Ellipsis) }, leadingContent = { CardGroupIcon(HugeIcons.Message01) }) } }
        }
    }
    editorMessage?.let { original -> MessagePartsEditor(original, onDismiss = { editorMessage = null; appendedPart = null }, initialNewPart = appendedPart,
        loadTools = { service.authorizedContextTools(conversationId) },
        onSave = { edited, indices -> service.saveContextMessage(conversationId, original.id, edited.parts, indices, original) },
    ) }
    insertion?.let { draft -> MessagePartsEditor(draft, onDismiss = { insertion = null }, title = "插入消息", isNew = true, allowRoleChange = true,
        loadTools = { service.authorizedContextTools(conversationId) }, onSave = { edited, _ -> service.insertContextMessages(conversationId, insertionAfter, listOf(edited)); messageId = edited.id },
    ) }
    replayPart?.let { index -> AlertDialog(onDismissRequest = { replayPart = null }, title = { Text("重新执行此工具？") }, text = { Text("使用已保存的参数运行所选工具。工具可能产生外部副作用。") }, confirmButton = { TextButton(onClick = { perform { service.rerunContextTool(conversationId, messageId, index, confirmedSideEffects = true); replayPart = null } }) { Text("执行工具") } }, dismissButton = { TextButton(onClick = { replayPart = null }) { Text("取消") } }) }
    gate?.let { active -> RequestPreviewDialog(active, onDismiss = { active.cancel(); gate = null }, onSend = { gate = null; if (requestConversationId != conversationId) { onDismiss(); navigateToChatPage(navigator, requestConversationId) } }) }
}

private fun partPreview(part: UIMessagePart?): String = when (part) {
    is UIMessagePart.Text -> part.text
    is UIMessagePart.Reasoning -> part.reasoning
    is UIMessagePart.Tool -> "${part.toolName}\n${part.input}\n${part.output.joinToString("\n") { it.contextText() }}"
    is UIMessagePart.Document -> part.fileName
    is UIMessagePart.Image -> part.url
    null -> ""
    else -> part.contextText()
}.take(400)

@Composable
private fun ContextInspector(snapshots: List<ContextRequestEntity>, messageId: Uuid, service: ChatService, onSkill: (String) -> Unit, onSource: (ContextSource) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val options = snapshots.filter { all || it.responseMessageId == messageId.toString() }
    var selectedId by remember(messageId) { mutableStateOf<String?>(null) }
    var compareId by remember { mutableStateOf<String?>(null) }
    val selected = options.find { it.id == selectedId } ?: options.firstOrNull()
    var body by remember { mutableStateOf("") }
    var comparison by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var comparedResponse by remember { mutableStateOf("") }
    LaunchedEffect(selected?.id, selected?.generationStatus) {
        body = selected?.let { service.contextRequestStore.body(it.id) }.orEmpty()
        response = selected?.let { service.contextRequestStore.response(it.runId) }.orEmpty()
    }
    LaunchedEffect(compareId, snapshots) {
        comparison = compareId?.let { service.contextRequestStore.body(it) }.orEmpty()
        comparedResponse = snapshots.find { it.id == compareId }?.let { service.contextRequestStore.response(it.runId) }.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContextToggle("查看本会话全部请求", all) { all = it }
        if (options.isEmpty()) Text("没有已记录请求。升级前的实际请求未知，不能从当前历史倒推出原始请求。")
        else {
            ContextChoice("请求", selected?.let { "${it.createdAt} · ${it.protocol}" }.orEmpty(), options.map { it.id to "${it.createdAt} · ${it.protocol} · ${it.id.take(8)}" }) { selectedId = it }
            selected?.let { record ->
                Text("${record.model}\n${record.endpoint}\n${record.transportStatus} · ${record.generationStatus} · attempts=${record.attempts}", style = MaterialTheme.typography.bodySmall)
                Text("正文 SHA-256：${record.bodySha256}", style = MaterialTheme.typography.labelSmall)
                if (record.rawEdited) Text("发送前已编辑原始正文。来源关联表示构建来源，修改后的字段可能不再与来源一一对应。")
                if (record.redactedPaths != "[]") Text("认证字段已隐藏：${record.redactedPaths}")
                if (body.isNotEmpty()) RequestBodyView(body, readOnly = true, onChange = {})
                Text("本轮归档回复", style = MaterialTheme.typography.titleSmall)
                Text("这是该轮结束时保存的回复或中断片段，同轮多个请求共享；不是服务商原始响应。后续直接编辑不会覆盖它。", style = MaterialTheme.typography.bodySmall)
                if (response.isEmpty()) Text("未记录回复内容。")
                else JsonTree(JsonInstant.parseToJsonElement(response), "归档回复")
                Text("排除记录", style = MaterialTheme.typography.titleSmall)
                JsonTree(JsonInstant.parseToJsonElement(record.exclusions), "未进入构建上下文")
                Text("来源", style = MaterialTheme.typography.titleSmall)
                JsonInstant.decodeFromString<List<ContextSource>>(record.sources).forEach { source ->
                    TextButton(onClick = { onSource(source) }) { Text("${source.description} · ${source.messageId.take(8)}") }
                    source.skillName?.let { name -> TextButton(onClick = { onSkill(name) }) { Text("Skill 来源：$name") } }
                }
                Text("Provider 未出现在正文中的内部状态为未知。结构视图展示实际正文，不能把历史中已保存的 Thinking 当作已发送。", style = MaterialTheme.typography.bodySmall)
                ContextChoice("对比另一次请求", compareId?.take(8) ?: "选择请求", snapshots.filter { it.id != record.id }.map { it.id to "${it.createdAt} · ${it.model} · ${it.id.take(8)}" }) { compareId = it }
                if (comparison.isNotEmpty() && body.isNotEmpty()) diffRequests(comparison, body).forEach { difference ->
                    Text(difference.path, style = MaterialTheme.typography.titleSmall)
                    difference.before?.let { JsonTree(it, "之前") }
                    difference.after?.let { JsonTree(it, "之后") }
                }
                if (comparedResponse.isNotEmpty() && response.isNotEmpty()) {
                    Text("两轮归档回复差异", style = MaterialTheme.typography.titleSmall)
                    diffRequests(comparedResponse, response).forEach { difference ->
                        Text(difference.path, style = MaterialTheme.typography.titleSmall)
                        difference.before?.let { JsonTree(it, "之前") }
                        difference.after?.let { JsonTree(it, "之后") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextTransfer(source: Conversation, currentId: Uuid, service: ChatService, enabled: Boolean, onCopy: (Set<Uuid>, Uuid, Uuid?, String) -> Unit) {
    var targets by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var targetId by remember { mutableStateOf<Uuid?>(null) }
    var target by remember { mutableStateOf<Conversation?>(null) }
    var selected by remember { mutableStateOf(setOf(currentId)) }
    var after by remember { mutableStateOf<Uuid?>(null) }
    var mode by remember { mutableStateOf("original") }
    LaunchedEffect(Unit) { targets = service.contextConversations().filter { it.id != source.id } }
    LaunchedEffect(targetId) { target = targetId?.let { service.getConversation(it) }; after = target?.currentMessages?.lastOrNull()?.id }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("选择连续或非连续消息，再指定目标与插入位置。复制仅保存，不请求模型。", style = MaterialTheme.typography.bodySmall)
        CardGroup(title = { Text("选择消息") }) { source.currentMessages.forEach { message ->
            item(onClick = { selected = if (message.id in selected) selected - message.id else selected + message.id },
                leadingContent = { Checkbox(checked = message.id in selected, onCheckedChange = { selected = if (it) selected + message.id else selected - message.id }) },
                headlineContent = { Text(contextRoleLabel(message.role)) },
                supportingContent = { Text(partPreview(message.parts.firstOrNull()), maxLines = 3, overflow = TextOverflow.Ellipsis) },
            )
        } }
        ContextChoice("目标会话", targets.find { it.id == targetId }?.title ?: "选择会话", targets.map { it.id to it.title.ifEmpty { it.id.toString().take(8) } }) { targetId = it }
        ContextChoice("插入位置", after?.let { "消息 ${it.toString().take(8)} 之后" } ?: "开头", listOf(null to "开头") + target?.currentMessages.orEmpty().map { it.id to "${it.role}: ${it.toText().take(50)} 之后" }) { after = it }
        ContextChoice("复制方式", mapOf("original" to "原样复制", "text" to "转普通文本", "merged" to "合并为一条用户消息").getValue(mode), listOf("original" to "原样复制", "text" to "转普通文本", "merged" to "合并为一条用户消息")) { mode = it }
        Text("原样保留角色和协议内容。不同 Provider 的签名或工具结构可能不兼容；不兼容时请显式转为文本，并用请求预览检查实际发送内容。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { targetId?.let { onCopy(selected, it, after, mode) } }, enabled = enabled && targetId != null && selected.isNotEmpty()) { Text("复制到指定位置") }
    }
}

@Composable
fun RequestPreviewDialog(gate: RequestReviewGate, onDismiss: () -> Unit, onSend: () -> Unit) {
    val preview by gate.preview.collectAsState()
    val failure by gate.failure.collectAsState()
    var body by remember(preview) { mutableStateOf(preview?.body.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    ContextSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "取消预览") }
            Text(if (preview?.purpose == "ocr") "附件 OCR 请求预览" else "请求预览", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(enabled = preview != null, onClick = {
                try {
                    val isOcr = preview?.purpose == "ocr"
                    gate.confirm(body)
                    if (!isOcr) onSend()
                } catch (e: Exception) { error = e.message }
            }) { Text(if (preview?.purpose == "ocr") "发送 OCR 请求" else "确认发送") }
        }
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (preview == null) { if (failure == null) CircularProgressIndicator() else Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error) }
                else {
                    CardGroup { item(headlineContent = { Text(preview!!.protocol) }, supportingContent = { Text(preview!!.endpoint) }, leadingContent = { CardGroupIcon(HugeIcons.Code) }) }
                    if (preview!!.notices.isNotEmpty()) CardGroup(title = { Text("思考携带说明") }) {
                        preview!!.notices.forEach { notice -> item(headlineContent = { Text(notice) }, leadingContent = { CardGroupIcon(HugeIcons.Brain02) }) }
                    }
                    if (preview!!.purpose == "ocr") Text("此请求把附件发给已配置的 OCR 模型。完成后会继续展示聊天模型的最终请求。", style = MaterialTheme.typography.bodySmall)
                    Text("确认前不会发送此请求。下方正文直接用于本次传输。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RequestBodyView(body, readOnly = false, onChange = { body = it })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
    }
    DisposableEffect(gate) { onDispose { gate.cancel() } }
}

/** Shared renderer for an unsent preview and the recorded wire body. */
@Composable
private fun RequestBodyView(body: String, readOnly: Boolean, onChange: (String) -> Unit) {
    var raw by remember { mutableStateOf(!readOnly) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleButton(raw, { raw = true }) { Text(if (readOnly) "原始正文" else "编辑正文") }
        ToggleButton(!raw, { raw = false }) { Text("结构视图") }
    }
    if (raw) TextField(body, onChange, readOnly = readOnly, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, minLines = 12, maxLines = 26, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    else runCatching { JsonInstant.parseToJsonElement(body) }.getOrNull()?.let { JsonTree(it, "Request") } ?: Text("JSON 格式有误")
}

@Composable
private fun JsonTree(value: JsonElement, label: String) {
    var expanded by remember(label, value) { mutableStateOf(false) }
    val children = when (value) {
        is JsonObject -> value.entries.map { it.key to it.value }
        is JsonArray -> value.mapIndexed { index, element -> index.toString() to element }
        else -> emptyList()
    }
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        CardGroup { item(onClick = { expanded = !expanded }, headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) }, supportingContent = { Text(if (children.isNotEmpty()) "${children.size} 项" else value.toString().take(160), maxLines = 3, overflow = TextOverflow.Ellipsis) }, trailingContent = { Icon(if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01, null) }) }
        if (expanded) {
            if (children.isEmpty()) SelectionContainer { Text(value.toString(), style = MaterialTheme.typography.bodySmall) }
            else children.forEach { (key, child) -> JsonTree(child, key) }
        }
    }
}

@Composable
private fun ContextToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    CardGroup { switchItem(checked, onChange, enabled = enabled, headlineContent = { Text(label) }) }
}

@Composable
private fun <T> ContextChoice(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        CardGroup { item(onClick = { expanded = true }, overlineContent = { Text(label) }, headlineContent = { Text(value, maxLines = 2, overflow = TextOverflow.Ellipsis) }, trailingContent = { Icon(HugeIcons.ArrowDown01, null) }) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

private fun partLabel(part: UIMessagePart): String = when (part) {
    is UIMessagePart.Text -> "Message"
    is UIMessagePart.Reasoning -> "Thinking"
    is UIMessagePart.Tool -> if (part.toolName == "use_skill") "Skill / Tool Call & Result" else "Tool Call & Result"
    is UIMessagePart.ServerTool -> "Provider Server Tool"
    else -> part.javaClass.simpleName
}
