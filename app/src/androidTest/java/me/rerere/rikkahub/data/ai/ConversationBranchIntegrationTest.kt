package me.rerere.rikkahub.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationPathEntity
import me.rerere.rikkahub.data.model.ContextInsertionPosition
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationBranchIntegrationTest {
    private val service get() = GlobalContext.get().get<ChatService>()
    private val repository get() = GlobalContext.get().get<ConversationRepository>()

    @Test
    fun branchPathsKeepTheOldSuffixAndCanBeCopiedWithoutSelectingThem() = runBlocking {
        val question = UIMessage.user("branch question")
        val original = UIMessage.assistant("branch original")
        val variant = UIMessage.assistant("branch alternate")
        val tail = UIMessage.user("previousbranchsuffix")
        val source = Conversation.ofId(
            Uuid.random(),
            messages = listOf(
                MessageNode.of(question),
                MessageNode(messages = listOf(original, variant)),
                MessageNode.of(tail)
            ),
        )
        val anchor = MessageNode.of(UIMessage.user("insert after"))
        val target = Conversation.ofId(
            Uuid.random(),
            messages = listOf(anchor, MessageNode.of(UIMessage.user("retained tail")))
        )
        try {
            repository.insertConversation(source)
            repository.insertConversation(target)
            val newBranch = service.createConversationBranch(source.id, variant.id)
            assertEquals(listOf(question.id, variant.id), newBranch.currentMessages.map { it.id })
            val tree = repository.getConversationTree(source.id)
            assertEquals(2, tree.branches.size)
            assertEquals(4, tree.links.size)
            assertEquals(setOf(source.id.toString()), tree.links.map { it.conversationId }.toSet())
            val previous = tree.branches.single { !it.active }
            assertEquals(tail.id.toString(), repository.searchMessages("previousbranchsuffix").single().messageId)

            service.copyContextMessages(
                source.id,
                setOf(tail.id),
                target.id,
                ContextInsertionPosition.After(anchor.id),
                "original",
                previous.id,
            )
            assertEquals(
                "previousbranchsuffix",
                repository.getConversationById(target.id)!!.currentMessages[1].toText()
            )
            assertEquals(
                listOf(question.id, variant.id),
                repository.getConversationById(source.id)!!.currentMessages.map { it.id }
            )
            service.insertContextMessages(source.id, ContextInsertionPosition.End, listOf(UIMessage.user("new tail")))
            assertEquals(
                listOf(question.id, original.id, tail.id),
                service.switchConversationBranch(source.id, previous.id).currentMessages.map { it.id }
            )
            assertEquals(
                "new tail",
                repository.getBranchConversation(
                    source.id,
                    tree.branches.single { it.active }.id
                )!!.currentMessages.last().toText()
            )
            assertTrue(service.contextRequestStore.observe(source.id).first().isEmpty())
        } finally {
            service.deleteConversation(source.id)
            service.deleteConversation(target.id)
        }
    }

    @Test
    fun newerExportAddsAPathAndKeepsTheLocallyEditedMessage() = runBlocking {
        val root = MessageNode.of(UIMessage.user("Archive question"))
        val reply = MessageNode.of(UIMessage.assistant("Source answer"))
        val source = Conversation.ofId(Uuid.random(), messages = listOf(root, reply))
        val id = source.id.toString()
        fun link(node: MessageNode, parent: MessageNode?) = ConversationPathEntity(
            node.id.toString(),
            id,
            parent?.id?.toString(),
            node.id.toString(),
            node.currentMessage.id.toString(),
            node.currentMessage.toText(),
            node.role.name,
        )
        val links = listOf(link(root, null), link(reply, root))
        try {
            assertTrue(repository.importConversationTree(source, links, reply.id.toString()))
            val imported = checkNotNull(repository.getConversationById(source.id))
            val edited = reply.copy(
                messages = listOf(reply.currentMessage.copy(parts = UIMessage.assistant("Local answer").parts))
            )
            repository.updateConversation(
                imported.copy(title = "Local title", messageNodes = listOf(root, edited)),
                imported
            )
            val followup = MessageNode.of(UIMessage.user("New archived follow-up"))
            assertTrue(repository.importConversationTree(
                source.copy(messageNodes = source.messageNodes + followup),
                links + link(followup, reply),
                followup.id.toString(),
            ))
            val live = checkNotNull(repository.getConversationById(source.id))
            assertEquals("Local title", live.title)
            assertEquals(listOf("Archive question", "Local answer"), live.currentMessages.map { it.toText() })
            val added = repository.getConversationTree(source.id).branches.single { it.tipId == followup.id.toString() }
            assertEquals(
                listOf("Archive question", "Local answer", "New archived follow-up"),
                repository.getBranchConversation(source.id, added.id)!!.currentMessages.map { it.toText() }
            )
        } finally {
            service.deleteConversation(source.id)
        }
    }

    @Test
    fun addingATurnDoesNotRewriteOneThousandPriorNodes() = runBlocking {
        val db = GlobalContext.get().get<AppDatabase>().openHelper.writableDatabase
        val before = Conversation.ofId(
            Uuid.random(),
            messages = List(1_000) { MessageNode.of(UIMessage.user("History " + it)) }
        )
        repository.insertConversation(before)
        val key = before.id.toString()
        try {
            db.execSQL("CREATE TABLE context_write_measurement (kind TEXT NOT NULL)")
            for ((action, row) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
                db.execSQL(
                    "CREATE TRIGGER context_measure_$action AFTER $action ON message_node " +
                        "WHEN $row.conversation_id = '$key' BEGIN INSERT INTO context_write_measurement VALUES ('$action'); END"
                )
            }
            val appended = MessageNode.of(UIMessage.user("writefixtureunique"))
            repository.updateConversation(before.copy(messageNodes = before.messageNodes + appended), before)
            val operations = db.query("SELECT kind FROM context_write_measurement").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(listOf("INSERT"), operations)
            assertEquals(1_001, repository.getConversationById(before.id)!!.messageNodes.size)
            assertEquals(
                appended.currentMessage.id.toString(),
                repository.searchMessages("writefixtureunique").single().messageId
            )
        } finally {
            for (action in listOf(
                "INSERT",
                "UPDATE",
                "DELETE"
            )) {
                db.execSQL("DROP TRIGGER IF EXISTS context_measure_$action")
            }
            db.execSQL("DROP TABLE IF EXISTS context_write_measurement")
            service.deleteConversation(before.id)
        }
    }
}
