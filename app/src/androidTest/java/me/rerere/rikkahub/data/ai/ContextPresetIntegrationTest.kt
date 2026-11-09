package me.rerere.rikkahub.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.context.withUserReasoning
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ContextPresetIntegrationTest {
    private val service get() = GlobalContext.get().get<ChatService>()
    private val settings get() = GlobalContext.get().get<SettingsStore>()

    @Test fun presetsRemainInitialHistoryAndAuthoredThoughtsReachTheOrdinaryFirstSend() = runBlocking {
        val previous = settings.awaitLoaded()
        val first = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("").withUserReasoning("first authored thought"),
            UIMessagePart.Reasoning("").withUserReasoning("second authored thought"),
            UIMessagePart.Text("<think>literal imported greeting</think>"),
        ))
        val response = """{"id":"reply","model":"preset-test","choices":[{"message":{"role":"assistant","content":"fresh answer"},"finish_reason":"stop"}]}"""
        LoopbackProvider(response).use { server ->
            val model = Model(modelId = "preset-test")
            val provider = ProviderSetting.OpenAI(name = "Preset fixture", baseUrl = server.baseUrl, models = listOf(model), includeHistoryReasoning = false)
            val assistant = Assistant(name = "Preset fixture", chatModelId = model.id, streamOutput = false, localTools = emptyList(), presetMessages = listOf(first))
            val ids = listOf(Uuid.random(), Uuid.random())
            settings.update { it.copy(providers = it.providers + provider, assistants = it.assistants + assistant, assistantId = assistant.id, enableAutoTitle = false, enableSuggestion = false) }
            try {
                service.addConversationReference(ids[0]); service.initializeConversation(ids[0])
                assertEquals(listOf(first), service.getConversation(ids[0])!!.currentMessages)
                assertEquals(0, server.bodies.size)
                val later = first.copy(parts = listOf(UIMessagePart.Text("updated template for later chats")))
                settings.update { it.copy(assistants = it.assistants.map { a -> if (a.id == assistant.id) a.copy(presetMessages = listOf(later)) else a }) }
                service.addConversationReference(ids[1]); service.initializeConversation(ids[1])
                assertEquals(listOf(first), service.getConversation(ids[0])!!.currentMessages)
                assertEquals(listOf(later), service.getConversation(ids[1])!!.currentMessages)
                assertEquals(0, server.bodies.size)
                service.sendMessage(ids[0], listOf(UIMessagePart.Text("New user question")))
                val body = requireNotNull(server.bodies.poll(10, TimeUnit.SECONDS))
                withTimeout(10_000) { service.getConversationFlow(ids[0]).first { it.currentMessages.lastOrNull()?.toText() == "fresh answer" } }
                withTimeout(10_000) { service.getGenerationJobStateFlow(ids[0]).first { it == null } }
                assertTrue(body.contains("reasoning_content"))
                assertTrue(body.contains("first authored thought"))
                assertTrue(body.contains("second authored thought"))
                assertTrue(body.contains("literal imported greeting"))
                assertTrue(body.contains("New user question"))
                assertFalse(body.contains("updated template"))
                assertEquals(first.id, service.getConversation(ids[0])!!.currentMessages.first().id)
                assertEquals(first.parts, service.getConversation(ids[0])!!.currentMessages.first().parts)
            } finally {
                ids.forEach { service.removeConversationReference(it); service.deleteConversation(it) }
                settings.update { it.copy(providers = it.providers.filterNot { p -> p.id == provider.id }, assistants = it.assistants.filterNot { a -> a.id == assistant.id }, assistantId = previous.assistantId, enableAutoTitle = previous.enableAutoTitle, enableSuggestion = previous.enableSuggestion) }
            }
        }
    }

    @Test fun deletingOnePresetConversationKeepsTheTemplateAndOtherConversationAttachment() = runBlocking {
        val previous = settings.awaitLoaded()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = GlobalContext.get().get<FilesManager>()
        val source = File(context.cacheDir, "preset-${Uuid.random()}.txt").apply { writeText("preset attachment") }
        val templateFile = files.createChatFilesByContents(listOf(source.toUri())).single()
        val template = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Document(templateFile.toString(), "preset.txt")))
        val assistant = Assistant(name = "Preset attachment fixture", presetMessages = listOf(template))
        val a = Uuid.random(); val b = Uuid.random()
        settings.update { it.copy(assistants = it.assistants + assistant, assistantId = assistant.id) }
        try {
            listOf(a, b).forEach { service.addConversationReference(it); service.initializeConversation(it); service.insertContextMessages(it, null, emptyList()) }
            val fileA = (service.getConversation(a)!!.currentMessages.single().parts.single() as UIMessagePart.Document).url
            val fileB = (service.getConversation(b)!!.currentMessages.single().parts.single() as UIMessagePart.Document).url
            assertNotEquals(templateFile.toString(), fileA)
            assertNotEquals(fileA, fileB)
            service.deleteConversation(a)
            assertFalse(File(java.net.URI(fileA)).exists())
            assertTrue(File(java.net.URI(fileB)).exists())
            assertTrue(File(java.net.URI(templateFile.toString())).exists())
            assertEquals(template.id, service.getConversation(b)!!.currentMessages.single().id)
        } finally {
            listOf(a, b).forEach { service.removeConversationReference(it); service.deleteConversation(it) }
            settings.update { it.copy(assistants = it.assistants.filterNot { a -> a.id == assistant.id }, assistantId = previous.assistantId) }
            files.deleteChatFiles(listOf(templateFile)); source.delete()
        }
    }
}
