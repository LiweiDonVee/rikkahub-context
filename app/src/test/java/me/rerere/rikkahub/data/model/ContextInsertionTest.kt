package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextInsertionTest {
    private val original = Conversation.ofId(Uuid.random(), messages = listOf("one", "two", "three").map { MessageNode.of(UIMessage.user(it)) })
    private val added = listOf("import first", "import second").map { MessageNode.of(UIMessage.assistant(it)) }

    @Test fun `all positions preserve neighbors and source order`() {
        val cases = listOf(
            ContextInsertionPosition.Start to listOf("import first", "import second", "one", "two", "three"),
            ContextInsertionPosition.End to listOf("one", "two", "three", "import first", "import second"),
            ContextInsertionPosition.Before(original.messageNodes[1].id) to listOf("one", "import first", "import second", "two", "three"),
            ContextInsertionPosition.After(original.messageNodes[1].id) to listOf("one", "two", "import first", "import second", "three"),
        )
        cases.forEach { (position, expected) ->
            val result = original.insertContextNodes(position, added)
            assertEquals(expected, result.currentMessages.map { it.toText() })
            assertEquals(original.messageNodes, result.messageNodes.filterNot { node -> added.any { it.id == node.id } })
        }
    }

    @Test fun `positions resolve on latest history and remain attached to a node across reply changes`() {
        val anchor = original.messageNodes[1]
        val changed = original.copy(messageNodes = original.messageNodes.map {
            if (it.id == anchor.id) it.copy(messages = it.messages + UIMessage.assistant("new variant"), selectIndex = 1) else it
        } + MessageNode.of(UIMessage.user("arrived while selecting")))
        assertEquals("arrived while selecting", changed.insertContextNodes(ContextInsertionPosition.End, added).currentMessages.dropLast(2).last().toText())
        val inserted = changed.insertContextNodes(ContextInsertionPosition.Before(anchor.id), added)
        assertEquals(anchor.messages + changed.messageNodes[1].messages.last(), inserted.messageNodes[3].messages)
        assertEquals("new variant", inserted.currentMessages[3].toText())
        assertEquals(added, inserted.messageNodes.subList(1, 3))
    }

    @Test fun `empty histories support both ends and deleted anchors never silently move`() {
        val empty = original.copy(messageNodes = emptyList())
        assertEquals(added, empty.insertContextNodes(ContextInsertionPosition.Start, added).messageNodes)
        assertEquals(added, empty.insertContextNodes(ContextInsertionPosition.End, added).messageNodes)
        assertThrows(IllegalArgumentException::class.java) { empty.insertContextNodes(ContextInsertionPosition.Before(original.messageNodes[0].id), added) }
    }
}
