package me.rerere.ai.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RequestControlTest {
    @Test fun `reviewed bytes are sent unchanged including whitespace`() = runTest {
        val original = JsonObject(mapOf("model" to JsonPrimitive("model"), "stream" to JsonPrimitive(true)))
        val reviewed = "{\n  \"model\": \"model\", \"stream\":true,\n\"temperature\":0.2\n}"
        val params = TextGenerationParams(Model(modelId = "model"), requestControl = RequestControl { protocol, endpoint, body ->
            assertEquals("test", protocol)
            assertEquals("https://example.test/v1", endpoint)
            assertEquals(original.toString(), body)
            reviewed
        })
        assertEquals(reviewed, params.prepareRequest("test", "https://example.test/v1", original))
    }

    @Test fun `raw edits preserve stream decoding while model and tool definitions are editable`() {
        val original = """{"model":"same","stream":true,"tools":[{"type":"function","function":{"name":"read"}}]}"""
        validateRequestEdit(original, original.replace("read", "read"))
        assertThrows(IllegalArgumentException::class.java) { validateRequestEdit(original, original.replace("true", "false")) }
        validateRequestEdit(original, original.replace("same", "other"))
        validateRequestEdit(original, original.replace("read", "delete"))
    }


    @Test fun `raw edits can rewrite signed and encrypted native content`() {
        val original = """{"stream":true,"input":[{"type":"reasoning","encrypted_content":"old cipher","summary":[{"type":"summary_text","text":"old"}]}]}"""
        val edited = original.replace("old cipher", "edited cipher").replace("\"old\"", "\"edited\"")
        validateRequestEdit(original, edited)
        validateRequestEdit("""{"messages":[{"signature":"original","thinking":"old"}]}""", """{"messages":[{"signature":"changed","thinking":"edited"}]}""")
        assertThrows(IllegalStateException::class.java) { validateRequestEdit(original, "[]") }
    }

    @Test fun `snapshots redact credential fields and strip endpoint credentials`() {
        val sanitized = sanitizeRequest("""{"api_key":"secret","nested":{"Authorization":"Bearer secret"},"messages":[{"content":"kept"}]}""")
        assertFalse(sanitized.body.contains("secret"))
        assertTrue(sanitized.body.contains("kept"))
        assertEquals(2, sanitized.redactedPaths.size)
        assertEquals("https://example.test/v1", sanitizedEndpoint("https://user:pass@example.test/v1?key=secret#token"))
    }

    @Test fun `unredacted snapshots preserve exact wire body`() {
        val body = "{ \n \"messages\": [ { \"content\": \"line\\nnext\" } ] }"
        assertEquals(body, sanitizeRequest(body).body)
    }

    @Test fun `diff reports message tool and parameter paths`() {
        val before = """{"messages":[{"content":"old"}],"tools":["read"],"temperature":0.1}"""
        val after = """{"messages":[{"content":"new"}],"tools":[],"temperature":0.9}"""
        assertEquals(setOf("/messages/0/content", "/tools/0", "/temperature"), diffRequests(before, after).map { it.path }.toSet())
    }
}
