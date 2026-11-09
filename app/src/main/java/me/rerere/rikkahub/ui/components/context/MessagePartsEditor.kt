package me.rerere.rikkahub.ui.components.context

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.context.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ui.components.Select
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** One draft and one Save for history, inserted messages, and assistant presets. */
@Composable
fun MessagePartsEditor(
    message: UIMessage,
    onDismiss: () -> Unit,
    onSave: suspend (UIMessage, List<Int?>) -> Unit,
    title: String = "编辑消息",
    allowRoleChange: Boolean = false,
    isNew: Boolean = false,
    loadTools: suspend () -> List<Tool> = { emptyList() },
    initialNewPart: UIMessagePart? = null,
    roleOptions: List<MessageRole> = listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.SYSTEM),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val files: FilesManager = koinInject()
    var inputs by remember(message.id) { mutableStateOf((message.contextDraft() + listOfNotNull(initialNewPart?.let { ContextPartDraft(it) })).map(::PartInput)) }
    var undo by remember { mutableStateOf<List<PartInput>?>(null) }
    var role by remember { mutableStateOf(message.role) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun change(next: List<PartInput>) { undo = inputs; inputs = next; error = null }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val added = uris.map { uri ->
            val mime = context.contentResolver.getType(uri).orEmpty()
            val part = when {
                mime.startsWith("image/") -> UIMessagePart.Image(uri.toString())
                mime.startsWith("audio/") -> UIMessagePart.Audio(uri.toString())
                mime.startsWith("video/") -> UIMessagePart.Video(uri.toString())
                else -> UIMessagePart.Document(uri.toString(), uri.lastPathSegment ?: "附件", mime.ifBlank { "application/octet-stream" })
            }
            PartInput(ContextPartDraft(part), pickedFile = true)
        }
        if (added.isNotEmpty()) change(inputs + added)
    }
    ContextSheet(onDismiss = { if (!saving) onDismiss() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, enabled = !saving) { Icon(HugeIcons.Cancel01, "取消编辑") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            FilledTonalButton(enabled = !saving, onClick = {
                scope.launch {
                    saving = true
                    error = null
                    val created = mutableListOf<android.net.Uri>()
                    try {
                        var draft = inputs.map { it.compile() }
                        val changedTools = draft.map { it.part }.filterIsInstance<UIMessagePart.Tool>().filter { tool ->
                            message.getTools().none { it.toolCallId == tool.toolCallId && it.toolName == tool.toolName && it.input == tool.input && !(it.isExecuted && !tool.isExecuted) }
                        }
                        if (changedTools.isNotEmpty()) {
                            draft = draft.map { entry ->
                                val call = entry.part as? UIMessagePart.Tool
                                if (call != null && changedTools.any { it.toolCallId == call.toolCallId }) entry.copy(part = call.copy(approvalState = ToolApprovalState.Pending)) else entry
                            }
                        }
                        draft = draft.mapIndexed { index, item ->
                            if (!inputs[index].pickedFile) item else {
                                val uri = attachmentUrl(item.part).toUri()
                                val owned = withContext(Dispatchers.IO) { files.createChatFilesByContents(listOf(uri)).single() }
                                created += owned
                                item.copy(part = item.part.withAttachmentUrl(owned.toString()))
                            }
                        }
                        val edited = message.saveContextDraft(draft).copy(role = role).let {
                            if (isNew) it.copy(contextState = it.contextState.copy(originalParts = null)) else it
                        }.let { if (changedTools.isNotEmpty()) it.copy(contextState = it.contextState.copy(manualToolExecution = true)) else it }
                        withContext(NonCancellable) { onSave(edited, draft.map { it.originalIndex }) }
                        created.clear()
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "保存失败" }
                    finally {
                        if (created.isNotEmpty()) withContext(Dispatchers.IO) { files.deleteChatFiles(created) }
                        saving = false
                    }
                }
            }) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else { Icon(HugeIcons.Tick01, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                if (allowRoleChange) CardGroup {
                    formItem(headlineContent = { Text("消息角色") }, leadingContent = { CardGroupIcon(HugeIcons.Message01) }) {
                        val roles = (roleOptions + message.role).distinct()
                        Select(roles.map(::contextRoleLabel), contextRoleLabel(role), { selected -> role = roles.first { contextRoleLabel(it) == selected } }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (error != null) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                    Text(error.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            itemsIndexed(inputs, key = { _, input -> input.draft.key }) { index, input ->
                CardGroup(Modifier.animateContentSize()) {
                    formItem(
                        headlineContent = { Text(contextPartLabel(input.draft.part)) },
                        supportingContent = { Text("内容 ${index + 1}", style = MaterialTheme.typography.labelMedium) },
                        leadingContent = { CardGroupIcon(contextPartIcon(input.draft.part)) },
                        trailingContent = {
                            ItemActionMenu(listOf(
                                ItemAction("上移", HugeIcons.ArrowUp01, enabled = index > 0 && !saving) {
                                    change(inputs.toMutableList().apply { add(index - 1, removeAt(index)) })
                                },
                                ItemAction("下移", HugeIcons.ArrowDown01, enabled = index < inputs.lastIndex && !saving) {
                                    change(inputs.toMutableList().apply { add(index + 1, removeAt(index)) })
                                },
                                ItemAction("转为正文", HugeIcons.Text, enabled = (input.draft.part !is UIMessagePart.Text || hasOpaqueState(input.draft.part)) && !saving) {
                                    val text = runCatching { input.compile().part.contextText() }.getOrElse { input.content.text.toString() }
                                    change(inputs.toMutableList().apply { set(index, PartInput(input.draft.copy(part = UIMessagePart.Text(text)))) })
                                },
                                ItemAction("删除内容", HugeIcons.Delete01, destructive = true, enabled = !saving) { change(inputs.filterIndexed { i, _ -> i != index }) },
                            ))
                        },
                    ) {
                        if (input.draft.part is UIMessagePart.Tool) {
                            AuthorizedToolPicker(input.name, enabled = !saving, loadTools = loadTools, onError = { error = it })
                            TextArea(input.name, label = "工具名称", minLines = 1, maxLines = 1, enabled = !saving)
                            TextArea(input.content, label = "参数 JSON", minLines = 3, maxLines = 8, enabled = !saving)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("已有工具结果", Modifier.weight(1f))
                                Switch(input.hasResult, { input.hasResult = it }, enabled = !saving)
                            }
                            if (input.hasResult) TextArea(input.output, label = if (input.jsonOutput) "结果内容部件 JSON" else "工具结果", minLines = 3, maxLines = 10, enabled = !saving)
                        } else {
                            TextArea(input.content, label = when (input.draft.part) {
                                is UIMessagePart.Text -> "正文"
                                is UIMessagePart.Reasoning -> "思考内容"
                                is UIMessagePart.Image -> "图片地址"
                                is UIMessagePart.Document -> "文档地址"
                                is UIMessagePart.Audio -> "音频地址"
                                is UIMessagePart.Video -> "视频地址"
                                else -> "内容部件 JSON"
                            }, minLines = 4, maxLines = 12, enabled = !saving)
                        }
                        if (input.draft.part is UIMessagePart.Reasoning) Text("这是可编辑的上下文文本。实际携带方式由协议决定，可在发送前预览。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                if (inputs.isEmpty()) CardGroup { item(headlineContent = { Text("消息还没有内容") }, supportingContent = { Text("添加正文、思考、工具调用或附件。") }, leadingContent = { CardGroupIcon(HugeIcons.Message01) }) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        FilledTonalButton(onClick = { adding = true }, enabled = !saving) {
                            Icon(HugeIcons.Add01, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("添加内容")
                        }
                        DropdownMenu(adding, { adding = false }) {
                            DropdownMenuItem(text = { Text("正文") }, leadingIcon = { Icon(HugeIcons.Text, null) }, onClick = { adding = false; change(inputs + PartInput(ContextPartDraft(UIMessagePart.Text("")))) })
                            DropdownMenuItem(text = { Text("思考") }, leadingIcon = { Icon(HugeIcons.Brain02, null) }, onClick = { adding = false; change(inputs + PartInput(ContextPartDraft(UIMessagePart.Reasoning("").withUserReasoning("")))) })
                            DropdownMenuItem(text = { Text("工具调用") }, leadingIcon = { Icon(HugeIcons.Code, null) }, onClick = {
                                adding = false
                                change(inputs + PartInput(ContextPartDraft(UIMessagePart.Tool(Uuid.random().toString(), "", "{}"))))
                            })
                            DropdownMenuItem(text = { Text("附件") }, leadingIcon = { Icon(HugeIcons.Attachment01, null) }, onClick = { adding = false; picker.launch(arrayOf("*/*")) })
                        }
                    }
                    if (undo != null) TextButton(onClick = { inputs = undo!!; undo = null }, enabled = !saving) { Text("撤销调整") }
                }
                Text("保存只更新内容，不请求模型或运行工具。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AuthorizedToolPicker(name: TextFieldState, enabled: Boolean, loadTools: suspend () -> List<Tool>, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var available by remember { mutableStateOf<List<Tool>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled && !loading, onClick = { scope.launch {
            loading = true
            try {
                available = loadTools()
                if (available.isEmpty()) onError("当前助手没有可用的授权工具") else expanded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "无法读取工具") }
            finally { loading = false }
        } }) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(HugeIcons.Code, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("选择已授权工具")
        }
        DropdownMenu(expanded, { expanded = false }) {
            available.forEach { tool -> DropdownMenuItem(
                text = { Column { Text(tool.name); Text(tool.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) } },
                onClick = { name.setTextAndPlaceCursorAtEnd(tool.name); expanded = false },
            ) }
        }
    }
}

@Composable
internal fun ContextSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        sheetGesturesEnabled = false, dragHandle = null,
    ) { Column(Modifier.fillMaxSize(), content = content) }
}

private class PartInput(val draft: ContextPartDraft, val pickedFile: Boolean = false) {
    val content = TextFieldState(when (val part = draft.part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Reasoning -> part.reasoning
        is UIMessagePart.Tool -> part.input
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Document -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Video -> part.url
        else -> JsonInstant.encodeToString(UIMessagePart.serializer(), part)
    })
    val name = TextFieldState((draft.part as? UIMessagePart.Tool)?.toolName.orEmpty())
    private val tool = draft.part as? UIMessagePart.Tool
    val jsonOutput = tool?.output?.let { it.isNotEmpty() && (it.size != 1 || it.firstOrNull() !is UIMessagePart.Text) } ?: false
    val output = TextFieldState(if (jsonOutput) JsonInstant.encodeToString(tool?.output.orEmpty()) else (tool?.output?.firstOrNull() as? UIMessagePart.Text)?.text.orEmpty())
    var hasResult by mutableStateOf(tool?.isExecuted == true)
    fun compile(): ContextPartDraft {
        val text = content.text.toString()
        val updated = when (val part = draft.part) {
            is UIMessagePart.Text -> part.copy(text = text)
            is UIMessagePart.Image -> part.copy(url = text)
            is UIMessagePart.Document -> part.copy(url = text)
            is UIMessagePart.Audio -> part.copy(url = text)
            is UIMessagePart.Video -> part.copy(url = text)
            is UIMessagePart.Reasoning -> if (text == part.reasoning) part else part.withUserReasoning(text)
            is UIMessagePart.Tool -> {
                require(JsonInstant.parseToJsonElement(text) is JsonObject) { "工具参数必须为 JSON 对象" }
                val result = if (!hasResult) emptyList() else if (jsonOutput) JsonInstant.decodeFromString<List<UIMessagePart>>(output.text.toString())
                else listOf((part.output.firstOrNull() as? UIMessagePart.Text)?.copy(text = output.text.toString()) ?: UIMessagePart.Text(output.text.toString()))
                part.copy(toolName = name.text.toString(), input = text, output = result)
            }
            else -> JsonInstant.decodeFromString(UIMessagePart.serializer(), text)
        }
        return draft.copy(part = updated)
    }
}

private fun attachmentUrl(part: UIMessagePart) = when (part) {
    is UIMessagePart.Image -> part.url
    is UIMessagePart.Document -> part.url
    is UIMessagePart.Audio -> part.url
    is UIMessagePart.Video -> part.url
    else -> error("Not an attachment")
}

private fun UIMessagePart.withAttachmentUrl(url: String): UIMessagePart = when (this) {
    is UIMessagePart.Image -> copy(url = url)
    is UIMessagePart.Document -> copy(url = url)
    is UIMessagePart.Audio -> copy(url = url)
    is UIMessagePart.Video -> copy(url = url)
    else -> this
}

internal fun contextRoleLabel(role: MessageRole) = when (role) { MessageRole.USER -> "用户"; MessageRole.ASSISTANT -> "助手"; MessageRole.SYSTEM -> "系统"; else -> role.name }
internal fun contextPartLabel(part: UIMessagePart) = when (part) {
    is UIMessagePart.Text -> "正文"
    is UIMessagePart.Reasoning -> "思考"
    is UIMessagePart.Tool -> if (part.toolName == "use_skill") "Skill" else "工具调用"
    is UIMessagePart.Image -> "图片"
    is UIMessagePart.Audio -> "音频"
    is UIMessagePart.Video -> "视频"
    is UIMessagePart.Document -> "文档"
    else -> "协议内容"
}
internal fun contextPartIcon(part: UIMessagePart) = when (part) {
    is UIMessagePart.Reasoning -> HugeIcons.Brain02
    is UIMessagePart.Tool -> HugeIcons.Code
    is UIMessagePart.Text -> HugeIcons.Text
    else -> HugeIcons.Attachment01
}

internal fun contextPartPreview(part: UIMessagePart): String = when (part) {
    is UIMessagePart.Text -> part.text
    is UIMessagePart.Reasoning -> part.reasoning
    is UIMessagePart.Tool -> "${part.toolName}\n${part.input}\n${part.output.joinToString("\n") { contextPartPreview(it) }}"
    is UIMessagePart.Document -> part.fileName
    is UIMessagePart.Image -> part.url
    is UIMessagePart.Audio -> part.url
    is UIMessagePart.Video -> part.url
    else -> contextPartLabel(part)
}

@Composable
fun MessagePartsSummary(message: UIMessage, onEdit: () -> Unit) {
    CardGroup {
        item(onClick = onEdit, leadingContent = { CardGroupIcon(HugeIcons.Edit03) },
            headlineContent = { Text(message.parts.joinToString(" · ") { contextPartLabel(it) }.ifEmpty { "添加消息内容" }) },
            supportingContent = { Text(message.parts.joinToString("\n") { contextPartPreview(it) }.take(240).ifBlank { "点按编辑，支持正文、思考和工具结果" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
        )
    }
}
