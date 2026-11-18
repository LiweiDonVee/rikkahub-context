package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ConversationBranchEntity
import me.rerere.rikkahub.data.db.entity.ConversationPathEntity

@Dao
interface ConversationBranchDAO {
    @Query("SELECT * FROM conversation_branch WHERE conversation_id = :conversationId AND active = 1 LIMIT 1")
    suspend fun active(conversationId: String): ConversationBranchEntity?

    @Query("SELECT * FROM conversation_branch WHERE conversation_id = :conversationId ORDER BY rowid")
    suspend fun branches(conversationId: String): List<ConversationBranchEntity>

    @Query("SELECT * FROM conversation_path WHERE conversation_id = :conversationId ORDER BY rowid")
    suspend fun links(conversationId: String): List<ConversationPathEntity>

    @Query(
"""
        WITH RECURSIVE ancestry(id, depth) AS (
            SELECT id, 0 FROM conversation_path WHERE id = :tipId
            UNION ALL
            SELECT link.parent_id, depth + 1 FROM ancestry
            JOIN conversation_path AS link ON link.id = ancestry.id
            WHERE link.parent_id IS NOT NULL
        )
        SELECT link.* FROM ancestry JOIN conversation_path AS link ON link.id = ancestry.id
        ORDER BY ancestry.depth DESC
"""
    )
    suspend fun path(tipId: String?): List<ConversationPathEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<ConversationPathEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBranches(branches: List<ConversationBranchEntity>)

    @Update
    suspend fun update(branch: ConversationBranchEntity)

    @Query("UPDATE conversation_path SET label = :label WHERE id = :id AND label != :label")
    suspend fun updateLabel(id: String, label: String)

    @Query("DELETE FROM conversation_path WHERE id IN (:ids)")
    suspend fun deleteLinks(ids: List<String>)
}
