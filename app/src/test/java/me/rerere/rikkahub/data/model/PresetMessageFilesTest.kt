package me.rerere.rikkahub.data.model

import me.rerere.ai.context.ContextMessageState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PresetMessageFilesTest {
    @Test fun `text and reasoning presets keep original Rikka identities roles literal text and metadata`() {
        val presets = listOf(UIMessage.user("question"), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Reasoning("thought"), UIMessagePart.Text("<think>literal greeting</think>"))))
        val result = copyPresetMessageFiles(presets) { error("Plain presets must not invoke file copying") }
        assertEquals(presets, result)
        presets.indices.forEach { assertSame(presets[it], result[it]) }
    }

    @Test fun `local files are owned once per conversation while originals and message fields remain intact`() {
        val image = UIMessagePart.Image("file:///template/image.png")
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(image), contextState = ContextMessageState(originalParts = listOf(image)))
        val calls = mutableListOf<String>()
        val a = copyPresetMessageFiles(listOf(original)) { calls += it; "file:///conversation-a/image.png" }.single()
        val b = copyPresetMessageFiles(listOf(original)) { "file:///conversation-b/image.png" }.single()
        assertEquals(1, calls.size)
        assertEquals(original.id, a.id)
        assertEquals(original.createdAt, a.createdAt)
        assertEquals(original.role, a.role)
        assertEquals(a.parts, a.contextState.originalParts)
        assertNotEquals(a.parts, b.parts)
        assertEquals("file:///template/image.png", (original.parts.single() as UIMessagePart.Image).url)
    }
}
