package com.xsaturn.browser.search

import android.net.Uri
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

private fun isWebUrl(u: String): Boolean = u.startsWith("https://") || u.startsWith("http://")

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** نتائج DuckDuckGo عبر نسخة HTML الرسمية. لا تحتاج مفتاحاً. تُحلَّل بـ jsoup وتُستبعد الإعلانات. */
class DuckDuckGoProvider : SearchProvider {
    override val name: String = "DuckDuckGo"

    override fun search(query: String, page: Int, safe: Boolean): SearchResponse {
        if (page > 1) return SearchResponse(emptyList(), name, false)
        val form = "q=" + enc(query) + "&b=&kl=wt-wt&kp=" + (if (safe) "1" else "-2")
        val res = try {
            Http.request(
                "https://html.duckduckgo.com/html/", "POST",
                mapOf(
                    "User-Agent" to Http.BROWSER_UA,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "text/html",
                    "Accept-Language" to Http.acceptLanguage(),
                    "Referer" to "https://html.duckduckgo.com/"
                ),
                form
            )
        } catch (e: IOException) {
            throw SearchException("network: " + (e.message ?: e.javaClass.simpleName))
        }
        if (res.code != 200) throw SearchException("HTTP " + res.code + " (bot check or rate limit)")
        return parse(res.body)
    }

    internal fun parse(html: String): SearchResponse {
        val doc = Jsoup.parse(html, "https://html.duckduckgo.com/")
        if (doc.selectFirst(".anomaly-modal__modal, form#challenge-form") != null) {
            throw SearchException("DuckDuckGo asked for a bot check")
        }
        val out = ArrayList<SearchResult>()
        for (el in doc.select("div.result")) {
            if (el.hasClass("result--ad")) continue
            val a = el.selectFirst("a.result__a") ?: continue
            val real = realUrl(a.absUrl("href")) ?: continue
            if (!isWebUrl(real)) continue
            val title = a.text().trim()
            if (title.isEmpty()) continue
            val snippet = el.selectFirst(".result__snippet")?.text()?.trim() ?: ""
            out.add(SearchResult(title, real, snippet))
        }
        return SearchResponse(out, name, false)
    }

    private fun realUrl(abs: String): String? {
        if (abs.isEmpty()) return null
        val uri = Uri.parse(abs)
        val host = uri.host ?: return null
        if (host.endsWith("duckduckgo.com")) {
            if (uri.path == "/y.js") return null // إعلان
            return uri.getQueryParameter("uddg")
        }
        return abs
    }
}

/** Brave Search API الرسمي (يحتاج مفتاحاً مجانياً/مدفوعاً من المستخدم). */
class BraveProvider(private val apiKey: String) : SearchProvider {
    override val name: String = "Brave Search"

    override fun search(query: String, page: Int, safe: Boolean): SearchResponse {
        val offset = (page - 1).coerceIn(0, 9)
        val url = "https://api.search.brave.com/res/v1/web/search?q=" + enc(query) +
            "&count=20&offset=" + offset + "&safesearch=" + (if (safe) "strict" else "off")
        val res = try {
            Http.request(
                url, "GET",
                mapOf(
                    "Accept" to "application/json",
                    "X-Subscription-Token" to apiKey,
                    "Accept-Language" to Http.acceptLanguage()
                )
            )
        } catch (e: IOException) {
            throw SearchException("network: " + (e.message ?: e.javaClass.simpleName))
        }
        if (res.code == 401 || res.code == 403) throw SearchException("Brave API key rejected (HTTP " + res.code + ")")
        if (res.code != 200) throw SearchException("HTTP " + res.code)
        val arr = try {
            JSONObject(res.body).optJSONObject("web")?.optJSONArray("results")
        } catch (e: Exception) {
            throw SearchException("invalid JSON")
        }
        val out = ArrayList<SearchResult>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                val t = o.optString("title").trim()
                if (!isWebUrl(u) || t.isEmpty()) continue
                val desc = Jsoup.parse(o.optString("description")).text().trim()
                out.add(SearchResult(Jsoup.parse(t).text(), u, desc))
            }
        }
        return SearchResponse(out, name, out.size >= 15 && offset < 9)
    }
}

/** خادم SearXNG خاص بالمستخدم (يجب تفعيل صيغة JSON فيه). */
class SearxProvider(private val baseUrl: String) : SearchProvider {
    override val name: String = "SearXNG"

    override fun search(query: String, page: Int, safe: Boolean): SearchResponse {
        if (!baseUrl.startsWith("https://")) throw SearchException("SearXNG URL must start with https://")
        val url = baseUrl + "/search?q=" + enc(query) + "&format=json&pageno=" + page +
            "&safesearch=" + (if (safe) "2" else "0")
        val res = try {
            Http.request(url, "GET", mapOf("Accept" to "application/json", "User-Agent" to Http.BROWSER_UA))
        } catch (e: IOException) {
            throw SearchException("network: " + (e.message ?: e.javaClass.simpleName))
        }
        if (res.code != 200) throw SearchException("HTTP " + res.code + " (is format=json enabled?)")
        val arr = try {
            JSONObject(res.body).optJSONArray("results")
        } catch (e: Exception) {
            throw SearchException("invalid JSON")
        }
        val out = ArrayList<SearchResult>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                val t = o.optString("title").trim()
                if (!isWebUrl(u) || t.isEmpty()) continue
                out.add(SearchResult(t, u, o.optString("content").trim()))
            }
        }
        return SearchResponse(out, name, out.size >= 5 && page < 10)
    }
}

/** بطاقة معرفة من ويكيبيديا (واجهتها الرسمية بلا مفتاح). تظهر فقط إن تطابق عنوان المقال مع البحث. */
object WikipediaCard {

    fun lookup(query: String): KnowledgeCard? {
        val q = query.trim()
        if (q.length < 3) return null
        val lang = if (q.any { it.code in 0x0600..0x06FF }) "ar" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search" +
            "&gsrsearch=" + enc(q) + "&gsrlimit=1&prop=extracts%7Cinfo&exintro=1&explaintext=1" +
            "&exsentences=3&inprop=url&redirects=1"
        val res = try {
            Http.request(url, "GET", mapOf("User-Agent" to "XSaturnBrowser/0.2 (Android; search card)"), null, 4000)
        } catch (e: Exception) {
            return null
        }
        if (res.code != 200) return null
        try {
            val pages = JSONObject(res.body).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            if (!keys.hasNext()) return null
            val p = pages.optJSONObject(keys.next()) ?: return null
            val title = p.optString("title")
            val extract = p.optString("extract").trim()
            val link = p.optString("fullurl")
            if (title.isEmpty() || extract.isEmpty() || !link.startsWith("https://")) return null
            if (!relevant(q, title)) return null
            return KnowledgeCard(title, extract, link, if (lang == "ar") "ويكيبيديا" else "Wikipedia")
        } catch (e: Exception) {
            return null
        }
    }

    /** نعرض البطاقة فقط إن كان أحد النصين يحتوي الآخر، لتجنب بطاقات بعيدة عن السؤال. */
    internal fun relevant(query: String, title: String): Boolean {
        val q = query.trim().lowercase()
        val t = title.trim().lowercase()
        if (q.length < 3 || t.length < 3) return false
        return t.contains(q) || q.contains(t)
    }
}
