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
import me.rerere.ai.context.ContextSelection
import me.rerere.ai.context.compileContext
import me.rerere.ai.context.saveContextEdit
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Real provider serializers, OkHttp transport, stream decoders and Room; loopback responses, not vendor acceptance. */
@RunWith(AndroidJUnit4::class)
class ContextWireIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var store: ContextRequestStore
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "context-wire-${Uuid.random()}.db"
    private val conversationId = Uuid.random()

    @Before fun setup() = runBlocking {
        database = AppDatabaseFactory.create(context, dbName)
        store = ContextRequestStore(database.contextRequestDao())
        database.conversationDao().insert(ConversationEntity(
            id = conversationId.toString(), assistantId = "fixture", title = "Wire fixture", nodes = "[]",
            createAt = 1, updateAt = 1, chatSuggestions = "[]", isPinned = false,
        ))
    }

    @After fun cleanup() { database.close(); context.deleteDatabase(dbName) }

    @Test fun mainProviderProtocolsSendEditedSignedThinkingAndReviewedRawFields() = runBlocking {
        verify("openai_chat_completions", """{"id":"chat","model":"test","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""") { client, base, params ->
            OpenAIProvider(client).generateText(ProviderSetting.OpenAI(baseUrl = base, apiKey = "fixture-secret"), messages(), params).message.toText()
        }
        verify("openai_responses", """{"id":"response","model":"test","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok","annotations":[]}]}]}""") { client, base, params ->
            OpenAIProvider(client).generateText(ProviderSetting.OpenAI(baseUrl = base, apiKey = "fixture-secret", useResponseApi = true), messages(), params).message.toText()
        }
        verify("anthropic_messages", """{"id":"claude","model":"test","role":"assistant","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""") { client, base, params ->
            ClaudeProvider(client).generateText(ProviderSetting.Claude(baseUrl = base, apiKey = "fixture-secret"), messages(), params).message.toText()
        }
        verify("google_generate_content", """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""") { client, base, params ->
            GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = base, apiKey = "fixture-secret"), messages(), params).message.toText()
        }
        verify("google_interactions", """{"id":"interaction","model":"test","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]}""") { client, base, params ->
            GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = base, apiKey = "fixture-secret", useInteractionsApi = true), messages(), params).message.toText()
        }
    }

    @Test fun streamingUsesTheSameReviewedBytesAndDecodesTheReply() = runBlocking {
        val sse = "data: {\"id\":\"chat\",\"model\":\"test\",\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chat\",\"model\":\"test\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        verify("openai_chat_completions", sse, streaming = true) { client, base, params ->
            OpenAIProvider(client).streamText(ProviderSetting.OpenAI(baseUrl = base, apiKey = "fixture-secret"), messages(), params)
                .toList().filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        }
    }

    @Test fun largeSnapshotsAreChunkedAndCancellingOneRunDoesNotRewriteAnother() = runBlocking {
        val replyId = Uuid.random()
        val oldRun = Uuid.random()
        val newRun = Uuid.random()
        val body = "{\"content\":\"${"x".repeat(3 * 1024 * 1024)}\",\"api_key\":\"fixture-secret\"}"
        store.control(conversationId, oldRun, replyId, "test", messages(), ContextSelection(), emptyList()).prepare("test", "https://example.test?key=secret", body)
        store.dao.finish(oldRun.toString(), "completed")
        store.control(conversationId, newRun, replyId, "test", messages(), ContextSelection(), emptyList()).prepare("test", "https://example.test", "{}")
        store.dao.finish(newRun.toString(), "cancelled")
        val records = store.observe(conversationId).first()
        val old = records.single { it.runId == oldRun.toString() }
        assertEquals("completed", old.generationStatus)
        assertEquals("cancelled", records.single { it.runId == newRun.toString() }.generationStatus)
        assertTrue(store.dao.bodyChunks(old.id).size > 40)
        val saved = store.body(old.id)
        assertFalse(saved.contains("fixture-secret"))
        assertTrue(saved.contains("[REDACTED]"))
        assertFalse(old.endpoint.contains("secret"))
        val largeReply = UIMessage.assistant("y".repeat(3 * 1024 * 1024))
        store.finish(oldRun, conversationId, "completed", largeReply)
        store.finish(oldRun, conversationId, "failed", UIMessage.assistant("must not overwrite"))
        assertEquals(largeReply, me.rerere.rikkahub.utils.JsonInstant.decodeFromString<UIMessage>(store.response(oldRun.toString())))
        assertEquals("", store.response(newRun.toString()))
    }

    private val wireHistory by lazy {
        val metadata = kotlinx.serialization.json.JsonObject(mapOf(
            "signature" to kotlinx.serialization.json.JsonPrimitive("keep-signature"),
            "thoughtSignature" to kotlinx.serialization.json.JsonPrimitive("keep-signature"),
            "interactions_signature" to kotlinx.serialization.json.JsonPrimitive("keep-signature"),
            "reasoning_id" to kotlinx.serialization.json.JsonPrimitive("rs_wire"),
            "encrypted_content" to kotlinx.serialization.json.JsonPrimitive("keep-cipher"),
        ))
        val part = UIMessagePart.Reasoning("old signed thought", metadata = metadata)
        val edited = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))
            .saveContextEdit(listOf(part.copy(reasoning = "edited signed thought")))
        compileContext(listOf(UIMessage.user("wire-original"), edited), ContextSelection()).messages
    }

    private fun messages() = wireHistory

    @Test fun unsignedInteractionThoughtIsSentAndRecordedWithoutPreflightRejection() = runBlocking {
        val response = """{"id":"interaction","model":"test","status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]}"""
        LoopbackProvider(response).use { server ->
            coroutineScope {
                val gate = RequestReviewGate()
                val history = listOf(UIMessage.user("question"), UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(me.rerere.ai.ui.UIMessagePart.Reasoning("manual unsigned thought"), me.rerere.ai.ui.UIMessagePart.Text("answer"))))
                val runId = Uuid.random()
                val client = OkHttpClient.Builder().addNetworkInterceptor(ContextRequestInterceptor()).build()
                try {
                    val call = async(Dispatchers.IO) { GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = server.baseUrl, apiKey = "fixture", useInteractionsApi = true), history,
                        TextGenerationParams(model = Model(modelId = "test"), requestControl = store.control(conversationId, runId, Uuid.random(), "test", history, ContextSelection(), emptyList(), gate))) }
                    val preview = withTimeout(10_000) { gate.preview.filterNotNull().first() }
                    assertTrue(preview.body.contains("manual unsigned thought"))
                    assertTrue(preview.notices.isEmpty())
                    assertEquals(0, server.bodies.size)
                    gate.confirm(preview.body)
                    assertEquals("ok", withTimeout(10_000) { call.await() }.message.toText())
                    assertEquals("[]", store.observe(conversationId).first().single { it.runId == runId.toString() }.exclusions)
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }

    @Test fun providerErrorsKeepActualStatusAndBodyForJsonAndPlainTextInBothModes() = runBlocking {
        val protocols = listOf("openai_chat_completions", "openai_responses", "anthropic_messages", "google_generate_content", "google_interactions")
        for (streaming in listOf(false, true)) {
            for (raw in listOf("edited thinking rejected by endpoint", "{\"error\":{\"message\":\"edited thinking rejected\",\"code\":\"vendor_test\"}}")) {
                LoopbackProvider(raw, streaming, responseCode = 400).use { server ->
                    val client = OkHttpClient.Builder().addNetworkInterceptor(ContextRequestInterceptor()).build()
                    try {
                        for (protocol in protocols) {
                            val runId = Uuid.random()
                            val params = TextGenerationParams(model = Model(modelId = "test"),
                                requestControl = store.control(conversationId, runId, Uuid.random(), "test", messages(), ContextSelection(), emptyList()))
                            val failure = when (protocol) {
                                "openai_chat_completions" -> rejected(OpenAIProvider(client), ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "fixture"), params, streaming)
                                "openai_responses" -> rejected(OpenAIProvider(client), ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "fixture", useResponseApi = true), params, streaming)
                                "anthropic_messages" -> rejected(ClaudeProvider(client), ProviderSetting.Claude(baseUrl = server.baseUrl, apiKey = "fixture"), params, streaming)
                                "google_generate_content" -> rejected(GoogleProvider(client), ProviderSetting.Google(baseUrl = server.baseUrl, apiKey = "fixture"), params, streaming)
                                else -> rejected(GoogleProvider(client), ProviderSetting.Google(baseUrl = server.baseUrl, apiKey = "fixture", useInteractionsApi = true), params, streaming)
                            }
                            assertTrue("$protocol: $failure", failure.message.orEmpty().contains("400"))
                            assertTrue("$protocol: $failure", failure.message.orEmpty().contains(raw))
                            assertTrue(server.bodies.poll(5, TimeUnit.SECONDS).contains("edited signed thought"))
                            val record = store.observe(conversationId).first().single { it.runId == runId.toString() }
                            assertEquals(400, record.responseCode)
                            assertEquals("response_headers_received", record.transportStatus)
                        }
                    } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
                }
            }
        }
    }

    private suspend fun <T : ProviderSetting> rejected(provider: Provider<T>, setting: T, params: TextGenerationParams, streaming: Boolean): Exception {
        try {
            if (streaming) provider.streamText(setting, messages(), params).toList() else provider.generateText(setting, messages(), params)
            throw AssertionError("An HTTP rejection must be reported")
        } catch (failure: Exception) { return failure }
    }

    private suspend fun verify(
        protocol: String,
        response: String,
        streaming: Boolean = false,
        history: List<UIMessage> = messages(),
        call: suspend (OkHttpClient, String, TextGenerationParams) -> String,
    ) = coroutineScope {
        LoopbackProvider(response, streaming).use { server ->
            val gate = RequestReviewGate()
            val runId = Uuid.random()
            val params = TextGenerationParams(
                model = Model(modelId = "test"),
                requestControl = store.control(conversationId, runId, Uuid.random(), "test", history, ContextSelection(), emptyList(), gate),
            )
            val client = OkHttpClient.Builder().addNetworkInterceptor(ContextRequestInterceptor()).build()
            try {
                val generating = async(Dispatchers.IO) { call(client, server.baseUrl, params) }
                val preview = withTimeout(10_000) { gate.preview.filterNotNull().first() }
                assertEquals(protocol, preview.protocol)
                assertEquals(0, server.bodies.size)
                assertTrue(store.observe(conversationId).first().none { it.runId == runId.toString() })
                val reviewed = "\n" + preview.body.replace("wire-original", "wire-edited")
                    .replace("\"model\":\"test\"", "\"model\":\"reviewed-model\"")
                    .replace("keep-signature", "edited-signature").replace("keep-cipher", "edited-cipher") + "\n"
                history.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>().forEach { assertTrue(preview.body.contains(it.reasoning)) }
                gate.confirm(reviewed)
                assertEquals("ok", withTimeout(10_000) { generating.await() })
                assertEquals(reviewed, server.bodies.poll(5, TimeUnit.SECONDS))
                val record = store.observe(conversationId).first().single { it.runId == runId.toString() }
                assertEquals("response_headers_received", record.transportStatus)
                assertEquals(1, record.attempts)
                assertEquals(200, record.responseCode)
                assertEquals(if (protocol == "google_generate_content") "test" else "reviewed-model", record.model)
                assertEquals(reviewed, store.body(record.id))
                assertFalse(store.body(record.id).contains("fixture-secret"))
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}

internal class LoopbackProvider(@Volatile var response: String, private val stream: Boolean = false, @Volatile var responseCode: Int = 200) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}/v1"
    val bodies = LinkedBlockingQueue<String>()
    private val worker = thread(name = "context-provider-fixture", isDaemon = true) {
        try {
            while (!socket.isClosed) socket.accept().use { client ->
                client.soTimeout = 10_000
                val input = client.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    check(byte >= 0) { "Unexpected EOF before request headers" }
                    header.append(byte.toChar())
                }
                val length = header.lines().first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    check(count > 0)
                    offset += count
                }
                bodies.put(bytes.toString(Charsets.UTF_8))
                val reply = response.toByteArray(Charsets.UTF_8)
                val headers = "HTTP/1.1 $responseCode ${if (responseCode < 400) "OK" else "Provider rejected request"}\r\nContent-Type: ${if (stream) "text/event-stream" else "application/json"}\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(reply); flush() }
            }
        } catch (e: java.net.SocketException) { if (!socket.isClosed) throw e }
    }
    override fun close() { socket.close(); worker.join(2_000) }
}
