package com.xsaturn.browser.search

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HttpResult(val code: Int, val body: String)

/** طلبات HTTPS بسيطة بلا كوكيز ولا بيانات تعريف. */
object Http {

    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val MAX_BYTES = 3 * 1024 * 1024

    fun acceptLanguage(): String {
        val tag = Locale.getDefault().toLanguageTag()
        val lang = Locale.getDefault().language
        return if (lang == "en") "en-US,en;q=0.9" else "$tag,$lang;q=0.9,en;q=0.7"
    }

    @Throws(IOException::class)
    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 10000
    ): HttpResult {
        if (!url.startsWith("https://")) throw IOException("only https is allowed")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = if (stream == null) "" else stream.use { readCapped(it) }
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("response too large")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
