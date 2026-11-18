package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.core.net.toUri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationBranchEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.ConversationPathEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ContextConversationSort
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    private val branchDAO get() = database.conversationBranchDao()

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl)) ||
            database.contextRequestDao().hasResponseFileReference(JsonInstant.encodeToString(fileUrl))

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun getTitlesOfAssistant(assistantId: Uuid): Set<String> =
        conversationDAO.getTitlesOfAssistant(assistantId.toString()).toSet()

    fun contextConversations(assistantId: Uuid?, query: String, sort: ContextConversationSort, excludeId: Uuid?): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = INITIAL_LOAD_SIZE, enablePlaceholders = false),
        pagingSourceFactory = {
            val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            conversationDAO.contextConversations(assistantId?.toString(), "%$escaped%", sort.name, excludeId?.toString())
        },
    ).flow.map { page -> page.map(::conversationSummaryToConversation) }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getUnfiledConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(
        folderId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getConversationsOfFolderPaging(
                folderId = folderId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? = database.withTransaction {
        val entity = conversationDAO.getConversationById(uuid.toString()) ?: return@withTransaction null
        conversationEntityToConversation(entity, loadMessageNodes(entity.id))
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(conversationToConversationEntity(conversation))
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            messageFtsManager.appendNodes(conversation.id.toString(), conversation.messageNodes)
        }
    }

    suspend fun updateConversation(conversation: Conversation, previous: Conversation? = null) {
        database.withTransaction {
            val id = conversation.id.toString()
            val old = previous ?: getConversationById(conversation.id)
            val oldNodes = old?.messageNodes.orEmpty()
            val indexed = oldNodes.withIndex().associateBy { it.value.id }
            val active = branchDAO.active(id)
            if (old == null || conversationToConversationEntity(old) != conversationToConversationEntity(conversation)) {
                conversationDAO.update(conversationToConversationEntity(conversation))
            }

            val writes = mutableListOf<MessageNodeEntity>()
            val indexWrites = mutableListOf<MessageNode>()
            val indexRemovals = mutableSetOf<String>()
            val currentIds = mutableSetOf<Uuid>()
            conversation.messageNodes.forEachIndexed { position, node ->
                currentIds.add(node.id)
                val before = indexed[node.id]
                val changedMessages = before?.value?.messages != node.messages
                if (changedMessages || before?.value?.selectIndex != node.selectIndex) {
                    writes.add(node.toEntity(id, position))
                } else if (active == null && before != null && before.index != position) {
                    messageNodeDAO.updatePosition(node.id.toString(), position)
                }
                if (changedMessages) {
                    indexWrites.add(node)
                    if (before != null) indexRemovals.add(node.id.toString())
                }
            }
            if (writes.isNotEmpty()) messageNodeDAO.insertAll(writes)

            val removed = if (active == null) {
                oldNodes.filter { it.id !in currentIds }.map { it.id.toString() }
            } else if (oldNodes !== conversation.messageNodes) {
                updateBranchPath(active, conversation.messageNodes)
            } else {
                emptyList()
            }
            removed.chunked(400).forEach { messageNodeDAO.deleteByIds(it) }
            if (removed.isNotEmpty()) deleteNodeFavorites(conversation.id, removed.map { Uuid.parse(it) })
            indexRemovals.addAll(removed)
            if (indexWrites.isNotEmpty() || indexRemovals.isNotEmpty()) {
                messageFtsManager.replaceNodes(id, indexRemovals, indexWrites)
            }
        }
    }

    suspend fun getConversationTree(conversationId: Uuid): ConversationTree = database.withTransaction {
        val id = conversationId.toString()
        ConversationTree(branchDAO.links(id), branchDAO.branches(id))
    }

    suspend fun createConversationBranch(conversationId: Uuid, messageId: Uuid): Conversation =
        database.withTransaction {
            val conversation = checkNotNull(getConversationById(conversationId))
            val nodeIndex = conversation.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
            require(nodeIndex >= 0) { "Message not found in the active branch" }
            val id = conversationId.toString()
            val previous = branchDAO.active(id) ?: seedBranch(id, conversation.messageNodes)
            val selected = conversation.messageNodes[nodeIndex]
            val prefix = branchDAO.path(previous.tipId).take(nodeIndex + 1)
            val tip = prefix.last()
            val selectedTip = if (tip.messageId == messageId.toString()) {
                tip
            } else {
                val version = selected.messages.first { it.id == messageId }
                pathLink(id, tip.parentId, selected.id.toString(), version).also {
                    branchDAO.insertLinks(listOf(it))
                }
            }
            branchDAO.update(previous.copy(active = false))
            branchDAO.insertBranches(listOf(ConversationBranchEntity(Uuid.random().toString(), id, selectedTip.id, true)))
            conversationDAO.updateChatSuggestions(id, "[]")
            checkNotNull(getConversationById(conversationId))
        }

    suspend fun switchConversationBranch(conversationId: Uuid, branchId: String): Conversation =
        database.withTransaction {
            val id = conversationId.toString()
            val branches = branchDAO.branches(id)
            val selected = branches.firstOrNull { it.id == branchId } ?: error("Branch not found")
            if (!selected.active) {
                branches.firstOrNull { it.active }?.let { branchDAO.update(it.copy(active = false)) }
                branchDAO.update(selected.copy(active = true))
                conversationDAO.updateChatSuggestions(id, "[]")
            }
            checkNotNull(getConversationById(conversationId))
        }

    suspend fun getBranchConversation(conversationId: Uuid, branchId: String): Conversation? =
        database.withTransaction {
            val entity = conversationDAO.getConversationById(conversationId.toString()) ?: return@withTransaction null
            val branch = branchDAO.branches(entity.id).firstOrNull { it.id == branchId } ?: return@withTransaction null
            conversationEntityToConversation(entity, loadPathNodes(entity.id, branch.tipId))
        }

    suspend fun knownImportNodes(conversationId: Uuid): Set<Uuid> =
        messageNodeDAO.getIds(conversationId.toString()).mapTo(mutableSetOf(), Uuid::parse)

    /** Add source paths without replacing locally edited nodes or an already open branch. */
    suspend fun importConversationTree(
        conversation: Conversation,
        sourceLinks: List<ConversationPathEntity>,
        sourceTipId: String?,
    ): Boolean = database.withTransaction {
        val id = conversation.id.toString()
        val existing = conversationDAO.getConversationById(id)
        val oldBranches = branchDAO.branches(id)
        val oldLinks = branchDAO.links(id).mapTo(mutableSetOf()) { it.id }
        val ids = messageNodeDAO.getIds(id).toHashSet()
        val newNodes = conversation.messageNodes.filter { it.id.toString() !in ids }
        val newLinks = sourceLinks.filter { it.id !in oldLinks }
        val parentIds = sourceLinks.mapNotNullTo(mutableSetOf()) { it.parentId }
        val tips = (listOfNotNull(sourceTipId) + sourceLinks.filter { it.id !in parentIds }.map { it.id }).distinct()
        val branchIds = oldBranches.mapTo(mutableSetOf()) { it.id }
        val newBranches = tips.filter { "deepseek:$it" !in branchIds }.map { tip ->
            ConversationBranchEntity("deepseek:$tip", id, tip, existing == null && tip == tips.first())
        }
        if (existing != null && oldBranches.isNotEmpty() && newNodes.isEmpty() &&
            newLinks.isEmpty() && newBranches.isEmpty()) return@withTransaction false

        val oldPath = if (existing != null && oldBranches.isEmpty()) loadMessageNodes(id) else emptyList()
        if (existing == null) {
            conversationDAO.insert(conversationToConversationEntity(conversation))
        } else if (oldBranches.isEmpty()) {
            // An upstream current-path import is identified by the same deterministic session id.
            conversationDAO.updateAssistantId(id, conversation.assistantId.toString())
        }

        saveMessageNodes(id, newNodes)
        val localNodes = oldPath.associateBy { it.id.toString() }
        branchDAO.insertLinks(newLinks.map { link ->
            val local = localNodes[link.nodeId]?.messages?.firstOrNull { it.id.toString() == link.messageId }
            if (local == null) link else link.copy(label = local.branchLabel())
        })
        branchDAO.insertBranches(newBranches)
        if (existing != null && oldBranches.isEmpty()) {
            val preferred = newBranches.first()
            val path = branchDAO.path(preferred.tipId)
            if (oldPath.size == path.size && oldPath.zip(path).all { (node, link) ->
                    node.id.toString() == link.nodeId && node.currentMessage.id.toString() == link.messageId
                }) {
                branchDAO.update(preferred.copy(active = true))
            } else {
                seedBranch(id, oldPath)
            }
        }
        messageFtsManager.appendNodes(id, newNodes)
        true
    }

    private suspend fun seedBranch(id: String, nodes: List<MessageNode>): ConversationBranchEntity {
        val links = buildPath(id, null, nodes)
        branchDAO.insertLinks(links)
        val branch = ConversationBranchEntity(Uuid.random().toString(), id, links.lastOrNull()?.id, true)
        branchDAO.insertBranches(listOf(branch))
        return branch
    }

    private suspend fun updateBranchPath(branch: ConversationBranchEntity, nodes: List<MessageNode>): List<String> {
        val path = branchDAO.path(branch.tipId)
        var common = 0
        while (common < minOf(path.size, nodes.size)) {
            val link = path[common]
            val node = nodes[common]
            if (link.nodeId != node.id.toString() || link.messageId != node.currentMessage.id.toString()) break
            val label = node.currentMessage.branchLabel()
            if (label != link.label) branchDAO.updateLabel(link.id, label)
            common++
        }
        if (common == path.size && common == nodes.size) return emptyList()
        val parent = path.getOrNull(common - 1)?.id
        val suffix = buildPath(branch.conversationId, parent, nodes.drop(common))
        branchDAO.insertLinks(suffix)
        branchDAO.update(branch.copy(tipId = suffix.lastOrNull()?.id ?: parent))
        if (common == path.size) return emptyList()

        // Retire only the replaced suffix. Other tips can still depend on that ancestry.
        val tree = ConversationTree(branchDAO.links(branch.conversationId), branchDAO.branches(branch.conversationId))
        val retained = tree.reachableLinks()
        val stale = path.drop(common).filter { it.id !in retained }
        stale.map { it.id }.chunked(400).forEach { branchDAO.deleteLinks(it) }
        val retainedNodes = tree.links.filter { it.id in retained }.mapTo(mutableSetOf()) { it.nodeId }
        return stale.map { it.nodeId }.distinct().filter { it !in retainedNodes }
    }

    private fun buildPath(id: String, parentId: String?, nodes: List<MessageNode>): List<ConversationPathEntity> {
        var parent = parentId
        return nodes.map { node ->
            pathLink(id, parent, node.id.toString(), node.currentMessage).also { parent = it.id }
        }
    }

    private fun pathLink(id: String, parent: String?, nodeId: String, message: UIMessage) =
        ConversationPathEntity(
            id = Uuid.random().toString(),
            conversationId = id,
            parentId = parent,
            nodeId = nodeId,
            messageId = message.id.toString(),
            label = message.branchLabel(),
            role = message.role.name,
        )

    suspend fun deleteConversation(conversation: Conversation) {
        val conversationId = conversation.id.toString()
        val allFiles = messageNodeDAO.getIds(conversationId).chunked(64).flatMap { ids ->
            val nodes = messageNodeDAO.getByIds(ids).map { it.toModel(emptySet()) }
            conversation.copy(messageNodes = nodes).files
        }
        val archivedFiles = database.contextRequestDao().responseFileLists(conversationId)
            .flatMap { JsonInstant.decodeFromString<List<String>>(it) }.map { it.toUri() }
        messageFtsManager.deleteConversation(conversationId)
        database.withTransaction {
            // 收藏表和会话没有外键，要单独删
            favoriteDAO.deleteNodeFavoritesOfConversation(conversationId)
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles((allFiles + archivedFiles).distinct().filterNot { hasFileReference(it.toString()) })
    }

    /** 删除这些节点的收藏，节点从会话里移除后调用 */
    suspend fun deleteNodeFavorites(conversationId: Uuid, nodeIds: Collection<Uuid>) {
        nodeIds
            .map { NodeFavoriteAdapter.buildRefKey(conversationId.toString(), it.toString()) }
            .chunked(500)
            .forEach { favoriteDAO.deleteByRefKeys(it) }
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.let { listOf(it.toString()) })

    /** 只在 [assistantIds] 这些助手的对话里搜索 */
    suspend fun searchMessagesOfAssistants(keyword: String, assistantIds: Set<Uuid>) =
        messageFtsManager.search(keyword, MessageSearchSort.RELEVANCE, assistantIds.map { it.toString() })

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = messageNodeDAO.getIds(id).chunked(64).flatMap { ids ->
                messageNodeDAO.getByIds(ids).map { it.toModel(emptySet()) }
            }
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]", // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            config = conversationEntity.config.ifEmpty { null }?.let { JsonInstant.decodeFromString(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = isPinned,
        )
    }

    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
    }

    /**
     * 只更新会话持有的配置（含注入绑定和工作目录），不重写消息节点。
     */
    suspend fun updateConversationConfig(conversation: Conversation) {
        conversationDAO.updateConfig(
            id = conversation.id.toString(),
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    suspend fun updateCustomSystemPrompt(conversationId: Uuid, customSystemPrompt: String?) {
        conversationDAO.updateCustomSystemPrompt(conversationId.toString(), customSystemPrompt ?: "")
    }

    suspend fun updateChatSuggestions(conversationId: Uuid, chatSuggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            id = conversationId.toString(),
            chatSuggestions = JsonInstant.encodeToString(chatSuggestions),
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteIds(conversationId)

        return database.withTransaction {
            branchDAO.active(conversationId)?.let { return@withTransaction loadPathNodes(conversationId, it.tipId) }
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: CancellationException) {
                    // CancellationException 是 IllegalStateException 的子类，必须先重新抛出，否则取消后会在事务内无限循环
                    throw e
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.mapTo(nodes) { it.toModel(favoriteNodeIds) }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun favoriteIds(conversationId: String): Set<String> =
        favoriteDAO.getFavoriteNodeIdsOfConversation(conversationId).toSet()

    private suspend fun loadPathNodes(conversationId: String, tipId: String?): List<MessageNode> {
        val favorites = favoriteIds(conversationId)
        return branchDAO.path(tipId).chunked(64).flatMap { links ->
            val nodes = messageNodeDAO.getByIds(links.map { it.nodeId }).associateBy { it.id }
            links.map { link ->
                val node = checkNotNull(nodes[link.nodeId]) { "Branch path references a missing message" }.toModel(favorites)
                val selected = node.messages.indexOfFirst { it.id.toString() == link.messageId }
                if (selected < 0) node else node.copy(selectIndex = selected)
            }
        }
    }

    private fun MessageNodeEntity.toModel(favoriteNodeIds: Set<String>): MessageNode = MessageNode(
        id = Uuid.parse(id),
        messages = JsonInstant.decodeFromString<List<UIMessage>>(messages),
        selectIndex = selectIndex,
        isFavorite = id in favoriteNodeIds,
    )

    private fun MessageNode.toEntity(conversationId: String, index: Int) = MessageNodeEntity(
        id = id.toString(),
        conversationId = conversationId,
        nodeIndex = index,
        messages = JsonInstant.encodeToString(messages),
        selectIndex = selectIndex,
    )

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        if (nodes.isNotEmpty()) messageNodeDAO.insertAll(nodes.mapIndexed { index, node -> node.toEntity(conversationId, index) })
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
