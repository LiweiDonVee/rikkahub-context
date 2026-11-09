package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ContextRequestBodyEntity
import me.rerere.rikkahub.data.db.entity.ContextRequestEntity
import me.rerere.rikkahub.data.db.entity.ContextResponseBodyEntity

@Dao
abstract class ContextRequestDAO {
    @Insert abstract suspend fun insert(request: ContextRequestEntity)

    @Insert abstract suspend fun insertChunks(chunks: List<ContextRequestBodyEntity>)

    @Query("SELECT * FROM context_request WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC")
    abstract fun observe(conversationId: String): Flow<List<ContextRequestEntity>>

    @Query("SELECT * FROM context_request WHERE id = :id")
    abstract suspend fun get(id: String): ContextRequestEntity?

    @Query("SELECT content FROM context_request_body WHERE request_id = :id ORDER BY chunk_index")
    abstract suspend fun bodyChunks(id: String): List<String>

    @Query("SELECT content FROM context_response_body WHERE run_id = :runId ORDER BY chunk_index")
    abstract suspend fun responseChunks(runId: String): List<String>

    @Query("SELECT COUNT(*) FROM context_request WHERE run_id = :runId")
    abstract suspend fun requestCount(runId: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM context_response_body WHERE chunk_index = 0 AND instr(file_urls, :encodedFileUrl) > 0)"
    )
    abstract suspend fun hasResponseFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT file_urls FROM context_response_body WHERE conversation_id = :conversationId AND chunk_index = 0")
    abstract suspend fun responseFileLists(conversationId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertResponseChunks(chunks: List<ContextResponseBodyEntity>)

    @Transaction
    open suspend fun finishWithResponse(
        runId: String,
        conversationId: String,
        status: String,
        response: String?,
        fileUrls: String = "[]"
    ) {
        if (response != null && requestCount(runId) > 0 && responseChunks(runId).isEmpty()) {
            insertResponseChunks(response.chunked(64 * 1024).mapIndexed { index, content ->
                ContextResponseBodyEntity(runId, conversationId, index, content, if (index == 0) fileUrls else "[]")
            })
        }
        finish(runId, status)
    }

    @Query(
        "UPDATE context_request SET transport_status = :status, attempts = attempts + :increment, response_code = :code, failure_type = :failure WHERE id = :id"
    )
    abstract suspend fun transport(
        id: String,
        status: String,
        increment: Int = 0,
        code: Int? = null,
        failure: String? = null
    )

    @Query(
        "UPDATE context_request SET generation_status = :status WHERE run_id = :runId AND generation_status = 'running'"
    )
    abstract suspend fun finish(runId: String, status: String)

    @Query("UPDATE context_request SET response_message_id = :visibleId WHERE response_message_id = :temporaryId")
    abstract suspend fun reassociate(temporaryId: String, visibleId: String)

    @Query("DELETE FROM context_request WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Transaction
    open suspend fun save(request: ContextRequestEntity, body: String) {
        insert(request)
        insertChunks(
            body.chunked(
                64 * 1024
            ).mapIndexed { index, content -> ContextRequestBodyEntity(request.id, index, content) }
        )
    }
}
