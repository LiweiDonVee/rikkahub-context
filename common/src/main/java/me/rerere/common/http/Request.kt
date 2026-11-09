package me.rerere.common.http

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import okio.ForwardingSource
import okio.IOException
import okio.buffer
import kotlin.coroutines.resumeWithException

@OptIn(InternalCoroutinesApi::class)
suspend fun Call.await(): Response {
    val requestCall = this
    return suspendCancellableCoroutine { continuation ->
        val owner = continuation.context[Job]
        // Keep ownership through body consumption: cancelling after headers must also abort a blocking read.
        val cancellation = owner?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) requestCall.cancel()
        }
        continuation.invokeOnCancellation {
            requestCall.cancel()
            cancellation?.dispose()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancellation?.dispose()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                val bound = response.newBuilder().body(object : ResponseBody() {
                    private val ownedSource = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long = try {
                            super.read(sink, byteCount).also { if (it == -1L) cancellation?.dispose() }
                        } catch (e: IOException) {
                            cancellation?.dispose()
                            owner?.ensureActive()
                            throw e
                        }

                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                cancellation?.dispose()
                            }
                        }
                    }.buffer()

                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = ownedSource
                }).build()
                continuation.resume(bound) { _, undelivered, _ ->
                    undelivered.closeQuietly()
                }
            }
        })
    }
}
