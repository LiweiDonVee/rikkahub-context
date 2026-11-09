package me.rerere.ai.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class ContextEngineeringTest {
    private fun tool(id: String = "one", output: String? = "result") = UIMessagePart.Tool(
        toolCallId = id, toolName = "read", input = "{\"path\":\"a\"}",
        output = output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(),
    )

    @Test fun `editing is lossless and restoring does not execute tools`() {
        var executions = 0
        val authorized = listOf(Tool("read", "read file", execute = { executions++; emptyList() }))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning(" first\nsecond \n"), tool()))
        val updatedParts = listOf(UIMessagePart.Reasoning(" changed\n\n"), tool().copy(input = "{\"path\":\"b\"}"))
        validateToolEdit(updatedParts[1] as UIMessagePart.Tool, authorized)
        val edited = original.saveContextEdit(updatedParts)
        val decoded = json.decodeFromString<UIMessage>(json.encodeToString(edited))
        assertEquals(original.id, decoded.id)
        assertEquals(original.role, decoded.role)
        assertEquals(updatedParts, decoded.parts)
        assertEquals(original.parts, decoded.restoreContextOriginal().parts)
        assertEquals(0, executions)
    }

    @Test fun `context exclusions leave immutable history intact and keep tool pairs together`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("thinking"), tool(), UIMessagePart.Text("answer")))
        val context = compileContext(listOf(message), ContextSelection(includeReasoning = false, includeToolResults = false))
        assertEquals(listOf(UIMessagePart.Text("answer")), context.messages.single().parts)
        assertEquals(2, context.exclusions.size)
        assertEquals(3, message.parts.size)
        assertTrue(message.getTools().single().isExecuted)
    }

    @Test fun `missing historical results are excluded and never fabricated`() {
        val pending = tool(output = null)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending, UIMessagePart.Text("after")))
        val result = compileContext(listOf(message), ContextSelection())
        assertTrue(result.messages.single().getTools().isEmpty())
        assertTrue(result.exclusions.single().reason.contains("never replayed"))
        assertTrue(message.getTools().single().output.isEmpty())
    }

    @Test fun `skill policy removes only skill calls and results`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool().copy(toolName = "use_skill"), tool("two")))
        val compiled = compileContext(listOf(message), ContextSelection(includeSkills = false))
        assertEquals(listOf("two"), compiled.messages.single().getTools().map { it.toolCallId })
    }

    @Test fun `continue includes entire parallel tool batch and excludes later model text`() {
        val user = UIMessage.user("start")
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("before"), tool("one"), tool("two"), UIMessagePart.Text("old answer")))
        val continuation = contextAtBreakpoint(listOf(user, message, UIMessage.user("later")), message.id, 1, true)
        assertEquals(listOf("one", "two"), continuation.last().getTools().map { it.toolCallId })
        assertEquals(3, continuation.last().parts.size)
        assertEquals(2, continuation.size)
    }

    @Test fun `continue refuses a parallel batch with a missing result`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("one"), tool("two", null)))
        assertThrows(IllegalArgumentException::class.java) { contextAtBreakpoint(listOf(message), message.id, 0, true) }
    }

    @Test fun `explicitly excluded missing sibling does not prevent continuing a parallel batch`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool("one"), tool("two", null)), contextState = ContextMessageState(excludedParts = setOf(1)))
        val history = contextAtBreakpoint(listOf(message), message.id, 0, true)
        assertEquals(listOf("one"), compileContext(history, ContextSelection()).messages.single().getTools().map { it.toolCallId })
    }

    @Test fun `editing a signed tool result does not invalidate its unchanged signed call`() {
        val call = tool().copy(metadata = JsonObject(mapOf("thoughtSignature" to JsonPrimitive("signed-call"))))
        val edited = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call)).saveContextEdit(listOf(call.copy(output = listOf(UIMessagePart.Text("edited result")))))
        assertTrue(edited.contextState.invalidOpaqueParts.isEmpty())
        assertEquals("edited result", (compileContext(listOf(edited), ContextSelection()).messages.single().getTools().single().output.single() as UIMessagePart.Text).text)
    }

    @Test fun `signed reasoning edits can be saved but require explicit exclusion before replay`() {
        val signed = UIMessagePart.Reasoning("original", metadata = JsonObject(mapOf("signature" to JsonPrimitive("opaque"))))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(signed, UIMessagePart.Text("answer")))
            .saveContextEdit(listOf(signed.copy(reasoning = "edited"), UIMessagePart.Text("answer")))
        assertThrows(IllegalArgumentException::class.java) { compileContext(listOf(message), ContextSelection()) }
        assertEquals(1, compileContext(listOf(message), ContextSelection(includeReasoning = false)).messages.single().parts.size)
    }

    @Test fun `explicit conversion of edited signed thinking to text is replayable`() {
        val signed = UIMessagePart.Reasoning("visible", metadata = JsonObject(mapOf("signature" to JsonPrimitive("opaque"))))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(signed))
        val edited = original.saveContextEdit(listOf(signed.copy(reasoning = "edited")))
        val converted = edited.saveContextEdit(listOf(UIMessagePart.Text("[Thinking]\nedited")))
        assertTrue(converted.contextState.invalidOpaqueParts.isEmpty())
        assertEquals("[Thinking]\nedited", compileContext(listOf(converted), ContextSelection()).messages.single().toText())
        assertEquals(listOf(signed), converted.restoreContextOriginal().parts)
    }

    @Test fun `a newly generated signed part is not an edited old signature`() {
        val old = UIMessagePart.Reasoning("old", metadata = JsonObject(mapOf("signature" to JsonPrimitive("old-signature"))))
        val fresh = old.copy(reasoning = "new model reasoning", metadata = JsonObject(mapOf("signature" to JsonPrimitive("new-signature"))))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(fresh), contextState = ContextMessageState(originalParts = listOf(old)))
        assertEquals(listOf(fresh), compileContext(listOf(message), ContextSelection()).messages.single().parts)
    }

    @Test fun `editing a signed Google text part requires exclusion or explicit conversion`() {
        val signed = UIMessagePart.Text("original", metadata = JsonObject(mapOf("thoughtSignature" to JsonPrimitive("signature"))))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(signed)).saveContextEdit(listOf(signed.copy(text = "edited")))
        assertEquals(setOf(0), message.contextState.invalidOpaqueParts)
        assertThrows(IllegalArgumentException::class.java) { compileContext(listOf(message), ContextSelection()) }
        assertTrue(message.saveContextEdit(listOf(UIMessagePart.Text("explicit plain text"))).contextState.invalidOpaqueParts.isEmpty())
    }

    @Test fun `malformed or unauthorized tool calls never degrade to empty arguments`() {
        val authorized = listOf(Tool("read", "read", execute = { emptyList() }))
        assertThrows(IllegalArgumentException::class.java) { validateToolEdit(tool().copy(input = "{"), authorized) }
        assertThrows(IllegalArgumentException::class.java) { validateToolEdit(tool().copy(input = "[]"), authorized) }
        assertThrows(IllegalStateException::class.java) { validateToolEdit(tool().copy(toolName = "delete"), authorized) }
    }

    @Test fun `request order and noncontiguous selection are independent of saved history`() {
        val one = UIMessage.user("one")
        val two = UIMessage.assistant("two")
        val three = UIMessage.user("three")
        val history = listOf(one, two, three)
        val compiled = compileContext(history, ContextSelection(excludedMessages = setOf(two.id.toString()), messageOrder = listOf(three.id.toString(), one.id.toString(), two.id.toString())))
        assertEquals(listOf(three.id, one.id), compiled.messages.map { it.id })
        assertEquals(listOf(one, two, three), history)
    }

    @Test fun `old history JSON defaults to no exclusions and no edit state`() {
        val decoded = json.decodeFromString<UIMessage>("""{"role":"user","parts":[{"type":"text","text":"old"}]}""")
        assertEquals(ContextMessageState(), decoded.contextState)
        assertEquals("old", compileContext(listOf(decoded), ContextSelection()).messages.single().toText())
    }
}
