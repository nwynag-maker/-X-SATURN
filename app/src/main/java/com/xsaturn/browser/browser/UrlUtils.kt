package com.xsaturn.browser.browser

import android.net.Uri

object UrlUtils {

    /**
     * نطاق وهمي (.invalid لا يُحلّ أبداً في DNS) تخدمه الصفحات الداخلية لـ X SATURN SEARCH
     * عبر اعتراض الطلبات؛ لا يخرج أي طلب إليه من الجهاز.
     */
    const val SEARCH_HOST = "search.xsaturn.invalid"
    const val HOME_URL = "https://search.xsaturn.invalid/"
    private const val SEARCH_BASE = "https://search.xsaturn.invalid/search?q="

    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val DANGEROUS_RE =
        Regex("^(javascript|data|file|content|intent|about|view-source|blob):", RegexOption.IGNORE_CASE)
    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz",
        "co.jp", "co.in", "com.br", "com.tr", "com.sa", "com.eg", "com.ly", "com.ar",
        "com.mx", "co.za", "com.cn", "com.hk", "com.sg", "co.kr", "com.pk", "com.ng"
    )

    /** يحوّل ما كتبه المستخدم إلى رابط http(s) أو إلى بحث. */
    fun resolveInput(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME_URL
        if (SCHEME_RE.containsMatchIn(t)) {
            val scheme = t.substringBefore("://").lowercase()
            return if (scheme == "http" || scheme == "https") t else search(t)
        }
        if (DANGEROUS_RE.containsMatchIn(t)) return search(t)
        val looksLikeHost = !t.contains(' ') && t.contains('.') && !t.startsWith(".") && !t.endsWith(".")
        return if (looksLikeHost) "https://$t" else search(t)
    }

    private fun search(query: String) = searchUrl(query)

    fun searchUrl(query: String, page: Int = 1): String {
        var u = SEARCH_BASE + Uri.encode(query)
        if (page > 1) u += "&p=$page"
        return u
    }

    /** الاستعلام داخل رابط بحث X SATURN، أو null. */
    fun queryOf(url: String): String? =
        try {
            Uri.parse(url).getQueryParameter("q")
        } catch (e: Exception) {
            null
        }

    fun hostOf(url: String?): String? =
        if (url.isNullOrEmpty()) null else try {
            Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }

    /** النص المعروض في شريط العنوان عند عدم التحرير. */
    fun displayUrl(url: String): String {
        if (hostOf(url) == SEARCH_HOST) return queryOf(url) ?: ""
        var s = url
        if (s.startsWith("https://")) s = s.removePrefix("https://")
        return s.removeSuffix("/")
    }

    /** النص الذي يظهر عند تحرير شريط العنوان: الاستعلام في صفحات البحث، والرابط الكامل في غيرها. */
    fun editText(url: String): String =
        if (hostOf(url) == SEARCH_HOST) (queryOf(url) ?: "") else url

    /** تقريب لنطاق الموقع (eTLD+1) دون قائمة اللواحق العامة الكاملة. */
    fun siteOf(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.contains(':') || IPV4_RE.matches(h)) return h
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val last2 = parts.takeLast(2).joinToString(".")
        return if (last2 in TWO_LEVEL_SUFFIXES) parts.takeLast(3).joinToString(".") else last2
    }

    fun sameSite(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return siteOf(a) == siteOf(b)
    }
}
