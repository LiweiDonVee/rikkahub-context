package me.rerere.rikkahub.data.ai

import androidx.paging.PagingSource
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.repository.LightConversationEntity
import me.rerere.rikkahub.data.model.ContextConversationSort
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextConversationPickerIntegrationTest {
    @Test fun lightweightPagesFilterAssistantsIncludeFoldersAndSortAcrossPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "context-picker-${Uuid.random()}.db"
        val database = AppDatabaseFactory.create(context, name)
        try {
            val dao = database.conversationDao()
            val ids = (0 until 75).map { Uuid.random().toString() }
            ids.forEachIndexed { index, id -> dao.insert(ConversationEntity(
                id = id, assistantId = if (index < 70) "agent-a" else "agent-b", title = "Topic ${index.toString().padStart(3, '0')}",
                nodes = "[]", createAt = index.toLong(), updateAt = (100 - index).toLong(), chatSuggestions = "[]", isPinned = false,
                folderId = if (index % 2 == 0) "inside-folder" else "",
            )) }
            val paging = dao.contextConversations("agent-a", "%Topic%", ContextConversationSort.CREATED_ASC.name, ids[12])
            val found = mutableListOf<String>()
            var key: Int? = null
            do {
                val page = paging.load(if (key == null) PagingSource.LoadParams.Refresh(null, 20, false) else PagingSource.LoadParams.Append(key!!, 20, false)) as PagingSource.LoadResult.Page<Int, LightConversationEntity>
                found += page.data.map { it.id }
                key = page.nextKey
            } while (key != null)
            assertEquals(ids.take(70).filterNot { it == ids[12] }, found)
            assertEquals(found.size, found.toSet().size)
            val beta = dao.contextConversations("agent-b", "%Topic%", ContextConversationSort.CREATED_DESC.name, null)
                .load(PagingSource.LoadParams.Refresh(null, 40, false)) as PagingSource.LoadResult.Page<Int, LightConversationEntity>
            assertEquals(ids.takeLast(5).reversed(), beta.data.map { it.id })
            val title = dao.contextConversations(null, "%Topic 07%", ContextConversationSort.TITLE_DESC.name, null)
                .load(PagingSource.LoadParams.Refresh(null, 40, false)) as PagingSource.LoadResult.Page<Int, LightConversationEntity>
            assertEquals(ids.takeLast(5).reversed(), title.data.map { it.id })
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
