package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RequestReviewGateTest {
    @Test fun `no request can pass review until an explicit confirmation`() = runTest {
        val gate = RequestReviewGate()
        val original = """{"model":"same","stream":true,"messages":[{"content":"old"}]}"""
        val edited = original.replace("old", "edited")
        val waiting = async { gate.review("test", "https://example.test/v1?key=hidden", original) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(original, gate.preview.value?.body)
        assertEquals("https://example.test/v1", gate.preview.value?.endpoint)
        gate.confirm(edited)
        assertEquals(edited, waiting.await())
        // The exact same serialization on retry must use the user's edit again.
        assertEquals(edited, gate.review("test", "https://example.test/v1?key=hidden", original))
        // A later tool/provider continuation is a new request, not a replay of the first body.
        val next = original.replace("old", "new tool result")
        assertEquals(next, gate.review("test", "https://example.test/v1?key=hidden", next))
    }

    @Test fun `cancelling review does not release an unreviewed body`() = runTest {
        val gate = RequestReviewGate()
        val waiting = async { gate.review("test", "https://example.test", "{}") }
        runCurrent()
        gate.cancel()
        runCurrent()
        assertTrue(waiting.isCancelled)
    }

    @Test fun `invalid edits leave review pending`() = runTest {
        val gate = RequestReviewGate()
        val body = """{"model":"same","stream":true}"""
        val waiting = async { gate.review("test", "https://example.test", body) }
        runCurrent()
        assertThrows(IllegalArgumentException::class.java) { gate.confirm(body.replace("true", "false")) }
        assertFalse(waiting.isCompleted)
        gate.confirm(body)
        assertEquals(body, waiting.await())
    }

    @Test fun `OCR needs its own confirmation before the final chat preview`() = runTest {
        val gate = RequestReviewGate()
        val ocr = """{"model":"ocr","messages":[{"content":"image"}]}"""
        val chat = """{"model":"chat","messages":[{"content":"recognized text"}]}"""
        val preparing = async {
            val first = gate.review("test", "https://example.test", ocr, purpose = "ocr")
            first to gate.review("test", "https://example.test", chat)
        }
        runCurrent()
        assertEquals("ocr", gate.preview.value?.purpose)
        gate.confirm(ocr)
        runCurrent()
        assertFalse(preparing.isCompleted)
        assertEquals(chat, gate.preview.value?.body)
        gate.confirm(chat)
        assertEquals(ocr to chat, preparing.await())
    }
}
