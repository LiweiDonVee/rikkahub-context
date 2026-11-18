package me.rerere.rikkahub.data.ai

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.DeepSeekMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class DeepSeekImportIntegrationTest {
    /** Run in a fresh test installation with an optional, locally supplied export. */
    @Test
    fun importsRealExportAndRepeatsWithoutDuplicateWrites() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("deepSeekArchive")
        assumeTrue(name != null)
        requireNotNull(name)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, name)
        check(file.isFile) { "No local export at the supplied cache location" }

        val components = GlobalContext.get()
        val repository = components.get<ConversationRepository>()
        val migrator = components.get<DeepSeekMigrator>()
        val files = components.get<FilesManager>()
        val db = components.get<AppDatabase>().openHelper.writableDatabase
        val assistant = DeepSeekMigrator.DEEPSEEK_ASSISTANT_ID
        val sentinel = Conversation.ofId(
            Uuid.random(),
            messages = listOf(MessageNode.of(UIMessage.user("A pre-existing native chat"))),
        ).copy(title = "Native fixture")
        assertEquals(null, repository.countConversationsByAssistant()[assistant])
        repository.insertConversation(sentinel)
        val beforeFiles = files.list().size
        val nativeConversations = repository.countConversations()
        val started = SystemClock.elapsedRealtime()
        val imported = migrator.import(file)
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        assertTrue(imported.importedConversations > 0)
        assertTrue(imported.addedMessages > 0)
        assertEquals(imported.importedConversations, repository.countConversationsByAssistant()[assistant])
        assertEquals(nativeConversations + imported.importedConversations, repository.countConversations())
        fun count(sql: String): Int = db.query(sql, arrayOf(assistant.toString())).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        val joined = " JOIN ConversationEntity AS c ON c.id = t.conversation_id WHERE c.assistant_id = ?"
        val links = count("SELECT COUNT(*) FROM conversation_path AS t" + joined)
        val branches = count("SELECT COUNT(*) FROM conversation_branch AS t" + joined)
        assertEquals(imported.addedMessages, links)
        assertEquals(links, count("SELECT COUNT(*) FROM message_node AS t" + joined))
        assertEquals(
            imported.importedConversations,
            count("SELECT COUNT(*) FROM conversation_branch AS t" + joined + " AND t.active = 1")
        )
        val saved = files.list().size - beforeFiles

        for ((key, value) in mapOf(
            "expectedSessions" to imported.importedConversations,
            "expectedNodes" to links,
            "expectedBranches" to branches,
            "expectedMissing" to imported.missingAttachments,
            "expectedFiles" to saved,
        )) {
            args.getString(key)?.toInt()?.let { assertEquals(key, it, value) }
        }

        // Open the current path and an alternate in the largest tree without logging any content.
        val largest = db.query(
            "SELECT t.conversation_id FROM conversation_path AS t" + joined +
                " GROUP BY t.conversation_id ORDER BY count(*) DESC LIMIT 1",
            arrayOf(assistant.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            Uuid.parse(cursor.getString(0))
        }
        val tree = repository.getConversationTree(largest)
        val active = tree.branches.single { it.active }
        assertEquals(tree.path(active.tipId).size, repository.getConversationById(largest)!!.currentMessages.size)
        tree.branches.firstOrNull { !it.active }?.let { alternate ->
            val projected = repository.getBranchConversation(largest, alternate.id)!!
            assertEquals(
                tree.path(alternate.tipId).map { it.messageId },
                projected.currentMessages.map { it.id.toString() }
            )
        }

        val heaviest = db.query(
            "SELECT t.conversation_id, t.id FROM message_node AS t" + joined +
                " ORDER BY length(CAST(t.messages AS BLOB)) DESC LIMIT 1",
            arrayOf(assistant.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            Uuid.parse(cursor.getString(0)) to cursor.getString(1)
        }
        val heavyTree = repository.getConversationTree(heaviest.first)
        val heavyBranch = checkNotNull(heavyTree.branchForNode(heaviest.second))
        assertTrue(repository.getBranchConversation(heaviest.first, heavyBranch.id)!!.messageNodes.any {
            it.id.toString() ==
                heaviest.second
        })

        val replayStart = SystemClock.elapsedRealtime()
        val repeated = migrator.import(file)
        val replaySeconds = (SystemClock.elapsedRealtime() - replayStart) / 1000.0
        assertEquals(0, repeated.importedConversations)
        assertEquals(0, repeated.addedMessages)
        assertEquals(imported.importedConversations, repeated.skippedExistingConversations)
        assertEquals(nativeConversations + imported.importedConversations, repository.countConversations())
        assertEquals(beforeFiles + saved, files.list().size)
        assertEquals(sentinel.currentMessages, repository.getConversationById(sentinel.id)!!.currentMessages)
        Log.i(
            "DeepSeekImportCheck",
            "sessions=${imported.importedConversations} nodes=$links branches=$branches files=$saved missing=${imported.missingAttachments} firstSeconds=$seconds repeatSeconds=$replaySeconds"
        )
        Unit
    }
}
