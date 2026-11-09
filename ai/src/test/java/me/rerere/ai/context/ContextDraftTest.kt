package me.rerere.ai.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ContextDraftTest {
    @Test fun `a text only assistant can gain its first reasoning and keep its identity`() {
        val text = UIMessagePart.Text("literal <think>tag</think>", JsonObject(mapOf("source" to JsonPrimitive("import"))))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(text))
        val thinking = UIMessagePart.Reasoning("").withUserReasoning("first\n\nsecond")
        val updated = original.saveContextDraft(listOf(ContextPartDraft(thinking)) + original.contextDraft())
        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(original.role, updated.role)
        assertEquals(text, updated.parts[1])
        assertEquals("first\n\nsecond", (updated.parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals(original, updated.restoreContextOriginal())
    }

    @Test fun `moves and additions remap exclusions and invalid signatures by source part`() {
        val signed = UIMessagePart.Reasoning("signed", metadata = JsonObject(mapOf("signature" to JsonPrimitive("opaque"))))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(signed, UIMessagePart.Text("excluded")),
            contextState = ContextMessageState(excludedParts = setOf(1), invalidOpaqueParts = setOf(0)))
        val draft = original.contextDraft()
        val updated = original.saveContextDraft(listOf(ContextPartDraft(UIMessagePart.Reasoning("new")), draft[1], draft[0]))
        assertEquals(setOf(1), updated.contextState.excludedParts)
        assertEquals(setOf(2), updated.contextState.invalidOpaqueParts)
        val removed = updated.saveContextDraft(updated.contextDraft().drop(1))
        assertEquals(setOf(0), removed.contextState.excludedParts)
        assertEquals(setOf(1), removed.contextState.invalidOpaqueParts)
    }

    @Test fun `inserting a plain thought before a signed part does not invalidate either`() {
        val signed = UIMessagePart.Reasoning("signed", metadata = JsonObject(mapOf("signature" to JsonPrimitive("opaque"))))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(signed))
        val updated = original.saveContextDraft(listOf(ContextPartDraft(UIMessagePart.Reasoning("new"))) + original.contextDraft())
        assertTrue(updated.contextState.invalidOpaqueParts.isEmpty())
        val changed = updated.contextDraft().toMutableList().apply { this[1] = this[1].copy(part = signed.copy(reasoning = "changed")) }
        assertEquals(setOf(1), updated.saveContextDraft(changed).contextState.invalidOpaqueParts)
    }

    @Test fun `delete all parts is reversible and compilation records the empty omission`() {
        val original = UIMessage.assistant("answer")
        val empty = original.saveContextDraft(emptyList())
        assertEquals(original, empty.restoreContextOriginal())
        assertTrue(compileContext(listOf(empty), ContextSelection()).messages.isEmpty())
        assertTrue(compileContext(listOf(empty), ContextSelection()).exclusions.any { it.reason.contains("没有选中") })
    }

    @Test fun `delivery feedback checks native fields instead of matching text inside a normal answer`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("authored thought")))
        assertEquals(1, inspectReasoningDelivery(listOf(message), "openai_chat_completions", """{"messages":[{"role":"assistant","content":"authored thought"}]}""").omissions.size)
        assertTrue(inspectReasoningDelivery(listOf(message), "openai_chat_completions", """{"messages":[{"reasoning_content":"authored thought"}]}""").omissions.isEmpty())
        assertEquals(1, inspectReasoningDelivery(listOf(message), "google_interactions", """{"input":[{"type":"text","text":"answer"}]}""").omissions.size)
        assertTrue(inspectReasoningDelivery(listOf(message), "google_generate_content", """{"contents":[{"parts":[{"thought":true,"text":"authored thought"}]}]}""").omissions.isEmpty())
    }
}
