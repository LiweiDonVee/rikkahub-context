package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_33_35_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun priorForkRequestHistorySurvivesTheUpstreamDatabaseMerge() {
        val name = "fork-migration-33-35"
        helper.createDatabase(name, 33).use { db ->
            Migration_34_35.migrate(db) // The request tables have the exact Fork 33 definitions.
            db.execSQL("DROP TABLE conversation_branch")
            db.execSQL("DROP TABLE conversation_path")
            db.execSQL("DROP INDEX index_message_node_conversation_id_node_index")
            db.execSQL("CREATE INDEX index_message_node_conversation_id ON message_node(conversation_id)")
            db.execSQL("INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)")
            db.execSQL(
                "INSERT INTO context_request (id, conversation_id, run_id, response_message_id, protocol, endpoint, model, created_at, " +
                    "selection, sources, exclusions, raw_edited, redacted_paths, body_sha256, transport_status, generation_status, attempts) " +
                    "VALUES ('request', 'conv', 'run', 'response', 'fixture', 'https://example.test', 'test', 1000, '{}', '[]', '[]', 0, '[]', 'hash', 'sent', 'completed', 1)"
            )
            db.execSQL("INSERT INTO context_request_body VALUES ('request', 0, 'fixture request body')")
            db.execSQL("INSERT INTO context_response_body VALUES ('run', 'conv', 0, 'original response', '[]')")
        }
        helper.runMigrationsAndValidate(name, 35, true, Migration_33_35).use { db ->
            db.query("SELECT content FROM context_request_body WHERE request_id = 'request'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("fixture request body", cursor.getString(0))
            }
            db.query("SELECT content FROM context_response_body WHERE run_id = 'run'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("original response", cursor.getString(0))
            }
        }
    }

    @Test
    fun upstream34KeepsTheWorkspaceColumnAndAddsBranchAndRequestTables() {
        val name = "upstream-migration-34-35"
        helper.createDatabase(name, 34).close()
        helper.runMigrationsAndValidate(name, 35, true, Migration_34_35).use { db ->
            db.query("SELECT storage_mounts FROM workspaces").close()
            db.query("SELECT id FROM conversation_branch").close()
            db.query("SELECT id FROM context_request").close()
        }
    }
}
