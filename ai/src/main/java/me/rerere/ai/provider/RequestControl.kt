package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.util.json
import java.net.URI

/** Runs after provider-specific transformations and custom body merging, before authentication/transport. */
fun interface RequestControl {
    suspend fun prepare(protocol: String, endpoint: String, serializedBody: String): String

    /** Network interceptor readback of the exact entity being handed to the transport. */
    fun dispatched(serializedBody: String) = Unit

    fun transportFinished(code: Int?, failureType: String? = null) = Unit
}

suspend fun TextGenerationParams.prepareRequest(protocol: String, endpoint: String, body: JsonObject): String {
    val serialized = json.encodeToString(body)
    val result = requestControl?.prepare(protocol, endpoint, serialized) ?: return serialized
    validateRequestEdit(serialized, result)
    // Preserve the exact reviewed bytes. Do not parse and re-serialize after confirmation.
    return result
}

/** Send JSON edits unchanged; the response stream mode must match the selected decoder. */
fun validateRequestEdit(original: String, edited: String) {
    val before = json.parseToJsonElement(original) as? JsonObject ?: error("Original request must be an object")
    val after = json.parseToJsonElement(edited) as? JsonObject ?: error("Request must be a JSON object")
    require(!before.containsKey("stream") || before["stream"] == after["stream"]) { "Change stream mode in conversation settings and rebuild the preview" }
}

@Serializable
data class SanitizedRequest(val body: String, val redactedPaths: List<String>)

/** Headers and URL queries are never stored. Credentials in custom JSON fields are redacted explicitly. */
fun sanitizeRequest(body: String): SanitizedRequest {
    val redacted = mutableListOf<String>()
    val secretKeys = setOf("authorization", "proxyauthorization", "apikey", "xapikey", "xgoogapikey", "accesstoken", "refreshtoken", "clientsecret", "password", "cookie", "setcookie")
    fun redact(value: JsonElement, path: String): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            val location = "$path/${key.replace("~", "~0").replace("/", "~1")}"
            if (key.lowercase().filter { it.isLetterOrDigit() } in secretKeys) {
                redacted += location
                JsonPrimitive("[REDACTED]")
            } else redact(child, location)
        })
        is JsonArray -> JsonArray(value.mapIndexed { index, child -> redact(child, "$path/$index") })
        else -> value
    }
    val sanitized = redact(json.parseToJsonElement(body), "")
    return SanitizedRequest(if (redacted.isEmpty()) body else sanitized.toString(), redacted)
}

fun sanitizedEndpoint(endpoint: String): String = URI(endpoint).let {
    URI(it.scheme, null, it.host, it.port, it.path, null, null).toString()
}

@Serializable
data class RequestDifference(val path: String, val before: JsonElement? = null, val after: JsonElement? = null)

fun diffRequests(before: String, after: String): List<RequestDifference> {
    val differences = mutableListOf<RequestDifference>()
    fun walk(a: JsonElement?, b: JsonElement?, path: String) {
        if (a == b) return
        when {
            a is JsonObject && b is JsonObject -> (a.keys + b.keys).forEach {
                walk(a[it], b[it], "$path/${it.replace("~", "~0").replace("/", "~1")}")
            }
            a is JsonArray && b is JsonArray -> repeat(maxOf(a.size, b.size)) {
                walk(a.getOrNull(it), b.getOrNull(it), "$path/$it")
            }
            else -> differences += RequestDifference(path.ifEmpty { "/" }, a, b)
        }
    }
    walk(json.parseToJsonElement(before), json.parseToJsonElement(after), "")
    return differences
}
