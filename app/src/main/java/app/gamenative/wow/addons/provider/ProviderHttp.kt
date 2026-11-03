package app.gamenative.wow.addons.provider

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal val ProviderJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

/**
 * Performs a request and returns the body text. Returns null on 404, throws [IOException] on other non-2xx responses and network
 * failures. Cancelling the coroutine cancels the call, including a body still being read (Codex OPT13-04).
 */
internal suspend fun OkHttpClient.fetchText(
    url: String,
    headers: Headers = Headers.headersOf(),
    postJson: String? = null,
): String? {
    val builder = Request.Builder().url(url).headers(headers)
    if (postJson != null) builder.post(postJson.toRequestBody("application/json".toMediaType()))
    return newCall(builder.build()).consumeCancellable { response ->
        when {
            response.code == 404 -> null
            !response.isSuccessful -> throw IOException("HTTP ${response.code} for ${response.request.url.encodedPath}")
            else -> response.body.string()
        }
    }
}

/**
 * Runs [block] on the response, body reading included, on OkHttp's thread, and cancels the call when the coroutine is
 * cancelled: a blocking `execute()` + body read inside `withContext(IO)` kept reading (and an install kept going) after the
 * caller was gone.
 */
internal suspend fun <T> Call.consumeCancellable(block: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            runCatching { response.use(block) }.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    })
}
