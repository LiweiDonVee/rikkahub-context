package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_branch",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversation_id", "active")],
)
data class ConversationBranchEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("tip_id") val tipId: String?,
    val active: Boolean,
)

@Entity(
    tableName = "conversation_path",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversation_id")],
)
data class ConversationPathEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("parent_id") val parentId: String?,
    @ColumnInfo("node_id") val nodeId: String,
    @ColumnInfo("message_id") val messageId: String,
    val label: String,
    val role: String,
)
