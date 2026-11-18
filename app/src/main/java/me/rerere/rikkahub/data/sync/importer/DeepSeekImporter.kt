package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.db.entity.ConversationPathEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.branchLabel
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.time.Instant as KotlinInstant

/** DeepSeek export v3: one JSON file per session, with optional binary attachments. */
object DeepSeekImporter {
    private const val MAX_ENTRY_SIZE = 64 * 1024 * 1024

    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        knownNodes: suspend (Uuid) -> Set<Uuid> = { emptySet() },
        saveAttachment: suspend (DeepSeekAttachment) -> String? = { null },
        formatMissingAttachment: (
            String
        ) -> String = { "[Attachment missing from the export or unable to be imported: $it]" },
        onConversation: suspend (DeepSeekHistory) -> Boolean,
    ): DeepSeekImportResult {
        var imported = 0
        var existing = 0
        var empty = 0
        var missingAttachments = 0
        var addedMessages = 0
        val savedFiles = mutableMapOf<String, String?>()
        ZipFile(file).use { zip ->
            val manifest = zip.readObject("manifest.json")
            require((manifest["export_format_version"] as? JsonPrimitive)?.intOrNull == 3) {
                "Unsupported DeepSeek export format (expected version 3)"
            }
            val entries = zip.entries().asSequence().filter {
                !it.isDirectory && it.name.startsWith("chat_sessions/") &&
                    it.name.removePrefix("chat_sessions/").let { name -> '/' !in name && name.endsWith(".json") }
            }.toList()
            require(entries.isNotEmpty()) { "No chat sessions found in DeepSeek export" }

            entries.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val session = zip.readObject(entry.name)
                val sessionId = session["id"].text?.takeIf { it.isNotBlank() }
                    ?: error("DeepSeek session has no id: ${entry.name}")
                val rawMessages = session["messages"] as? JsonArray
                    ?: error("DeepSeek session has no messages: ${entry.name}")
                val messages = rawMessages.map { element ->
                    element as? JsonObject ?: error("Invalid DeepSeek message")
                }.associateBy { it["id"].text ?: error("DeepSeek message has no id") }
                require(messages.size == rawMessages.size) { "Duplicate DeepSeek message id" }
                if (messages.isEmpty()) {
                    empty++
                    return@forEach
                }
                val createdAt = session["inserted_at"].instant ?: Instant.EPOCH
                val updatedAt = session["updated_at"].instant ?: createdAt

                suspend fun parseMessage(id: String): UIMessage {
                    val message = messages.getValue(id)
                    val role = when (message["role"].text) {
                        "USER" -> MessageRole.USER
                        "ASSISTANT" -> MessageRole.ASSISTANT
                        "SYSTEM" -> MessageRole.SYSTEM
                        else -> error("Unsupported DeepSeek message role")
                    }
                    val timestamp = message["inserted_at"].instant ?: createdAt
                    val parts = mutableListOf<UIMessagePart>()
                    val citations = mutableListOf<UIMessageAnnotation.UrlCitation>()
                    (message["fragments"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
                        val fragment = element as? JsonObject ?: return@forEachIndexed
                        when (val type = fragment["type"].text) {
                            "THINK" -> fragment["content"].text?.takeIf { it.isNotBlank() }?.let { content ->
                                val start = KotlinInstant.fromEpochMilliseconds(timestamp.toEpochMilli())
                                val seconds = (fragment["elapsed_secs"] as? JsonPrimitive)?.doubleOrNull
                                    ?.takeIf { it.isFinite() && it >= 0 }
                                parts += UIMessagePart.Reasoning(
                                    reasoning = content,
                                    createdAt = start,
                                    finishedAt = start + (seconds ?: 0.0).seconds,
                                )
                            }
                            "FILE" -> (fragment["files"] as? JsonArray).orEmpty().forEach { fileElement ->
                                val attachment = fileElement as? JsonObject ?: return@forEach
                                val path = attachment["export_path"].text
                                val name = attachment["file_name"].text ?: "attachment"
                                val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                                val url = if (path.isNullOrBlank()) {
                                    null
                                } else {
                                    if (!savedFiles.containsKey(path)) {
                                        val binary = zip.getEntry(path)?.takeUnless { it.isDirectory }
                                        savedFiles[path] = binary?.let {
                                            saveAttachment(DeepSeekAttachment(name, mime, zip.readBytes(it)))
                                        }
                                    }
                                    savedFiles[path]
                                }
                                if (url.isNullOrBlank()) {
                                    missingAttachments++
                                    parts += UIMessagePart.Text(formatMissingAttachment(name), metadata = attachment)
                                } else if ((attachment["is_image"] as? JsonPrimitive)?.booleanOrNull == true) {
                                    parts += UIMessagePart.Image(url)
                                } else {
                                    parts += UIMessagePart.Document(url = url, fileName = name, mime = mime)
                                }
                            }
                            "TOOL_SEARCH", "TOOL_OPEN", "TOOL_IMAGE_SEARCH", "TOOL_FIND", "READ_LINK" -> {
                                parts += UIMessagePart.ServerTool(
                                    toolCallId = "deepseek:$sessionId:$id:$index",
                                    toolName = type.lowercase().removePrefix("tool_"),
                                    input = JsonObject(fragment.filterKeys { it == "queries" || it == "url" }),
                                    output = fragment,
                                    metadata = ServerToolMetadata(
                                        protocol = ServerToolProtocol.DEEPSEEK_EXPORT
                                    ).toMetadata(),
                                    status = if (fragment["status"].text == "FINISHED") {
                                        ServerToolStatus.COMPLETED
                                    } else {
                                        ServerToolStatus.FAILED
                                    },
                                )
                                val sources = if (type == "TOOL_OPEN" || type == "READ_LINK") {
                                    listOf(fragment)
                                } else {
                                    (fragment["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                                }
                                sources.forEach { source ->
                                    source["url"].text?.takeIf { it.isNotBlank() }?.let { url ->
                                        citations += UIMessageAnnotation.UrlCitation(
                                            title = source["title"].text?.takeIf { it.isNotBlank() } ?: url,
                                            url = url,
                                        )
                                    }
                                }
                            }
                            else -> fragment["content"].text?.takeIf { it.isNotBlank() }?.let {
                                // Includes REQUEST, RESPONSE, CONTENT_RISK, TIP and TEMPLATE_RESPONSE.
                                parts += UIMessagePart.Text(it)
                            }
                        }
                    }
                    return UIMessage(
                        role = role,
                        parts = parts,
                        annotations = citations.distinctBy { citation -> citation.url },
                        createdAt = KotlinInstant.fromEpochMilliseconds(timestamp.toEpochMilli())
                            .toLocalDateTime(TimeZone.currentSystemDefault()),
                    )
                }

                val parents = messages.mapValues { (_, message) -> message["parent_id"].text }
                val current = session["current_message_id"].text
                require(current == null || current in parents) { "DeepSeek current message is missing" }
                validateParents(parents)
                val identity = "deepseek:session:$sessionId"
                val conversationId = stableUuid(identity)
                val known = knownNodes(conversationId)
                val nodeIds = messages.keys.associateWith { stableUuid("$identity:node:$it") }
                val nodes = mutableListOf<MessageNode>()
                val links = messages.map { (id, message) ->
                    currentCoroutineContext().ensureActive()
                    val nodeId = nodeIds.getValue(id)
                    val messageId = stableUuid("$identity:message:$id")
                    val parsed = if (nodeId in known) null else parseMessage(id).copy(id = messageId)
                    if (parsed != null) nodes.add(MessageNode(id = nodeId, messages = listOf(parsed)))
                    ConversationPathEntity(
                        id = nodeId.toString(),
                        conversationId = conversationId.toString(),
                        parentId = parents.getValue(id)?.let { nodeIds.getValue(it).toString() },
                        nodeId = nodeId.toString(),
                        messageId = messageId.toString(),
                        label = parsed?.branchLabel().orEmpty(),
                        role = message["role"].text.orEmpty(),
                    )
                }
                val history = DeepSeekHistory(
                    conversation = Conversation(
                        id = conversationId,
                        assistantId = assistantId,
                        title = session["title"].text?.takeIf { it.isNotBlank() } ?: "DeepSeek",
                        messageNodes = nodes,
                        isPinned = (session["pinned"] as? JsonPrimitive)?.booleanOrNull == true,
                        createAt = createdAt,
                        updateAt = updatedAt,
                    ),
                    links = links,
                    selectedTip = current?.let { nodeIds.getValue(it).toString() },
                )
                if (onConversation(history)) {
                    imported++
                    addedMessages += nodes.size
                } else {
                    existing++
                }
            }
        }
        return DeepSeekImportResult(imported, existing, empty, missingAttachments, addedMessages)
    }

    private fun validateParents(parents: Map<String, String?>) {
        val validated = mutableSetOf<String>()
        parents.keys.forEach { tip ->
            val seen = mutableSetOf<String>()
            var id: String? = tip
            while (id != null && id !in validated) {
                check(id in parents) { "DeepSeek message parent is missing" }
                require(seen.add(id)) { "DeepSeek messages contain a cycle" }
                id = parents.getValue(id)
            }
            validated.addAll(seen)
        }
    }

    private fun ZipFile.readObject(path: String): JsonObject {
        val entry = getEntry(path) ?: error("Invalid DeepSeek export: $path not found")
        return JsonInstant.parseToJsonElement(readBytes(entry).decodeToString()) as? JsonObject
            ?: error("Invalid DeepSeek export: $path is not an object")
    }

    private fun ZipFile.readBytes(entry: ZipEntry): ByteArray {
        require(entry.size <= MAX_ENTRY_SIZE) { "DeepSeek archive entry is too large: ${entry.name}" }
        return getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ENTRY_SIZE) { "DeepSeek archive entry is too large: ${entry.name}" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private val JsonElement?.text: String?
        get() = (this as? JsonPrimitive)?.contentOrNull

    private val JsonElement?.instant: Instant?
        get() = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun stableUuid(value: String): Uuid = Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray()).toString())
}

data class DeepSeekAttachment(val fileName: String, val mimeType: String, val bytes: ByteArray)

data class DeepSeekHistory(
    val conversation: Conversation,
    val links: List<ConversationPathEntity>,
    val selectedTip: String?,
)

data class DeepSeekImportResult(
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedEmptyConversations: Int,
    val missingAttachments: Int,
    val addedMessages: Int = 0,
)
