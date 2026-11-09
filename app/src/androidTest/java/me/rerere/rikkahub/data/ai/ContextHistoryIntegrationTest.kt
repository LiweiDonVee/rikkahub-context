package me.rerere.rikkahub.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.context.ContextMessageState
import me.rerere.ai.context.saveContextEdit
import me.rerere.ai.context.withUserReasoning
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ContextHistoryIntegrationTest {
    private val service get() = GlobalContext.get().get<ChatService>()
    private val repository get() = GlobalContext.get().get<ConversationRepository>()
    private val fixtures = mutableListOf<Uuid>()

    private suspend fun persist(messages: List<UIMessage>): Conversation {
        val conversation = Conversation.ofId(Uuid.random(), messages = messages.map { MessageNode.of(it) }).copy(title = "Context test ${Uuid.random()}")
        repository.insertConversation(conversation)
        fixtures += conversation.id
        return conversation
    }

    @After fun cleanup() = runBlocking { fixtures.forEach { service.deleteConversation(it) } }

    @Test fun archivedReplyAttachmentsStayReferencedUntilTheirConversationIsDeleted() = runBlocking {
        val conversation = persist(listOf(UIMessage.user("No visible attachment")))
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val attachment = java.io.File(context.filesDir, "context-archive-${Uuid.random()}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val url = attachment.toURI().toString()
        val runId = Uuid.random()
        service.contextRequestStore.control(conversation.id, runId, Uuid.random(), "fixture", emptyList(), me.rerere.ai.context.ContextSelection(), emptyList())
            .prepare("fixture", "https://example.test", "{}")
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Image(url)))
        service.contextRequestStore.finish(runId, conversation.id, "completed", reply)
        assertTrue(repository.hasFileReference(url))
        assertTrue(attachment.exists())
        service.deleteConversation(conversation.id)
        fixtures.remove(conversation.id)
        assertFalse(attachment.exists())
        assertEquals("", service.contextRequestStore.response(runId.toString()))
    }

    @Test fun editingThinkingAndToolResultsPersistsWithoutExecutingAnything() = runBlocking {
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("original\nthinking"),
            UIMessagePart.Tool("call", "not_an_authorized_tool", "{}", listOf(UIMessagePart.Text("old result"))),
            UIMessagePart.Text("answer"),
        ))
        val later = UIMessage.user("keep later history")
        val conversation = persist(listOf(original, later))
        val parts = original.parts.toMutableList().also {
            it[0] = (it[0] as UIMessagePart.Reasoning).withUserReasoning("edited\n\nthinking")
            it[1] = (it[1] as UIMessagePart.Tool).copy(toolName = "user_defined_tool", input = "{\"custom\":1}", output = listOf(UIMessagePart.Text("edited result")), approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)
        }
        service.saveContextMessage(conversation.id, original.id, parts)
        val saved = repository.getConversationById(conversation.id)!!
        assertEquals(listOf(original.id, later.id), saved.currentMessages.map { it.id })
        assertEquals(parts, saved.currentMessages.first().parts)
        assertEquals(original.parts, saved.currentMessages.first().contextState.originalParts)
        assertNull(service.getGenerationJobStateFlow(conversation.id).first())
        assertTrue(service.contextRequestStore.observe(conversation.id).first().isEmpty())
        assertTrue(saved.currentMessages.first().contextState.toolRuns.isEmpty())
        assertTrue(saved.currentMessages.first().contextState.manualToolExecution)
        service.restoreContextMessage(conversation.id, original.id)
        assertEquals(original.parts, repository.getConversationById(conversation.id)!!.currentMessages.first().parts)
    }

    @Test fun noncontiguousNativeCopyInsertsAtTheAnchorAndKeepsSourceUnchanged() = runBlocking {
        val user = UIMessage.user("question")
        val skip = UIMessage.assistant("do not copy")
        val signature = kotlinx.serialization.json.JsonObject(mapOf("signature" to kotlinx.serialization.json.JsonPrimitive("native-signature")))
        val answer = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("thinking", metadata = signature),
            UIMessagePart.Tool("call", "read", "{}", listOf(UIMessagePart.Text("result"), UIMessagePart.Reasoning("tool thought")), metadata = signature),
            UIMessagePart.Text("answer"), UIMessagePart.Audio("https://example.test/audio.mp3"), UIMessagePart.Video("https://example.test/video.mp4"),
        ))
        val source = persist(listOf(user, skip, answer))
        val beginning = UIMessage.user("beginning")
        val end = UIMessage.assistant("end")
        val target = persist(listOf(beginning, end))
        service.copyContextMessages(source.id, setOf(user.id, answer.id), target.id, beginning.id, "original")
        assertEquals(source.currentMessages, repository.getConversationById(source.id)!!.currentMessages)
        val copied = repository.getConversationById(target.id)!!.currentMessages
        assertEquals(4, copied.size)
        assertEquals(beginning, copied.first())
        assertEquals(end, copied.last())
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), copied.subList(1, 3).map { it.role })
        assertNotEquals(user.id, copied[1].id)
        assertNotEquals("call", copied[2].getTools().single().toolCallId)
        assertEquals("result", (copied[2].getTools().single().output.first() as UIMessagePart.Text).text)
        assertEquals(answer.parts.filterNot { it is UIMessagePart.Tool }, copied[2].parts.filterNot { it is UIMessagePart.Tool })
        assertEquals(answer.getTools().single().output, copied[2].getTools().single().output)
        assertEquals(signature, copied[2].getTools().single().metadata)
        assertEquals(source.id.toString(), copied[2].contextState.sources.single().conversationId)
        assertNull(service.getGenerationJobStateFlow(target.id).first())
    }

    @Test fun editedNativeReasoningCanBeCopiedWithoutCompatibilityPreflight() = runBlocking {
        val original = UIMessagePart.Reasoning("old", metadata = kotlinx.serialization.json.JsonObject(mapOf("signature" to kotlinx.serialization.json.JsonPrimitive("opaque"))))
        val edited = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(original)).saveContextEdit(listOf(original.copy(reasoning = "edited", metadata = null)))
        val source = persist(listOf(edited))
        val target = persist(listOf(UIMessage.user("target")))
        service.copyContextMessages(source.id, setOf(edited.id), target.id, null, "original")
        val native = repository.getConversationById(target.id)!!.currentMessages.first().parts.single() as UIMessagePart.Reasoning
        assertEquals("edited", native.reasoning)
        assertEquals(edited.parts.single().metadata, native.metadata)
        service.copyContextMessages(source.id, setOf(edited.id), target.id, null, "text")
        val copied = repository.getConversationById(target.id)!!.currentMessages.first()
        assertTrue(copied.parts.single() is UIMessagePart.Text)
        assertTrue(copied.toText().contains("edited"))
        assertEquals(1, repository.getConversationById(source.id)!!.currentMessages.size)
    }

    @Test fun clearingACompletedResultDoesNotReplayItWhenAnotherCallIsApproved() = runBlocking {
        val settings = GlobalContext.get().get<me.rerere.rikkahub.data.datastore.SettingsStore>()
        settings.awaitLoaded()
        LoopbackProvider("{}").use { server ->
            val model = me.rerere.ai.provider.Model(modelId = "fixture-model", abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL))
            val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(name = "Pending fixture", models = listOf(model), baseUrl = server.baseUrl)
            val assistant = me.rerere.rikkahub.data.model.Assistant(name = "Pending fixture", chatModelId = model.id, streamOutput = false, localTools = listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.JavascriptEngine))
            settings.update { it.copy(providers = it.providers + provider, assistants = it.assistants + assistant) }
            val completed = UIMessagePart.Tool("a", "eval_javascript", """{"code":"throw new Error('A must stay pending')"}""", listOf(UIMessagePart.Text("previous result")))
            val pending = UIMessagePart.Tool("b", "eval_javascript", """{"code":"21 * 2"}""", approvalState = me.rerere.ai.ui.ToolApprovalState.Pending)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completed, pending))
            val conversation = Conversation.ofId(Uuid.random(), assistantId = assistant.id, messages = listOf(MessageNode.of(UIMessage.user("test")), MessageNode.of(message)))
            repository.insertConversation(conversation); fixtures += conversation.id
            try {
                service.saveContextMessage(conversation.id, message.id, listOf(completed.copy(output = emptyList()), pending))
                val saved = service.getConversation(conversation.id)!!.currentMessages.last()
                assertTrue(saved.contextState.manualToolExecution)
                assertTrue(saved.getTools().all { it.isPending })
                service.handleToolApproval(conversation.id, "b", approved = true)
                withTimeout(10_000) { service.getConversationFlow(conversation.id).first { current -> current.currentMessages.last().getTools().any { it.toolCallId == "b" && it.isExecuted } } }
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                val calls = service.getConversation(conversation.id)!!.currentMessages.last().getTools()
                assertTrue(calls.first().isPending)
                assertFalse(calls.first().isExecuted)
                assertTrue((calls.last().output.single() as UIMessagePart.Text).text.contains("42"))
                assertTrue(server.bodies.isEmpty())
            } finally {
                settings.update { current -> current.copy(providers = current.providers.filterNot { it.id == provider.id }, assistants = current.assistants.filterNot { it.id == assistant.id }) }
            }
        }
    }

    @Test fun editedToolRunsLocallyAndBreakpointRequestsPreserveTheReviewedProjection() = runBlocking {
        val textReply = """{"id":"chat","model":"fixture-model","choices":[{"message":{"role":"assistant","content":"fresh answer"},"finish_reason":"stop"}]}"""
        LoopbackProvider(textReply).use { server ->
            val settings = GlobalContext.get().get<me.rerere.rikkahub.data.datastore.SettingsStore>()
            settings.awaitLoaded()
            val model = me.rerere.ai.provider.Model(modelId = "fixture-model", abilities = listOf(me.rerere.ai.provider.ModelAbility.TOOL))
            val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(name = "Context fixture", models = listOf(model), baseUrl = server.baseUrl)
            val assistant = me.rerere.rikkahub.data.model.Assistant(name = "Context fixture", chatModelId = model.id, streamOutput = false, localTools = listOf(me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.JavascriptEngine))
            settings.update { it.copy(providers = it.providers + provider, assistants = it.assistants + assistant) }
            val user = UIMessage.user("fixture question")
            val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Reasoning("do-not-send-thinking"),
                UIMessagePart.Tool("call", "eval_javascript", """{"code":"1 + 1"}""", listOf(UIMessagePart.Text("2"))),
                UIMessagePart.Text("OLD ANSWER SUFFIX"),
            ))
            val conversation = Conversation.ofId(Uuid.random(), assistantId = assistant.id, messages = listOf(MessageNode.of(user), MessageNode.of(original)))
            repository.insertConversation(conversation)
            fixtures += conversation.id
            try {
                val edited = original.parts.toMutableList().also { it[1] = (it[1] as UIMessagePart.Tool).copy(input = """{"code":"40 + 1"}""") }
                service.saveContextMessage(conversation.id, original.id, edited)
                assertEquals(0, server.bodies.size)
                service.rerunContextTool(conversation.id, original.id, 1, confirmedSideEffects = true)
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                val tool = service.getConversation(conversation.id)!!.currentMessages.last().getTools().single()
                assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("41"))
                assertEquals(0, server.bodies.size)
                val selection = me.rerere.ai.context.ContextSelection(includeReasoning = false, includeSkills = false)
                val gate = service.previewContextRequest(conversation.id, original.id, 1, continueAfter = true, selection = selection)
                val prepared = withTimeout(10_000) { gate.preview.filterNotNull().first() }
                assertFalse(prepared.body.contains("do-not-send-thinking"))
                assertFalse(prepared.body.contains("OLD ANSWER SUFFIX"))
                assertTrue(prepared.body.contains("41"))
                assertEquals(0, server.bodies.size)
                val reviewed = prepared.body.replace("fixture question", "reviewed question")
                gate.confirm(reviewed)
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                assertEquals(reviewed, server.bodies.peek())
                var saved = service.getConversation(conversation.id)!!.currentMessages
                assertEquals("fixture question", saved.first().toText())
                assertTrue(saved.last().toText().contains("fresh answer"))
                assertFalse(saved.last().toText().contains("OLD ANSWER SUFFIX"))
                assertTrue(saved.last().contextState.manualToolExecution)
                val firstRecord = service.contextRequestStore.observe(conversation.id).first().single()
                assertEquals("completed", firstRecord.generationStatus)
                val firstArchive = service.contextRequestStore.response(firstRecord.runId)
                assertTrue(firstArchive.contains("fresh answer"))
                service.saveContextMessage(conversation.id, original.id, saved.last().parts.map {
                    if (it is UIMessagePart.Text) it.copy(text = "manually overwritten answer") else it
                })
                assertEquals(firstArchive, service.contextRequestStore.response(firstRecord.runId))

                server.response = """{"id":"chat-2","model":"fixture-model","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"new-call","type":"function","function":{"name":"eval_javascript","arguments":"{\"code\":\"100 + 1\"}"}}]},"finish_reason":"tool_calls"}]}"""
                val newGate = service.previewContextRequest(conversation.id, original.id, selection = selection)
                val second = withTimeout(10_000) { newGate.preview.filterNotNull().first() }
                newGate.confirm(second.body)
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                assertFalse(service.getConversation(conversation.id)!!.currentMessages.last().getTools().single().isExecuted)
                assertEquals(2, server.bodies.size)
                service.handleToolApproval(conversation.id, "new-call", approved = true)
                withTimeout(10_000) { service.getConversationFlow(conversation.id).first { current -> current.currentMessages.last().getTools().any { it.toolCallId == "new-call" && it.isExecuted } } }
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                val newResult = service.getConversation(conversation.id)!!.currentMessages.last().getTools().single().output.single() as UIMessagePart.Text
                assertTrue(newResult.text.contains("101"))
                assertEquals(2, server.bodies.size)
                val states = service.contextRequestStore.observe(conversation.id).first().map { it.generationStatus }.toSet()
                assertEquals(setOf("completed", "awaiting_tool_approval"), states)
                assertEquals(firstArchive, service.contextRequestStore.response(firstRecord.runId))
                val approvalRecord = service.contextRequestStore.observe(conversation.id).first().single { it.generationStatus == "awaiting_tool_approval" }
                val approvalReply = me.rerere.rikkahub.utils.JsonInstant.decodeFromString<UIMessage>(service.contextRequestStore.response(approvalRecord.runId))
                assertFalse(approvalReply.getTools().single().isExecuted)
                server.response = textReply
                val finalGate = service.previewContextRequest(conversation.id, original.id, 0, continueAfter = true, selection = selection)
                val final = withTimeout(10_000) { finalGate.preview.filterNotNull().first() }
                assertTrue(final.body.contains("101"))
                finalGate.confirm(final.body)
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                saved = service.getConversation(conversation.id)!!.currentMessages
                assertEquals(3, server.bodies.size)
                assertEquals("new-call", saved.last().getTools().single().toolCallId)
                assertTrue(saved.last().toText().contains("fresh answer"))

                server.response = """{"id":"unknown","model":"fixture-model","choices":[{"message":{"role":"assistant","tool_calls":[{"id":"unknown-call","type":"function","function":{"name":"context_unknown_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                val unknownGate = service.previewContextRequest(conversation.id, original.id, selection = selection)
                val unknownPreview = withTimeout(10_000) { unknownGate.preview.filterNotNull().first() }
                val raw = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(unknownPreview.body) as kotlinx.serialization.json.JsonObject
                val definitions = (raw["tools"] as? kotlinx.serialization.json.JsonArray).orEmpty() +
                    me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement("""{"type":"function","function":{"name":"context_unknown_tool","parameters":{"type":"object"}}}""")
                unknownGate.confirm(kotlinx.serialization.json.JsonObject(raw + ("tools" to kotlinx.serialization.json.JsonArray(definitions))).toString())
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                val unknown = service.getConversation(conversation.id)!!.currentMessages.last().getTools().single()
                assertEquals("context_unknown_tool", unknown.toolName)
                assertTrue(unknown.isPending)
                assertFalse(unknown.isExecuted)
                assertEquals(4, server.bodies.size)
                service.handleToolApproval(conversation.id, "unknown-call", approved = true)
                withTimeout(10_000) { service.errors.first { errors -> errors.any { it.conversationId == conversation.id && it.error.message.orEmpty().contains("not currently authorized") } } }
                withTimeout(10_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
                assertEquals(4, server.bodies.size)
                assertFalse(service.getConversation(conversation.id)!!.currentMessages.last().getTools().single().isExecuted)
            } finally {
                service.deleteConversation(conversation.id)
                fixtures.remove(conversation.id)
                settings.update { it.copy(providers = it.providers.filterNot { p -> p.id == provider.id }, assistants = it.assistants.filterNot { a -> a.id == assistant.id }) }
            }
        }
    }
}
