package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.context.ContextExclusion
import me.rerere.ai.context.ContextMessageState
import me.rerere.ai.context.inspectReasoningDelivery
import me.rerere.ai.context.ContextSelection
import me.rerere.ai.context.ContextSource
import me.rerere.ai.provider.RequestControl
import me.rerere.ai.provider.sanitizeRequest
import me.rerere.ai.provider.sanitizedEndpoint
import me.rerere.ai.provider.validateRequestEdit
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.ContextRequestDAO
import me.rerere.rikkahub.data.db.entity.ContextRequestEntity
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

data class RequestReview(val protocol: String, val endpoint: String, val body: String, val purpose: String = "chat", val notices: List<String> = emptyList())

/** A one-shot review of the first request. Retries and later provider continuation requests are recorded separately. */
class RequestReviewGate {
    private val _preview = MutableStateFlow<RequestReview?>(null)
    val preview = _preview.asStateFlow()
    private val _failure = MutableStateFlow<String?>(null)
    val failure = _failure.asStateFlow()
    private val lifetime = Job()
    private val decision = AtomicReference<CompletableDeferred<String>?>(null)
    private val approvedBodies = mutableMapOf<Triple<String, String, String>, String>()
    private var reviewedMain = false

    @Volatile private var mainConfirmed = false
    var cancelRun: () -> Unit = {}

    suspend fun review(protocol: String, endpoint: String, body: String, purpose: String = "chat", notices: List<String> = emptyList()): String {
        val key = Triple("$purpose/$protocol", endpoint, body)
        approvedBodies[key]?.let { return it }
        if (purpose == "chat" && reviewedMain) return body
        val waiting = CompletableDeferred<String>(lifetime)
        decision.set(waiting)
        _preview.value = RequestReview(protocol, sanitizedEndpoint(endpoint), body, purpose, notices)
        val edited = waiting.await()
        validateRequestEdit(body, edited)
        approvedBodies[key] = edited
        if (purpose == "chat") reviewedMain = true
        _preview.value = null
        return edited
    }

    fun confirm(body: String) {
        val original = checkNotNull(_preview.value) { "Request has not been prepared" }
        validateRequestEdit(original.body, body)
        if (original.purpose == "chat") mainConfirmed = true
        checkNotNull(decision.get()).complete(body)
    }

    fun cancel() {
        if (!mainConfirmed) {
            lifetime.cancel()
            cancelRun()
        }
    }
    fun fail(message: String) {
        _failure.value = message
        lifetime.cancel()
    }
}

class ContextRequestStore(val dao: ContextRequestDAO) {
    fun observe(conversationId: Uuid) = dao.observe(conversationId.toString())
    suspend fun body(id: String) = dao.bodyChunks(id).joinToString("")

    suspend fun response(runId: String) = dao.responseChunks(runId).joinToString("")

    suspend fun finish(runId: Uuid, conversationId: Uuid, status: String, response: UIMessage?) = withContext(
        Dispatchers.IO
    ) {
        dao.finishWithResponse(
            runId.toString(),
            conversationId.toString(),
            status,
            response?.let { JsonInstant.encodeToString(it.copy(contextState = ContextMessageState())) },
            JsonInstant.encodeToString(response?.parts?.localFileUrls().orEmpty())
        )
    }

    fun control(
        conversationId: Uuid,
        runId: Uuid,
        responseMessageId: Uuid,
        model: String,
        messages: List<UIMessage>,
        selection: ContextSelection,
        exclusions: List<ContextExclusion>,
        gate: RequestReviewGate? = null,
        reviewPurpose: String = "chat",
    ): RequestControl = object : RequestControl {
        private val current = AtomicReference<Pair<String, String>?>(null)

        override suspend fun prepare(protocol: String, endpoint: String, serializedBody: String): String {
            val delivery = inspectReasoningDelivery(messages, protocol, serializedBody)
            val finalBody = gate?.review(protocol, endpoint, serializedBody, reviewPurpose, delivery.notices) ?: serializedBody
            validateRequestEdit(serializedBody, finalBody)
            val sentModel = if (protocol == "google_generate_content") model else {
                ((JsonInstant.parseToJsonElement(finalBody) as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull ?: model
            }
            val safe = sanitizeRequest(finalBody)
            val id = Uuid.random().toString()
            dao.save(
                ContextRequestEntity(
                    id = id,
                    conversationId = conversationId.toString(),
                    runId = runId.toString(),
                    responseMessageId = responseMessageId.toString(),
                    protocol = protocol,
                    endpoint = sanitizedEndpoint(endpoint),
                    model = sentModel,
                    createdAt = System.currentTimeMillis(),
                    selection = JsonInstant.encodeToString(selection),
                    sources = JsonInstant.encodeToString(messages.flatMap { message ->
                        message.contextState.sources.ifEmpty {
                            message.parts.mapIndexed { index, part ->
                                val tool = part as? me.rerere.ai.ui.UIMessagePart.Tool
                                val skillPart = tool?.output?.firstOrNull {
                                    it.metadata?.containsKey("context_skill_name") ==
                                        true
                                }
                                val skillName = (skillPart?.metadata?.get(
                                    "context_skill_name"
                                ) as? JsonPrimitive)?.content
                                ContextSource(
                                    conversationId.toString(),
                                    message.id.toString(),
                                    index,
                                    description = if (message.isSynthetic) {
                                        "Generated instructions / transformer"
                                    } else {
                                        tool?.let { "Tool ${it.toolName} / ${it.toolCallId}" }
                                            ?: "${message.role} / ${part.javaClass.simpleName}"
                                    },
                                    skillName = skillName,
                                )
                            }
                        }
                    }),
                    exclusions = JsonInstant.encodeToString(exclusions + inspectReasoningDelivery(messages, protocol, finalBody).omissions),
                    rawEdited = serializedBody != finalBody,
                    redactedPaths = JsonInstant.encodeToString(safe.redactedPaths),
                    bodySha256 = sha256(finalBody),
                ),
                safe.body,
            )
            current.set(id to sha256(finalBody))
            return finalBody
        }

        override fun dispatched(serializedBody: String) {
            val (id, expected) = checkNotNull(current.get())
            check(sha256(serializedBody) == expected) { "Transport body differs from the reviewed request" }
            runBlocking(Dispatchers.IO) { dao.transport(id, "dispatch_started", increment = 1) }
        }

        override fun transportFinished(code: Int?, failureType: String?) {
            val id = current.get()?.first ?: return
            runBlocking(Dispatchers.IO) {
                dao.transport(id, if (code !=
                    null) {
                    "response_headers_received"
                } else {
                    "transport_failed"
                }, code = code, failure = failureType)
            }
        }
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance(
    "SHA-256"
).digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

class ContextRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val control = request.tag(RequestControl::class.java) ?: return chain.proceed(request)
        val buffer = Buffer()
        checkNotNull(request.body).writeTo(buffer)
        control.dispatched(buffer.readUtf8())
        return try {
            chain.proceed(request).also { control.transportFinished(it.code) }
        } catch (e: Exception) {
            control.transportFinished(null, e.javaClass.simpleName)
            throw e
        }
    }
}
