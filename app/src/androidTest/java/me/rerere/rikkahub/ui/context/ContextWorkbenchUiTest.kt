package me.rerere.rikkahub.ui.context

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.data.model.ContextInsertionPosition
import me.rerere.rikkahub.ui.components.context.ContextConversationPicker
import me.rerere.rikkahub.ui.components.context.ContextInsertionSheet
import me.rerere.rikkahub.ui.components.context.ContextTransferPanel
import me.rerere.rikkahub.ui.components.context.ContextWorkbench
import me.rerere.rikkahub.ui.components.context.MessagePartsEditor
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantPresetMessages
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.rememberToasterState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ContextWorkbenchUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val conversationId = Uuid.random()
    private val service get() = GlobalContext.get().get<ChatService>()
    private val settings get() = GlobalContext.get().get<SettingsStore>()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var createdConversation = false
    private var temporaryAssistant: Uuid? = null
    private var previousAssistant: Uuid? = null
    private val extraConversations = mutableListOf<Uuid>()
    private val extraAssistants = mutableListOf<Uuid>()

    @After fun cleanup() = runBlocking {
        scenario?.close()
        extraConversations.forEach { service.removeConversationReference(it); service.deleteConversation(it) }
        if (extraAssistants.isNotEmpty()) settings.update { it.copy(assistants = it.assistants.filterNot { a -> a.id in extraAssistants }) }
        if (createdConversation) { service.removeConversationReference(conversationId); service.deleteConversation(conversationId) }
        temporaryAssistant?.let { id -> settings.update { it.copy(assistants = it.assistants.filterNot { a -> a.id == id }, assistantId = previousAssistant ?: it.assistantId) } }
        Unit
    }
    private suspend fun persist(message: UIMessage) = persist(listOf(message))
    private suspend fun persist(messages: List<UIMessage>) {
        GlobalContext.get().get<ConversationRepository>().insertConversation(Conversation.ofId(conversationId, messages = messages.map { MessageNode.of(it) }).copy(title = "Context UI fixture"))
        service.addConversationReference(conversationId); service.initializeConversation(conversationId); createdConversation = true
    }
    private fun show(content: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario!!.onActivity { activity -> activity.setContent {
            val state by settings.settingsFlow.collectAsState()
            CompositionLocalProvider(LocalSettings provides state, LocalToaster provides rememberToasterState(), LocalNavController provides Navigator(mutableListOf(Screen.Chat(conversationId.toString())))) {
                RikkahubTheme(ColorMode.LIGHT) { Surface(Modifier.fillMaxSize(), color = CustomColors.topBarColors.containerColor) { content() } }
            }
        } }
    }
    @Test fun textOnlyHistoryCanGainItsFirstThoughtWithoutDispatchAndUseNativeWorkbenchTabs() = runBlocking {
        settings.awaitLoaded()
        val message = UIMessage.assistant("A reply that originally had no thinking")
        persist(message)
        show { ContextWorkbench(conversationId, message.id, onDismiss = {}) }
        waitFor { findText("上下文工作台") != null }
        screenshot("context-v2-workbench.png")
        click("添加内容"); click("思考")
        waitFor { findText("编辑消息") != null }
        setText("", "New authored thinking\n\nExact newlines")
        screenshot("context-v2-history-editor.png")
        click("保存")
        waitFor { service.getConversationFlow(conversationId).value.currentMessages.single().parts.size == 2 }
        val edited = service.getConversation(conversationId)!!.currentMessages.single()
        assertEquals(message.id, edited.id)
        assertEquals(message.parts.single(), edited.parts[0])
        assertEquals("New authored thinking\n\nExact newlines", (edited.parts[1] as UIMessagePart.Reasoning).reasoning)
        assertNull(service.getGenerationJobStateFlow(conversationId).first())
        assertTrue(service.contextRequestStore.observe(conversationId).first().isEmpty())
        waitFor { findText("编辑消息") == null }
        screenshot("context-v2-workbench-edited.png")
        click("请求记录")
        waitFor { findText("没有已记录请求。", substring = true) != null }
        screenshot("context-v2-records.png")
        click("本次请求")
        waitFor { findText("历史 Thinking") != null }
        screenshot("context-v2-request-controls.png")
    }
    @Test fun assistantPresetEditorAddsThinkingAndLaterBodyEditingKeepsItForNewConversations() = runBlocking {
        previousAssistant = settings.awaitLoaded().assistantId
        val original = UIMessage.assistant("Original preset greeting")
        val assistant = Assistant(name = "Preset UI fixture", presetMessages = listOf(original))
        temporaryAssistant = assistant.id
        settings.update { it.copy(assistants = it.assistants + assistant, assistantId = assistant.id) }
        show {
            val state by settings.settingsFlow.collectAsState()
            val current = state.assistants.first { it.id == assistant.id }
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                AssistantPresetMessages(current, state, onUpdate = { updated -> scope.launch { settings.update { it.copy(assistants = it.assistants.map { a -> if (a.id == updated.id) updated else a }) } } },
                    onSaveMessages = { expected, messages -> settings.update { stored ->
                        check(stored.assistants.first { it.id == assistant.id }.presetMessages == expected)
                        stored.copy(assistants = stored.assistants.map { if (it.id == assistant.id) it.copy(presetMessages = messages) else it })
                    } },
                )
            }
        }
        click("Original preset greeting")
        waitFor { findText("编辑预填充消息") != null }
        click("添加内容"); click("思考")
        setText("", "Preset authored thought\nTwo lines")
        screenshot("context-v2-preset-editor.png")
        click("保存")
        waitFor { settings.settingsFlow.value.assistants.first { it.id == assistant.id }.presetMessages.single().parts.size == 2 }
        waitFor { findText("编辑预填充消息") == null }
        click("Original preset greeting", substring = true)
        setText("Original preset greeting", "Edited preset greeting")
        click("保存")
        waitFor { settings.settingsFlow.value.assistants.first { it.id == assistant.id }.presetMessages.single().parts.filterIsInstance<UIMessagePart.Text>().single().text == "Edited preset greeting" }
        val saved = settings.settingsFlow.value.assistants.first { it.id == assistant.id }.presetMessages.single()
        assertEquals(original.id, saved.id); assertEquals(original.createdAt, saved.createdAt); assertEquals(original.role, saved.role)
        assertNull(saved.contextState.originalParts)
        assertEquals("Preset authored thought\nTwo lines", (saved.parts[1] as UIMessagePart.Reasoning).reasoning)
        service.addConversationReference(conversationId); service.initializeConversation(conversationId); createdConversation = true
        assertEquals(listOf(saved), service.getConversation(conversationId)!!.currentMessages)
        assertNull(service.getGenerationJobStateFlow(conversationId).first())
        assertTrue(service.contextRequestStore.observe(conversationId).first().isEmpty())
        screenshot("context-v2-presets.png")
    }
    @Test fun aNewInsertedMessageCanContainOnlyThinking() = runBlocking {
        settings.awaitLoaded()
        val anchor = UIMessage.user("Existing neighbor")
        persist(anchor)
        val draft = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        var finished by mutableStateOf(false)
        show { if (!finished) MessagePartsEditor(draft, title = "插入消息", isNew = true, onDismiss = { finished = true }, onSave = { edited, _ -> service.insertContextMessages(conversationId, anchor.id, listOf(edited)) }) }
        click("添加内容"); click("思考"); setText("", "A thinking-only inserted assistant"); click("保存")
        waitFor { service.getConversationFlow(conversationId).value.currentMessages.size == 2 }
        assertEquals(anchor, service.getConversation(conversationId)!!.currentMessages[0])
        assertTrue(service.getConversation(conversationId)!!.currentMessages[1].parts.single() is UIMessagePart.Reasoning)
        assertTrue(service.contextRequestStore.observe(conversationId).first().isEmpty())
    }
    private suspend fun extraConversation(title: String, assistantId: Uuid, messages: List<UIMessage>, created: Long = 1): Conversation {
        val conversation = Conversation.ofId(Uuid.random(), assistantId = assistantId, messages = messages.map { MessageNode.of(it) })
            .copy(title = title, createAt = java.time.Instant.ofEpochMilli(created), updateAt = java.time.Instant.ofEpochMilli(100 - created))
        GlobalContext.get().get<ConversationRepository>().insertConversation(conversation)
        extraConversations += conversation.id
        return conversation
    }

    @Test fun conversationBrowserSearchesAssistantsTitlesAndSortsWithoutChangingTheActiveAssistant() = runBlocking {
        val active = settings.awaitLoaded().assistantId
        val alpha = Assistant(name = "Transfer Alpha")
        val beta = Assistant(name = "Transfer Beta")
        extraAssistants += listOf(alpha.id, beta.id)
        settings.update { it.copy(assistants = it.assistants + alpha + beta) }
        repeat(45) { extraConversation("Transfer paged ${it.toString().padStart(3, '0')}", alpha.id, emptyList(), it.toLong()) }
        val older = extraConversation("Transfer lookup Beta older", beta.id, emptyList(), 1)
        extraConversation("Transfer lookup Beta newer", beta.id, emptyList(), 2)
        var chosen by mutableStateOf<Conversation?>(null)
        show { if (chosen == null) ContextConversationPicker("选择目标会话", conversationId, onDismiss = {}, onSelect = { chosen = it }) }
        click("全部助手"); waitFor { findText("筛选助手") != null }; setText("", "Beta"); click("Transfer Beta"); waitFor { findText("筛选助手") == null }
        waitFor { findText("Transfer lookup Beta older") != null }
        click("最近更新"); click("最早创建")
        waitFor { findText("Transfer lookup Beta newer") != null }
        val before = android.graphics.Rect(); val after = android.graphics.Rect()
        findText("Transfer lookup Beta older")!!.getBoundsInScreen(before)
        findText("Transfer lookup Beta newer")!!.getBoundsInScreen(after)
        assertTrue(before.top < after.top)
        setText("", "Beta older")
        waitFor { findText("Transfer lookup Beta newer") == null }
        screenshot("context-v4-conversation-picker.png")
        click("Transfer lookup Beta older")
        waitFor { chosen != null }
        assertEquals(older.id, chosen!!.id)
        assertEquals(active, settings.settingsFlow.value.assistantId)
    }

    @Test fun importingFromTheDestinationKeepsItsMiddleAnchorWhileSearchingAndSelectingNoncontiguousMessages() = runBlocking {
        settings.awaitLoaded()
        val neighbors = listOf("Target first", "Target middle", "Target last").map { UIMessage.user(it) }
        persist(neighbors)
        val target = service.getConversation(conversationId)!!
        val assistant = Assistant(name = "Import agent")
        extraAssistants += assistant.id
        settings.update { it.copy(assistants = it.assistants + assistant) }
        val source = extraConversation("Import source fixture", assistant.id, listOf(UIMessage.user("Import first"), UIMessage.assistant("Skip this reply"), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("Import third")))))
        var inserted by mutableStateOf<List<Uuid>>(emptyList())
        show { if (inserted.isEmpty()) ContextInsertionSheet(target, ContextInsertionPosition.Before(target.messageNodes[1].id), onDismiss = {}, onInserted = { inserted = it }) }
        click("从其他会话导入"); click("查找来源会话")
        setText("", "Import source"); click("Import source fixture")
        waitFor { findText("选择来源消息") != null }
        setText("", "Import")
        click("Import first"); click("Import third", substring = true); click("使用 2 条")
        waitFor { findText("第 2 条", substring = true) != null }
        screenshot("context-v4-import-anchor.png")
        click("导入到此位置")
        waitFor { inserted.size == 2 }
        val saved = service.getConversation(conversationId)!!
        assertEquals(listOf("Target first", "Import first", "", "Target middle", "Target last"), saved.currentMessages.map { it.toText() })
        assertEquals("Import third", (saved.currentMessages[2].parts.single() as UIMessagePart.Reasoning).reasoning)
        assertEquals(source.id.toString(), saved.currentMessages[1].contextState.sources.single().conversationId)
        assertEquals(inserted, saved.messageNodes.subList(1, 3).map { it.id })
        assertTrue(service.contextRequestStore.observe(conversationId).first().isEmpty())
    }

    @Test fun newContextUsesTheSearchablePositionPickerAndAlsoWorksInAnEmptyConversation() = runBlocking {
        settings.awaitLoaded()
        persist(listOf(UIMessage.user("Position first"), UIMessage.assistant("Position middle")))
        var inserted by mutableStateOf<List<Uuid>>(emptyList())
        show { val conversation by service.getConversationFlow(conversationId).collectAsState()
            if (inserted.isEmpty()) ContextInsertionSheet(conversation, onDismiss = {}, onInserted = { inserted = it })
        }
        click("会话结尾"); setText("", "Position middle")
        screenshot("context-v4-position-picker.png")
        click("插在第 2 条之前"); click("新建上下文"); click("添加内容"); click("正文")
        setText("", "Inserted in the middle"); click("保存")
        waitFor { inserted.isNotEmpty() }
        assertEquals(listOf("Position first", "Inserted in the middle", "Position middle"), service.getConversation(conversationId)!!.currentMessages.map { it.toText() })
        assertTrue(service.contextRequestStore.observe(conversationId).first().isEmpty())
        scenario?.close()
        val empty = extraConversation("Empty target fixture", settings.settingsFlow.value.assistantId, emptyList())
        service.addConversationReference(empty.id); service.initializeConversation(empty.id)
        inserted = emptyList()
        show { if (inserted.isEmpty()) ContextInsertionSheet(service.getConversationFlow(empty.id).value, onDismiss = {}, onInserted = { inserted = it }) }
        click("新建上下文"); click("添加内容"); click("正文"); setText("", "First context in an empty conversation"); click("保存")
        waitFor { inserted.size == 1 }
        assertEquals("First context in an empty conversation", service.getConversation(empty.id)!!.currentMessages.single().toText())
        assertTrue(service.contextRequestStore.observe(empty.id).first().isEmpty())
    }

    @Test fun copyingOutUsesTheSameTargetBrowserAndAnExplicitPosition() = runBlocking {
        settings.awaitLoaded()
        val sourceMessage = UIMessage.assistant("Outbound selected message")
        persist(listOf(sourceMessage, UIMessage.user("Keep this source neighbor")))
        val source = service.getConversation(conversationId)!!
        val target = extraConversation("Outbound target fixture", settings.settingsFlow.value.assistantId, listOf(UIMessage.user("Anchor one"), UIMessage.assistant("Anchor two")))
        var copied by mutableStateOf<List<Uuid>>(emptyList())
        var destination: Uuid? = null
        show { if (copied.isEmpty()) Column(Modifier.fillMaxSize().padding(16.dp)) {
            ContextTransferPanel(fixedSource = source, initialMessageId = sourceMessage.id, onDone = { id, nodes -> destination = id; copied = nodes })
        } }
        click("查找目标会话"); waitFor { findText("选择目标会话") != null }
        setText("", "Outbound target"); click("Outbound target fixture")
        waitFor { findText("会话结尾") != null }
        click("会话结尾"); waitFor { findText("选择插入位置") != null }
        setText("", "Anchor two"); click("插在第 2 条之前")
        click("复制并打开目标"); waitFor { copied.size == 1 }
        assertEquals(target.id, destination)
        assertEquals(listOf("Anchor one", "Outbound selected message", "Anchor two"), service.getConversation(target.id)!!.currentMessages.map { it.toText() })
        assertEquals(source.currentMessages, service.getConversation(conversationId)!!.currentMessages)
        assertTrue(service.contextRequestStore.observe(target.id).first().isEmpty())
    }

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        repeat(node.childCount) { index -> findNode(node.getChild(index), predicate)?.let { return it } }
        return null
    }
    private fun findText(text: String, substring: Boolean = false) = findNode(instrumentation.uiAutomation.rootInActiveWindow) {
        val value = it.text?.toString() ?: it.contentDescription?.toString().orEmpty()
        if (substring) value.contains(text) else value == text
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) { screenshot("context-v2-ui-failure.png"); error("UI condition timed out") }
            SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }
    private fun setText(before: String, after: String) {
        var node: AccessibilityNodeInfo? = null
        for (attempt in 0..8) {
            node = findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable && it.text?.toString().orEmpty() == before }
            if (node != null) break
            findNode(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        if (node == null) screenshot("context-v4-ui-failure.png")
        assertNotNull("Editable field '$before' was not found", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, after) }))
        instrumentation.waitForIdleSync()
    }
    private fun click(text: String, substring: Boolean = false) {
        waitFor { findText(text, substring) != null }
        var node = checkNotNull(findText(text, substring))
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $text" }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
