package ai.respondo.sdk.transport

import ai.respondo.sdk.internal.SdkInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Боевая реализация [HttpEngine] поверх OkHttp. Один переиспользуемый [OkHttpClient] (общий пул соединений);
 * покадровый WS/SSE-обмен использует тот же клиент через [okHttpClient].
 */
internal class OkHttpEngine(
    val okHttpClient: OkHttpClient = defaultClient(),
) : HttpEngine {

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val client = if (request.timeoutMs != null) {
            okHttpClient.newBuilder().callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS).build()
        } else {
            okHttpClient
        }

        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("User-Agent", SdkInfo.USER_AGENT)

        when (request.method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.DELETE -> builder.delete(request.body?.toRequestBody())
            HttpMethod.POST -> builder.post(request.body?.toRequestBody() ?: EMPTY_BODY)
        }

        val call = client.newCall(builder.build())
        return call.await()
    }

    override fun close() {
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    private fun HttpBody.toRequestBody(): RequestBody = when (this) {
        is HttpBody.Json -> text.toRequestBody(JSON_MEDIA)
        is HttpBody.Multipart -> MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = fieldName,
                filename = filename,
                body = bytes.toRequestBody(contentType.toMediaType()),
            )
            .build()
    }

    private suspend fun Call.await(): HttpResponse = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val headers = resp.headers.toMap()
                    val bodyText = resp.body?.string().orEmpty()
                    cont.resumeIfActive(HttpResponse(resp.code, bodyText, headers))
                }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private fun CancellableContinuation<HttpResponse>.resumeIfActive(value: HttpResponse) {
        if (isActive) resume(value)
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> =
        (0 until size).associate { name(it).lowercase() to value(it) }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // callTimeout по умолчанию не задаём: для длинных SSE/WS он мешает; для REST переопределяется покадрово.
            .pingInterval(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
