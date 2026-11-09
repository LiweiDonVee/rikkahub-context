package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "context_request",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversation_id"), Index("response_message_id"), Index("run_id")],
)
data class ContextRequestEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "response_message_id") val responseMessageId: String,
    val protocol: String,
    val endpoint: String,
    val model: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val selection: String,
    val sources: String,
    val exclusions: String,
    @ColumnInfo(name = "raw_edited") val rawEdited: Boolean,
    @ColumnInfo(name = "redacted_paths") val redactedPaths: String,
    @ColumnInfo(name = "body_sha256") val bodySha256: String,
    @ColumnInfo(name = "transport_status") val transportStatus: String = "prepared",
    @ColumnInfo(name = "generation_status") val generationStatus: String = "running",
    val attempts: Int = 0,
    @ColumnInfo(name = "response_code") val responseCode: Int? = null,
    @ColumnInfo(name = "failure_type") val failureType: String? = null,
)

/** Chunked JSON avoids Android's CursorWindow size limit for large contexts/tool results. */
@Entity(
    tableName = "context_request_body",
    primaryKeys = ["request_id", "chunk_index"],
    foreignKeys = [ForeignKey(
        entity = ContextRequestEntity::class,
        parentColumns = ["id"],
        childColumns = ["request_id"],
        onDelete = ForeignKey.CASCADE
    )],
)
data class ContextRequestBodyEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
)

/** One normalized final/partial reply per run, shared by its physical requests. */
@Entity(
    tableName = "context_response_body",
    primaryKeys = ["run_id", "chunk_index"],
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversation_id")],
)
data class ContextResponseBodyEntity(
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
    @ColumnInfo(name = "file_urls") val fileUrls: String = "[]",
)
