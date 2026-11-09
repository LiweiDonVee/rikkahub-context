package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), RequerySQLiteOpenHelperFactory(),
    )

    @Test fun migrationPreservesLegacyMessageJsonAndAddsEmptySnapshotTables() {
        val name = "context-migration-32-33"
        val history = """[{"role":"assistant","parts":[{"type":"reasoning","reasoning":"original\nthinking"},{"type":"text","text":"answer"}]}]"""
        helper.createDatabase(name, 32).apply {
            execSQL("INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) VALUES ('conv', 'assistant', 'Legacy', '[]', 1, 1)")
            execSQL("INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('node', 'conv', 0, ?, 0)", arrayOf(history))
            close()
        }
        helper.runMigrationsAndValidate(name, 33, true).use { db ->
            db.query("SELECT messages FROM message_node WHERE id = 'node'").use { cursor ->
                check(cursor.moveToFirst()); assertEquals(history, cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM context_request").use { cursor -> check(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
            db.query("SELECT COUNT(*) FROM context_request_body").use { cursor -> check(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
            db.query("SELECT COUNT(*) FROM context_response_body").use { cursor -> check(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
        }
    }
}
