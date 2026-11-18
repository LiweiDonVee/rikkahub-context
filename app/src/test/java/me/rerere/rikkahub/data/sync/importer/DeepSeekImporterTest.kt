package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationTree
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `imports text reasoning searches and attachments with session metadata`() = runBlocking {
        val image = byteArrayOf(1, 2, 3)
        val backup = archive(
            session(
                """
                {"id":1,"parent_id":null,"role":"USER","fragments":[
                  {"type":"FILE","files":[{"file_name":"图片.png","export_path":"chat_sessions/s_files/图片.png","is_image":true}]},
                  {"type":"REQUEST","content":"Describe this"}
                ]},
                {"id":2,"parent_id":1,"role":"ASSISTANT","inserted_at":"2026-11-14T05:56:00Z","fragments":[
                  {"type":"THINK","content":"Thinking","elapsed_secs":1.5},
                  {"type":"TOOL_SEARCH","status":"FINISHED","queries":["query"],"results":[{"url":"https://example.com","title":"Source"}]},
                  {"type":"TOOL_OPEN","status":"TIMEOUT","url":"https://example.com","title":"Source"},
                  {"type":"RESPONSE","content":"Answer"},
                  {"type":"TIP","content":"Notice"}
                ]}
                """.trimIndent(),
                current = 2,
            ),
            attachments = mapOf("chat_sessions/s_files/图片.png" to image),
        )
        val conversations = mutableListOf<Conversation>()
        val attachments = mutableListOf<DeepSeekAttachment>()
        val assistant = Uuid.random()
        val result = DeepSeekImporter.importStreaming(
            backup,
            assistant,
            saveAttachment = {
                attachments += it
                "file:///image.png"
            },
            onConversation = {
                conversations += it.activeConversation()
                true
            },
        )
        assertEquals(DeepSeekImportResult(1, 0, 0, 0, 2), result)
        assertArrayEquals(image, attachments.single().bytes)
        assertEquals("图片.png", attachments.single().fileName)
        assertEquals("image/png", attachments.single().mimeType)
        val conversation = conversations.single()
        assertEquals(assistant, conversation.assistantId)
        assertEquals("Test session", conversation.title)
        assertTrue(conversation.isPinned)
        assertEquals(Instant.parse("2026-11-14T05:55:54Z"), conversation.createAt)
        assertEquals(Instant.parse("2026-11-14T05:56:01Z"), conversation.updateAt)
        assertEquals(MessageRole.USER, conversation.currentMessages.first().role)
        assertEquals(
            "file:///image.png",
            (conversation.currentMessages.first().parts.first() as UIMessagePart.Image).url
        )
        val answer = conversation.currentMessages.last()
        val reasoning = answer.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("Thinking", reasoning.reasoning)
        assertEquals(1500, (reasoning.finishedAt!! - reasoning.createdAt).inWholeMilliseconds.toInt())
        assertEquals(listOf("Answer", "Notice"), answer.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertEquals(1, answer.annotations.size)
        val tools = answer.parts.filterIsInstance<UIMessagePart.ServerTool>()
        assertEquals(listOf(ServerToolStatus.COMPLETED, ServerToolStatus.FAILED), tools.map { it.status })
        assertTrue(tools.all { it.isFinished })
        assertTrue(answer.getTools().isEmpty())
    }

    @Test
    fun `keeps every parent link and selects the source path`() = runBlocking {
        val backup = archive(session(
            listOf(
                message(1, null, "USER", "Question"),
                message(2, 1, "ASSISTANT", "Old answer"),
                message(3, 2, "USER", "Old followup"),
                message(4, 3, "ASSISTANT", "Old conclusion"),
                message(5, 1, "ASSISTANT", "Current answer"),
                message(6, 5, "USER", "Current followup"),
                message(7, 6, "ASSISTANT", "Current conclusion"),
                message(8, 1, "ASSISTANT", "Leaf alternative"),
            ).joinToString(),
            current = 7,
        ))
        val first = mutableListOf<DeepSeekHistory>()
        val assistant = Uuid.random()
        DeepSeekImporter.importStreaming(backup, assistant, onConversation = {
            first += it
            true
        })
        val history = first.single()
        assertEquals(
            listOf("Question", "Current answer", "Current followup", "Current conclusion"),
            history.activeConversation().currentMessages.map { it.toText() },
        )
        assertEquals("Test session", history.conversation.title)
        assertEquals(8, history.links.map { it.nodeId }.distinct().size)
        val tree = ConversationTree(history.links, emptyList())
        val oldTip = history.links.single { it.label == "Old conclusion" }.id
        assertEquals(
            listOf("Question", "Old answer", "Old followup", "Old conclusion"),
            tree.path(oldTip).map { it.label }
        )
        val second = mutableListOf<DeepSeekHistory>()
        DeepSeekImporter.importStreaming(backup, assistant, onConversation = {
            second += it
            true
        })
        assertEquals(first, second)

        val skipped = DeepSeekImporter.importStreaming(
            backup,
            assistant,
            knownNodes = { history.conversation.messageNodes.mapTo(mutableSetOf()) { it.id } },
            saveAttachment = { error("Already imported messages must not save attachments") },
            onConversation = { repeated ->
                assertEquals(emptyList<Any>(), repeated.conversation.messageNodes)
                assertEquals(history.links.map { it.id }, repeated.links.map { it.id })
                false
            },
        )
        assertEquals(1, skipped.skippedExistingConversations)
        assertEquals(0, skipped.addedMessages)
    }

    @Test
    fun `keeps descendants and saves attachments outside the selected branch`() = runBlocking {
        val backup = archive(session(
            listOf(
                message(1, null, "USER", "Question"),
                message(2, 1, "ASSISTANT", "Selected answer"),
                message(3, 2, "USER", "Unselected continuation"),
                """{"id":4,"parent_id":1,"role":"USER","fragments":[{"type":"FILE","files":[
                    {"file_name":"old.png","export_path":"old.png","is_image":true}
                ]}]}""",
            ).joinToString(),
            current = 2,
        ), attachments = mapOf("old.png" to byteArrayOf(1)))
        val histories = mutableListOf<DeepSeekHistory>()
        var attachments = 0
        val result = DeepSeekImporter.importStreaming(
            backup,
            Uuid.random(),
            saveAttachment = {
                attachments++
                "file:///alternate.png"
            },
            onConversation = {
                histories += it
                true
            },
        )
        assertEquals(DeepSeekImportResult(1, 0, 0, 0, 4), result)
        val history = histories.single()
        assertEquals(1, attachments)
        assertEquals(
            listOf("Question", "Selected answer"),
            history.activeConversation().currentMessages.map { it.toText() }
        )
        assertEquals(4, history.conversation.messageNodes.size)
        assertTrue(history.conversation.currentMessages.any { msg -> msg.parts.any { it is UIMessagePart.Image } })
    }

    @Test
    fun `retains missing file names and imports documents`() = runBlocking {
        val backup = archive(session(
            """
            {"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"FILE","files":[
              {"file_name":"missing.pdf","export_path":null,"is_image":false},
              {"file_name":"not-found.pdf","export_path":"missing.pdf","is_image":false},
              {"file_name":"notes.txt","export_path":"chat_sessions/s_files/notes.txt","is_image":false}
            ]}]}
            """.trimIndent(),
            current = 1,
        ), attachments = mapOf("chat_sessions/s_files/notes.txt" to "Notes".encodeToByteArray()))
        val conversations = mutableListOf<Conversation>()
        val result = DeepSeekImporter.importStreaming(
            backup,
            Uuid.random(),
            saveAttachment = { "file:///notes.txt" },
            onConversation = {
                conversations += it.activeConversation()
                true
            },
        )
        assertEquals(2, result.missingAttachments)
        val message = conversations.single().currentMessages.single()
        assertTrue(message.toText().contains("missing.pdf"))
        assertTrue(message.toText().contains("not-found.pdf"))
        assertEquals("notes.txt", message.parts.filterIsInstance<UIMessagePart.Document>().single().fileName)
    }

    @Test
    fun `rejects unsupported formats and invalid parent graphs`() {
        val invalidVersion = archive(session(message(1, null, "USER", "Hello"), 1), version = 2)
        val cyclic = archive(session(message(1, 2, "USER", "One") + "," + message(2, 1, "ASSISTANT", "Two"), 2))
        val missingParent = archive(session(message(1, 99, "USER", "One"), 1))
        val missingCurrent = archive(session(message(1, null, "USER", "One"), 99))
        listOf(invalidVersion, cyclic, missingCurrent).forEach { file ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    DeepSeekImporter.importStreaming(file, Uuid.random(), onConversation = { error("Must not save") })
                }
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                DeepSeekImporter.importStreaming(
                    missingParent,
                    Uuid.random(),
                    onConversation = { error("Must not save") }
                )
            }
        }
    }

    @Test
    fun `skips empty sessions`() = runBlocking {
        val result = DeepSeekImporter.importStreaming(
            archive(session("", 0)),
            Uuid.random(),
            onConversation = { error("Must not save") },
        )
        assertEquals(DeepSeekImportResult(0, 0, 1, 0), result)
    }

    private fun DeepSeekHistory.activeConversation(): Conversation {
        val byId = conversation.messageNodes.associateBy { it.id.toString() }
        return conversation.copy(
            messageNodes = ConversationTree(links, emptyList()).path(selectedTip).map { byId.getValue(it.nodeId) }
        )
    }

    private fun message(id: Int, parent: Int?, role: String, text: String): String =
        """{"id":$id,"parent_id":$parent,"role":"$role","fragments":[{"type":"RESPONSE","content":"$text"}]}"""

    private fun session(messages: String, current: Int): String = """
        {"id":"s","title":"Test session","pinned":true,"current_message_id":$current,
         "inserted_at":"2026-11-14T05:55:54Z","updated_at":"2026-11-14T05:56:01Z","messages":[$messages]}
    """.trimIndent()

    private fun archive(session: String, version: Int = 3, attachments: Map<String, ByteArray> = emptyMap()): File {
        val file = temporaryFolder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "manifest.json" to """{"export_format_version":$version}""".encodeToByteArray(),
                "chat_sessions/s.json" to session.encodeToByteArray(),
            ) + attachments
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
