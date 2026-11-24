package com.xsaturn.browser.search

import android.net.Uri
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.security.SecurityStats
import com.xsaturn.browser.security.ThreatEngine
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * X SATURN SEARCH: يجلب نتائج حقيقية من الإنترنت عبر سلسلة مزوّدين:
 *   Brave API (إن أُدخل مفتاح)  ←  SearXNG (إن أُدخل خادم)  ←  DuckDuckGo (افتراضي بلا مفتاح)
 * ثم يُخفي النتائج الخطرة (برمجيات خبيثة/تصيّد/إباحي) ويسجّلها في الإحصائيات.
 */
class SearchService(
    private val settings: SettingsRepository,
    private val threats: ThreatEngine,
    private val stats: SecurityStats
) {
    private class CacheEntry(val time: Long, val outcome: SearchOutcome)

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > 30
    }

    private fun providers(): List<SearchProvider> {
        val list = ArrayList<SearchProvider>()
        val key = settings.braveApiKey
        if (key.isNotEmpty()) list.add(BraveProvider(key))
        val searx = settings.searxUrl
        if (searx.isNotEmpty()) list.add(SearxProvider(searx))
        list.add(DuckDuckGoProvider())
        return list
    }

    @Throws(SearchException::class)
    fun search(query: String, page: Int, useCache: Boolean = true): SearchOutcome {
        val cacheKey = "$query|$page|${settings.safeSearch}|${settings.protectionEnabled}|" +
            "${settings.blockAdult}|${settings.blockMalware}|${settings.blockPhishing}|" +
            "${settings.braveApiKey.isNotEmpty()}|${settings.searxUrl}"
        if (useCache) {
            synchronized(cache) {
                val hit = cache[cacheKey]
                if (hit != null && System.currentTimeMillis() - hit.time < CACHE_MS) return hit.outcome
            }
        }

        val started = System.currentTimeMillis()
        val wikiTask: FutureTask<KnowledgeCard?>? =
            if (page == 1) FutureTask<KnowledgeCard?>(Callable<KnowledgeCard?> { WikipediaCard.lookup(query) }) else null
        if (wikiTask != null) Thread(wikiTask, "xs-wiki").start()

        var response: SearchResponse? = null
        var emptyResponse: SearchResponse? = null
        var lastError = "no provider"
        for (p in providers()) {
            try {
                val r = p.search(query, page, settings.safeSearch)
                if (r.results.isNotEmpty()) {
                    response = r
                    break
                }
                emptyResponse = r
            } catch (e: Exception) {
                lastError = p.name + ": " + (e.message ?: e.javaClass.simpleName)
            }
        }
        val raw = response ?: emptyResponse
        if (raw == null) {
            wikiTask?.cancel(true)
            throw SearchException(lastError)
        }

        // إخفاء النتائج الخطرة
        val kept = ArrayList<SearchResult>()
        var hidden = 0
        for (r in raw.results) {
            val host = try {
                Uri.parse(r.url).host?.lowercase()
            } catch (e: Exception) {
                null
            }
            val verdict = if (host != null && settings.protectionEnabled) {
                threats.checkNavigation(
                    r.url, host, settings.blockMalware, settings.blockPhishing, settings.blockAdult
                )
            } else null
            if (verdict != null) {
                hidden++
                stats.record(verdict.category, host)
            } else {
                kept.add(r)
            }
        }

        var card: KnowledgeCard? = null
        if (wikiTask != null) {
            card = try {
                wikiTask.get(2500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                wikiTask.cancel(true)
                null
            }
        }

        val outcome = SearchOutcome(
            SearchResponse(kept, raw.provider, raw.hasNext),
            hidden,
            card,
            System.currentTimeMillis() - started
        )
        synchronized(cache) { cache[cacheKey] = CacheEntry(System.currentTimeMillis(), outcome) }
        return outcome
    }

    private companion object {
        const val CACHE_MS = 10 * 60 * 1000L
    }
}
