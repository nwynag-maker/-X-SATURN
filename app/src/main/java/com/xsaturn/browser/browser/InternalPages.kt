package com.xsaturn.browser.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.xsaturn.browser.R
import com.xsaturn.browser.search.SearchException
import com.xsaturn.browser.search.SearchOutcome
import com.xsaturn.browser.search.SearchService
import com.xsaturn.browser.security.BlockCategory
import com.xsaturn.browser.security.SecurityStats
import com.xsaturn.browser.security.ThreatVerdict
import java.io.ByteArrayInputStream
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

/** قوالب HTML بسيطة: {{key}} يُهرَّب تلقائياً، و{{html_key}} يُدرج كما هو (للأجزاء التي نبنيها نحن). */
object Template {
    private val cache = ConcurrentHashMap<String, String>()
    private val TOKEN = Regex("\\{\\{(\\w+)\\}\\}")

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun render(context: Context, name: String, values: Map<String, String>): String {
        val tpl = cache.getOrPut(name) {
            context.assets.open("search/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return TOKEN.replace(tpl) { m ->
            val key = m.groupValues[1]
            val v = values[key] ?: ""
            if (key.startsWith("html_")) v else esc(v)
        }
    }
}

/**
 * يخدم صفحات X SATURN SEARCH وصفحة التحذير محلياً دون أي اتصال بنطاق حقيقي:
 * يعترضها XWebViewClient قبل وصولها للشبكة. السجل (رجوع/تقدم) يعمل طبيعياً لأنها روابط حقيقية.
 */
class InternalPages(
    private val context: Context,
    private val search: SearchService,
    private val stats: SecurityStats
) {
    private val nf: NumberFormat = NumberFormat.getInstance()

    private val headers: Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to
            "default-src 'none'; img-src https://${UrlUtils.SEARCH_HOST} data:; " +
            "style-src 'unsafe-inline'; form-action https://${UrlUtils.SEARCH_HOST}; base-uri 'none'",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer"
    )

    fun handle(request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET") return status(405, "Method Not Allowed")
        val uri = request.url
        return when (uri.path ?: "/") {
            "", "/", "/security", "/proceed" -> html(layout(context.getString(R.string.search_title), homeBody()))
            "/search" -> searchPage(uri)
            "/logo.png" -> logo()
            else -> if (uri.path?.startsWith("/img/") == true) image(uri.path!!.removePrefix("/img/")) else status(404, "Not Found")
        }
    }

    // ------------------------------------------------------------ الصفحة الرئيسية

    private fun homeBody(): String {
        val s = stats.snapshot()
        return Template.render(
            context, "home.html",
            mapOf("today" to nf.format(s.today), "total" to nf.format(s.total))
        )
    }

    // ------------------------------------------------------------ النتائج

    private fun searchPage(uri: Uri): WebResourceResponse {
        val q = (uri.getQueryParameter("q") ?: "").trim().take(300)
        if (q.isEmpty()) return html(layout(context.getString(R.string.search_title), homeBody()))
        val page = (uri.getQueryParameter("p")?.toIntOrNull() ?: 1).coerceIn(1, 10)
        return try {
            val outcome = search.search(q, page)
            html(layout("$q – X SATURN SEARCH", resultsBody(q, page, outcome)))
        } catch (e: SearchException) {
            html(layout(q, errorBody(q, page, e.message ?: "")))
        } catch (e: Exception) {
            html(layout(q, errorBody(q, page, e.javaClass.simpleName)))
        }
    }

    private fun resultsBody(q: String, page: Int, outcome: SearchOutcome): String {
        val items = StringBuilder()
        for (r in outcome.response.results) {
            items.append(
                Template.render(
                    context, "result_item.html",
                    mapOf(
                        "url" to r.url,
                        "title" to r.title,
                        "snippet" to r.snippet,
                        "display" to displayOf(r.url)
                    )
                )
            )
        }
        if (outcome.response.results.isEmpty()) {
            items.append("<div class=\"empty\">")
                .append(Template.esc(context.getString(R.string.search_no_results)))
                .append("</div>")
        }

        val note = if (outcome.hidden > 0) {
            "<div class=\"note\">🛡 " +
                Template.esc(context.getString(R.string.search_hidden_note, outcome.hidden)) + "</div>"
        } else ""

        val card = outcome.card?.let {
            Template.render(
                context, "card.html",
                mapOf("title" to it.title, "extract" to it.extract, "url" to it.url, "source" to it.source)
            )
        } ?: ""

        val pager = StringBuilder()
        val hasPrev = page > 1
        val hasNext = outcome.response.hasNext
        if (hasPrev || hasNext) {
            pager.append("<nav class=\"pager\">")
            if (hasPrev) {
                pager.append("<a class=\"btn ghost\" href=\"")
                    .append(Template.esc(UrlUtils.searchUrl(q, page - 1)))
                    .append("\">").append(Template.esc(context.getString(R.string.search_prev))).append("</a>")
            } else pager.append("<span></span>")
            if (hasNext) {
                pager.append("<a class=\"btn\" href=\"")
                    .append(Template.esc(UrlUtils.searchUrl(q, page + 1)))
                    .append("\">").append(Template.esc(context.getString(R.string.search_next))).append("</a>")
            }
            pager.append("</nav>")
        }

        return Template.render(
            context, "results.html",
            mapOf(
                "q" to q,
                "html_note" to note,
                "html_card" to card,
                "html_items" to items.toString(),
                "html_pager" to pager.toString(),
                "count" to nf.format(outcome.response.results.size),
                "provider" to outcome.response.provider,
                "secs" to String.format(java.util.Locale.US, "%.1f", outcome.millis / 1000.0)
            )
        )
    }

    private fun errorBody(q: String, page: Int, detail: String): String =
        Template.render(
            context, "error.html",
            mapOf(
                "q" to q,
                "heading" to context.getString(R.string.search_error_heading),
                "message" to context.getString(R.string.search_error_msg),
                "detail" to detail,
                "retry" to UrlUtils.searchUrl(q, page),
                "external" to "https://duckduckgo.com/?q=" + Uri.encode(q)
            )
        )

    private fun displayOf(url: String): String {
        var s = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")
        if (s.length > 80) s = s.substring(0, 80) + "…"
        return s
    }

    // ------------------------------------------------------------ صفحة التحذير

    fun blockedPage(url: String, verdict: ThreatVerdict): WebResourceResponse {
        val (headingRes, msgRes) = when (verdict.category) {
            BlockCategory.MALWARE -> Pair(R.string.block_heading_malware, R.string.block_msg_malware)
            BlockCategory.PHISHING -> Pair(R.string.block_heading_phishing, R.string.block_msg_phishing)
            else -> Pair(R.string.block_heading_adult, R.string.block_msg_adult)
        }
        val reasonRes = when (verdict.reason) {
            "listed" -> R.string.reason_listed
            "adult_name" -> R.string.reason_adult_name
            "userinfo" -> R.string.reason_userinfo
            "homograph" -> R.string.reason_homograph
            "lookalike" -> R.string.reason_lookalike
            "brand_subdomain" -> R.string.reason_brand_subdomain
            "brand_keywords" -> R.string.reason_brand_keywords
            "brand_freehost" -> R.string.reason_brand_freehost
            "ip_login" -> R.string.reason_ip_login
            "payload" -> R.string.reason_payload
            else -> R.string.reason_listed
        }
        val body = Template.render(
            context, "blocked.html",
            mapOf(
                "heading" to context.getString(headingRes),
                "host" to (UrlUtils.hostOf(url) ?: url),
                "message" to context.getString(msgRes),
                "reason" to context.getString(reasonRes),
                "proceed" to "https://${UrlUtils.SEARCH_HOST}/proceed?url=" + Uri.encode(url)
            )
        )
        return html(layout(context.getString(headingRes), body))
    }

    // ------------------------------------------------------------ مساعدات

    private fun layout(title: String, body: String): String =
        Template.render(
            context, "layout.html",
            mapOf("title" to title, "html_css" to css(), "html_body" to body)
        )

    private var cssCache: String? = null

    private fun css(): String {
        val c = cssCache
        if (c != null) return c
        val read = context.assets.open("search/style.css").bufferedReader(Charsets.UTF_8).use { it.readText() }
        cssCache = read
        return read
    }

    private fun html(content: String): WebResourceResponse =
        WebResourceResponse(
            "text/html", "UTF-8", 200, "OK", headers,
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        )

    /** صور الصفحة الرئيسية من assets/search/img (أسماء بسيطة فقط، منعاً لتجاوز المسار). */
    private fun image(name: String): WebResourceResponse {
        if (!Regex("[a-z0-9_]{1,24}\\.(png|jpg)").matches(name)) return status(404, "Not Found")
        return try {
            WebResourceResponse(
                if (name.endsWith(".png")) "image/png" else "image/jpeg", null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400"),
                context.assets.open("search/img/$name")
            )
        } catch (e: Exception) {
            status(404, "Not Found")
        }
    }

    private fun logo(): WebResourceResponse =
        WebResourceResponse(
            "image/png", null, 200, "OK",
            mapOf("Cache-Control" to "max-age=86400"),
            context.assets.open("search/logo.png")
        )

    private fun status(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "UTF-8", code, reason, headers,
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8))
        )
}
