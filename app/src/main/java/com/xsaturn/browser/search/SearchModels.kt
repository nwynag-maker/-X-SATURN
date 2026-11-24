package com.xsaturn.browser.search

data class SearchResult(val title: String, val url: String, val snippet: String)

data class SearchResponse(
    val results: List<SearchResult>,
    val provider: String,
    val hasNext: Boolean
)

data class KnowledgeCard(val title: String, val extract: String, val url: String, val source: String)

data class SearchOutcome(
    val response: SearchResponse,
    val hidden: Int,
    val card: KnowledgeCard?,
    val millis: Long
)

/** رسالة تقنية (بالإنجليزية) تُعرض مع شرح عربي في صفحة الخطأ. */
class SearchException(message: String) : Exception(message)

interface SearchProvider {
    val name: String

    @Throws(SearchException::class)
    fun search(query: String, page: Int, safe: Boolean): SearchResponse
}
