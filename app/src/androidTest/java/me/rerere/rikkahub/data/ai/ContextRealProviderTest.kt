package me.rerere.rikkahub.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.context.ContextSelection
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Opt-in: a host relay injects existing DSH auth; credentials never enter the APK or emulator. */
@RunWith(AndroidJUnit4::class)
class ContextRealProviderTest {
    @Test fun realConfiguredProvidersReceiveTheReviewedBytes() = runBlocking {
        val relay = InstrumentationRegistry.getArguments().getString("contextRealProvider")
        assumeTrue("Requires an explicitly configured local credential relay", relay != null)
        val base = requireNotNull(relay).trimEnd('/')
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "context-real-${Uuid.random()}.db"
        val database = AppDatabaseFactory.create(context, name)
        val store = ContextRequestStore(database.contextRequestDao())
        val conversationId = Uuid.random()
        val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.SECONDS)
            .addNetworkInterceptor(ContextRequestInterceptor()).build()
        suspend fun read(path: String) = client.newCall(Request.Builder().url("$base/$path").build()).await().use {
            check(it.isSuccessful)
            JsonInstant.parseToJsonElement(it.body.string())
        }
        try {
            database.conversationDao().insert(ConversationEntity(
                id = conversationId.toString(), assistantId = "fixture", title = "Real provider acceptance", nodes = "[]",
                createAt = 1, updateAt = 1, chatSuggestions = "[]", isPinned = false,
            ))
            val config = read("config").jsonObject
            for ((route, streaming) in listOf("chat" to false, "chat" to true, "responses" to false, "responses" to true)) {
                coroutineScope {
                    val model = config.getValue(route).jsonObject.getValue("model").jsonPrimitive.content
                    val runId = Uuid.random()
                    val gate = RequestReviewGate()
                    val messages = listOf(UIMessage.user("Reply with exactly the single word BEFORE. No explanation."))
                    val params = TextGenerationParams(
                        model = Model(modelId = model), maxTokens = 2048,
                        requestControl = store.control(conversationId, runId, Uuid.random(), model, messages, ContextSelection(), emptyList(), gate),
                    )
                    val provider = OpenAIProvider(client)
                    val setting = ProviderSetting.OpenAI(baseUrl = "$base/$route", apiKey = "host-relay-only", useResponseApi = route == "responses")
                    val call = async(Dispatchers.IO) {
                        if (streaming) provider.streamText(setting, messages, params).toList()
                            .filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
                        else provider.generateText(setting, messages, params).message.toText()
                    }
                    val preview = withTimeout(10_000) { gate.preview.filterNotNull().first() }
                    assertTrue(store.observe(conversationId).first().none { it.runId == runId.toString() })
                    val reviewed = "\n" + preview.body.replace("BEFORE", "RIKKA") + "\n"
                    gate.confirm(reviewed)
                    val answer = withTimeout(150_000) { call.await() }
                    assertTrue("Real provider did not follow the edited toy prompt", answer.contains("RIKKA", ignoreCase = true))
                    val record = store.observe(conversationId).first().single { it.runId == runId.toString() }
                    assertEquals(200, record.responseCode)
                    assertTrue(record.rawEdited)
                    assertEquals(reviewed, store.body(record.id))
                    val hash = MessageDigest.getInstance("SHA-256").digest(reviewed.toByteArray()).joinToString("") { "%02x".format(it) }
                    val upstream = read("stats").jsonArray.map { it.jsonObject }.last { it["receivedSha256"]?.jsonPrimitive?.content == hash }
                    assertEquals("200", upstream.getValue("status").jsonPrimitive.content)
                    println("REAL_PROVIDER_PASS protocol=${preview.protocol} model=$model stream=$streaming status=200 edited=true exactSha256=$hash")
                    store.dao.finish(runId.toString(), "completed")
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            database.close()
            context.deleteDatabase(name)
        }
    }
}
