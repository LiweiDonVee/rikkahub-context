package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.ai.context.contextText
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.context.MessagePartsEditor
import me.rerere.rikkahub.ui.components.context.contextPartLabel
import me.rerere.rikkahub.ui.components.context.contextRoleLabel
import me.rerere.rikkahub.ui.components.context.contextPartPreview
import me.rerere.rikkahub.ui.components.ui.*
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Edits the original presetMessages field; conversation initialization stays owned by ChatService. */
@Composable
fun AssistantPresetMessages(assistant: Assistant, settings: Settings, onUpdate: (Assistant) -> Unit,
    onSaveMessages: suspend (List<UIMessage>, List<UIMessage>) -> Unit = { _, messages -> onUpdate(assistant.copy(presetMessages = messages)) },
) {
    var editing by remember(assistant.id) { mutableStateOf<UIMessage?>(null) }
    var creating by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<UIMessage>?>(null) }
    val tools: ChatToolFactory = koinInject()
    fun update(messages: List<UIMessage>) { undo = assistant.presetMessages; onUpdate(assistant.copy(presetMessages = messages)) }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.assistant_page_preset_messages), description = stringResource(R.string.assistant_page_preset_messages_desc), action = {
            SectionAddButton(onClick = {
                val next = if (assistant.presetMessages.lastOrNull()?.role == MessageRole.USER) MessageRole.ASSISTANT else MessageRole.USER
                creating = true
                editing = UIMessage(role = next, parts = listOf(UIMessagePart.Text("")))
            })
        })
        CardGroup {
            if (assistant.presetMessages.isEmpty()) item(headlineContent = { Text("还没有预填充消息") }, supportingContent = { Text("新会话会沿用这里配置的消息和顺序。") }, leadingContent = { CardGroupIcon(HugeIcons.Message01) })
            assistant.presetMessages.forEachIndexed { index, message ->
                item(onClick = { creating = false; editing = message },
                    leadingContent = { CardGroupIcon(HugeIcons.Message01) },
                    overlineContent = { Text("${index + 1} · ${contextRoleLabel(message.role)}") },
                    headlineContent = { Text(message.parts.joinToString(" · ") { contextPartLabel(it) }.ifBlank { "空消息" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(message.parts.joinToString("\n") { contextPartPreview(it) }.take(200).ifBlank { "点按添加正文或思考" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { ItemActionMenu(listOf(
                        ItemAction("编辑内容", HugeIcons.Edit03) { creating = false; editing = message },
                        ItemAction("上移消息", HugeIcons.ArrowUp01, enabled = index > 0) { update(assistant.presetMessages.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                        ItemAction("下移消息", HugeIcons.ArrowDown01, enabled = index < assistant.presetMessages.lastIndex) { update(assistant.presetMessages.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                        ItemAction("删除消息", HugeIcons.Delete01, destructive = true) { update(assistant.presetMessages.filter { it.id != message.id }) },
                    )) },
                )
            }
        }
        if (undo != null) TextButton(onClick = { onUpdate(assistant.copy(presetMessages = undo!!)); undo = null }) { Text("撤销调整") }
    }
    editing?.let { original ->
        MessagePartsEditor(message = original, title = if (creating) "添加预填充消息" else "编辑预填充消息", allowRoleChange = true, isNew = creating,
            roleOptions = listOf(MessageRole.USER, MessageRole.ASSISTANT),
            onDismiss = { editing = null },
            loadTools = {
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: error("请先为助手选择模型，再添加工具调用")
                tools.createTools(settings, assistant, model, Uuid.random())
            },
            onSave = { edited, _ ->
                // Recovery belongs to history editing; do not make template authoring history a new chat's original.
                val saved = edited.copy(contextState = edited.contextState.copy(originalParts = original.contextState.originalParts))
                val next = if (creating) assistant.presetMessages + saved
                else {
                    check(assistant.presetMessages.find { it.id == original.id } == original) { "预填充已变化，请重新打开编辑器" }
                    assistant.presetMessages.map { if (it.id == original.id) saved else it }
                }
                onSaveMessages(assistant.presetMessages, next)
                undo = assistant.presetMessages
            },
        )
    }
}
