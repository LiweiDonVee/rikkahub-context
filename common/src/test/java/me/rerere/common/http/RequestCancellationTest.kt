package me.rerere.common.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RequestCancellationTest {
    @Test fun cancellingBeforeHeadersCancelsTheSocket() = runBlocking {
        stalledServer(sendHeaders = false) { url, accepted ->
            val call = OkHttpClient().newCall(Request.Builder().url(url).build())
            val request = async(Dispatchers.IO) { call.await().close() }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(request.isCancelled)
        }
    }

    @Test fun cancellingDuringBodyReadCancelsTheSocket() = runBlocking {
        stalledServer(sendHeaders = true) { url, _ ->
            val call = OkHttpClient().newCall(Request.Builder().url(url).build())
            val headersReceived = CompletableDeferred<Unit>()
            val request = async(Dispatchers.IO) {
                call.await().use { response ->
                    headersReceived.complete(Unit)
                    response.body.string()
                }
            }
            withTimeout(3_000) { headersReceived.await() }
            withTimeout(3_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(request.isCancelled)
        }
    }

    private suspend fun stalledServer(sendHeaders: Boolean, test: suspend (String, CountDownLatch) -> Unit) {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val shutdown = CountDownLatch(1)
        var socket: Socket? = null
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        executor.submit {
            try {
                server.accept().use { connection ->
                    socket = connection
                    val reader = connection.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    accepted.countDown()
                    if (sendHeaders) {
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nx".toByteArray())
                            flush()
                        }
                    }
                    shutdown.await(10, TimeUnit.SECONDS)
                }
            } catch (_: java.io.IOException) {
                // Test teardown can close accept/read concurrently.
            }
        }
        try {
            test("http://127.0.0.1:${server.localPort}", accepted)
        } finally {
            shutdown.countDown()
            socket?.close()
            server.close()
            executor.shutdownNow()
        }
    }
}
