package me.rerere.rikkahub.data.sync.importer

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File
import kotlin.uuid.Uuid

/** Shared by the Backup and Quick Start imports. */
class DeepSeekMigrator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) {
    private val importMutex = Mutex()

    suspend fun import(uri: Uri): DeepSeekImportResult = withContext(Dispatchers.IO) {
        // ZipFile 需要随机读取，先把文档复制成临时文件
        val tempFile = File.createTempFile("temp_deepseek_", ".zip", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)) {
                context.getString(R.string.backup_page_import_deepseek_read_error)
            }.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            import(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun import(file: File): DeepSeekImportResult = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val settings = settingsStore.awaitLoaded()
            val assistant = settings.assistants.firstOrNull { it.id == DEEPSEEK_ASSISTANT_ID }
                ?: Assistant(id = DEEPSEEK_ASSISTANT_ID, name = context.getString(R.string.deepseek_archive_assistant))
            val importSettings = settings.copy(assistants =
                settings.assistants.filterNot { it.id == assistant.id } + assistant)
            val pendingFiles = mutableListOf<Uri>()
            var ready = settings.assistants.any { it.id == assistant.id }

            try {
                DeepSeekImporter.importStreaming(
                    file = file,
                    assistantId = assistant.id,
                    knownNodes = conversationRepository::knownImportNodes,
                    formatMissingAttachment = { name ->
                        context.getString(R.string.backup_page_import_deepseek_missing_attachment, name)
                    },
                    saveAttachment = { resource ->
                        val entity = filesManager.saveUploadFromBytes(
                            bytes = resource.bytes,
                            displayName = resource.fileName,
                            mimeType = resource.mimeType,
                        )
                        filesManager.getFile(entity).toUri().also { pendingFiles.add(it) }.toString()
                    },
                    onConversation = { history ->
                        if (!ready) {
                            settingsStore.update { latest ->
                                if (latest.assistants.any { it.id == assistant.id }) {
                                    latest
                                } else {
                                    latest.copy(assistants = latest.assistants + assistant)
                                }
                            }
                            ready = true
                        }
                        conversationRepository.importConversationTree(
                            history.conversation.bindConfig(importSettings),
                            history.links,
                            history.selectedTip,
                        ).also { pendingFiles.clear() }
                    },
                )
            } finally {
                if (pendingFiles.isNotEmpty()) {
                    withContext(NonCancellable) {
                        filesManager.deleteChatFiles(
                            pendingFiles.filterNot { conversationRepository.hasFileReference(it.toString()) }
                        )
                    }
                }
            }
        }
    }

    companion object {
        val DEEPSEEK_ASSISTANT_ID = Uuid.parse("de455ee5-b8c4-4737-bfa1-1f43dbf6d625")
    }
}
