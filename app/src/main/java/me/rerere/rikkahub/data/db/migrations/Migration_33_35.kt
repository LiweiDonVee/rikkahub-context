package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Fork 33 used the same version number as upstream 33. Both entry paths converge here.
val Migration_33_35 = object : Migration(33, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_message_node_conversation_id_node_index " +
                "ON message_node(conversation_id, node_index)"
        )
        db.execSQL("DROP INDEX IF EXISTS index_message_node_conversation_id")
        db.execSQL("ALTER TABLE workspaces ADD COLUMN storage_mounts TEXT NOT NULL DEFAULT '[]'")
        createContextAndBranchTables(db)
    }
}

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) = createContextAndBranchTables(db)
}

private fun createContextAndBranchTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `context_request` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
            "`run_id` TEXT NOT NULL, `response_message_id` TEXT NOT NULL, `protocol` TEXT NOT NULL, `endpoint` " +
            "TEXT NOT NULL, `model` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `selection` TEXT NOT NULL, " +
            "`sources` TEXT NOT NULL, `exclusions` TEXT NOT NULL, `raw_edited` INTEGER NOT NULL, " +
            "`redacted_paths` TEXT NOT NULL, `body_sha256` TEXT NOT NULL, `transport_status` TEXT NOT NULL, " +
            "`generation_status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `response_code` INTEGER, " +
            "`failure_type` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES " +
            "`ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_context_request_conversation_id` ON `context_request` " +
            "(`conversation_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_context_request_response_message_id` ON `context_request` " +
            "(`response_message_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_context_request_run_id` ON `context_request` (`run_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `context_request_body` (`request_id` TEXT NOT NULL, `chunk_index` " +
            "INTEGER NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`request_id`, `chunk_index`), FOREIGN " +
            "KEY(`request_id`) REFERENCES `context_request`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `context_response_body` (`run_id` TEXT NOT NULL, `conversation_id` TEXT " +
            "NOT NULL, `chunk_index` INTEGER NOT NULL, `content` TEXT NOT NULL, `file_urls` TEXT NOT NULL, " +
            "PRIMARY KEY(`run_id`, `chunk_index`), FOREIGN KEY(`conversation_id`) REFERENCES " +
            "`ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_context_response_body_conversation_id` ON " +
            "`context_response_body` (`conversation_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_branch (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT " +
            "NOT NULL, tip_id TEXT, active INTEGER NOT NULL, FOREIGN KEY(conversation_id) REFERENCES " +
            "ConversationEntity(id) ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conversation_branch_conversation_id_active ON " +
            "conversation_branch(conversation_id, active)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_path (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT " +
            "NOT NULL, parent_id TEXT, node_id TEXT NOT NULL, message_id TEXT NOT NULL, label TEXT NOT NULL, " +
            "role TEXT NOT NULL, FOREIGN KEY(conversation_id) REFERENCES ConversationEntity(id) ON DELETE " +
            "CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conversation_path_conversation_id ON " +
            "conversation_path(conversation_id)"
        )
}
