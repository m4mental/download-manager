package com.example.speeddown.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp-based Downloader implementation for NewPipe Extractor.
 * Connects NewPipeExtractor directly to SpeedDown's high-speed OkHttp engine.
 */
class NewPipeDownloaderImpl(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder().url(url)

        // Set default User-Agent if not provided
        reqBuilder.header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        )

        // Add request headers
        for ((key, values) in headers) {
            reqBuilder.removeHeader(key)
            for (value in values) {
                reqBuilder.addHeader(key, value)
            }
        }

        // Configure HTTP Method and Body
        val requestBody = if (dataToSend != null) {
            dataToSend.toRequestBody(null)
        } else if (httpMethod.equals("POST", ignoreCase = true)) {
            ByteArray(0).toRequestBody(null)
        } else {
            null
        }

        reqBuilder.method(httpMethod, requestBody)

        val okResponse = client.newCall(reqBuilder.build()).execute()
        val responseBodyString = okResponse.body?.string() ?: ""
        val responseCode = okResponse.code
        val responseMessage = okResponse.message
        val responseHeaders = okResponse.headers.toMultimap()
        val latestUrl = okResponse.request.url.toString()

        if (responseCode == 429) {
            throw ReCaptchaException("reCaptcha Challenge Requested", url)
        }

        return Response(responseCode, responseMessage, responseHeaders, responseBodyString, latestUrl)
    }
}
