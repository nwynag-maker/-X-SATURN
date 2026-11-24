package com.xsaturn.browser.security

import android.content.Context
import java.io.File
import java.io.IOException

/** reason: رمز يُترجم إلى نص عربي في طبقة العرض. */
data class ThreatVerdict(val category: BlockCategory, val reason: String)

data class ThreatCounts(val malware: Int = 0, val phishing: Int = 0, val adult: Int = 0)

/**
 * يحدد إن كان موقع ما خطيراً: برمجيات خبيثة، تصيّد/احتيال، أو محتوى إباحي.
 * المصادر:
 *  - assets/security/{malware,phishing,adult}.txt  (قوائم مدمجة)
 *  - filesDir/security/{malware,phishing,adult}*.txt  (قوائم محدَّثة من الإنترنت)
 * + فحص استدلالي للروابط (UrlHeuristics) + كلمات دالة في اسم النطاق للمحتوى الإباحي.
 */
class ThreatEngine(private val context: Context) {

    @Volatile private var malware: Set<String> = emptySet()
    @Volatile private var phishing: Set<String> = emptySet()
    @Volatile private var adult: Set<String> = emptySet()

    @Volatile
    var counts: ThreatCounts = ThreatCounts()
        private set

    val securityDir: File get() = File(context.filesDir, "security").also { it.mkdirs() }

    @Synchronized
    fun load() {
        val m = HashSet<String>()
        val p = HashSet<String>()
        val a = HashSet<String>()

        for ((prefix, target) in listOf("malware" to m, "phishing" to p, "adult" to a)) {
            readAsset("security/$prefix.txt", target)
            securityDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?.forEach { readFile(it, target) }
        }

        malware = m
        phishing = p
        adult = a
        counts = ThreatCounts(m.size, p.size, a.size)
    }

    /** فحص مضيف فقط (للطلبات الفرعية ونتائج البحث). */
    fun checkHost(host: String, malwareOn: Boolean, phishingOn: Boolean, adultOn: Boolean): ThreatVerdict? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return null

        var cur = h
        while (true) {
            if (malwareOn && cur in malware) return ThreatVerdict(BlockCategory.MALWARE, "listed")
            if (phishingOn && cur in phishing) return ThreatVerdict(BlockCategory.PHISHING, "listed")
            if (adultOn && cur in adult) return ThreatVerdict(BlockCategory.ADULT, "listed")
            val dot = cur.indexOf('.')
            if (dot < 0 || cur.indexOf('.', dot + 1) < 0) break
            cur = cur.substring(dot + 1)
        }
        if (adultOn && looksAdultHost(h)) return ThreatVerdict(BlockCategory.ADULT, "adult_name")
        return null
    }

    /** فحص تنقّل كامل: المضيف + الاستدلالات على الرابط. */
    fun checkNavigation(url: String, host: String, malwareOn: Boolean, phishingOn: Boolean, adultOn: Boolean): ThreatVerdict? {
        checkHost(host, malwareOn, phishingOn, adultOn)?.let { return it }
        val reason = UrlHeuristics.analyze(url, host) ?: return null
        if (reason == "payload") {
            if (malwareOn) return ThreatVerdict(BlockCategory.MALWARE, reason)
            return null
        }
        if (phishingOn) return ThreatVerdict(BlockCategory.PHISHING, reason)
        return null
    }

    // ------------------------------------------------------------ التحليل

    private fun readAsset(path: String, into: MutableSet<String>) {
        try {
            context.assets.open(path).bufferedReader().useLines { seq ->
                seq.forEach { addLine(it, into) }
            }
        } catch (e: IOException) {
        }
    }

    private fun readFile(file: File, into: MutableSet<String>) {
        try {
            file.bufferedReader().useLines { seq ->
                for (line in seq) {
                    if (into.size >= MAX_ENTRIES) break
                    addLine(line, into)
                }
            }
        } catch (e: IOException) {
        }
    }

    private fun addLine(raw: String, into: MutableSet<String>) {
        val host = parseHost(raw) ?: return
        into.add(host)
    }

    companion object {
        private const val MAX_ENTRIES = 300_000

        private val WHITESPACE = Regex("\\s+")
        private val DOMAIN_RE = Regex(
            "^[a-z0-9]([a-z0-9_-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9_-]*[a-z0-9])?)+$"
        )
        private val SKIP = setOf("0.0.0.0", "127.0.0.1", "localhost", "broadcasthost", "local")

        /** نطاقات كبيرة مشروعة لا تُدرج أبداً كاملة (قد تحوي نطاقات فرعية خبيثة محددة فقط). */
        private val NEVER_BLOCK = setOf(
            "google.com", "gstatic.com", "googleapis.com", "youtube.com", "facebook.com",
            "microsoft.com", "apple.com", "amazon.com", "cloudflare.com", "github.com",
            "github.io", "githubusercontent.com", "wikipedia.org", "duckduckgo.com",
            "blogspot.com", "wordpress.com", "weebly.com", "wixsite.com", "netlify.app",
            "pages.dev", "web.app", "firebaseapp.com", "herokuapp.com", "vercel.app",
            "glitch.me", "000webhostapp.com", "sites.google.com", "drive.google.com",
            "docs.google.com", "dropbox.com", "mega.nz", "t.me", "bit.ly", "tinyurl.com",
            "cloudfront.net", "amazonaws.com", "azurewebsites.net", "workers.dev",
            "xsaturn.invalid"
        )

        private val ADULT_TLDS = setOf("xxx", "porn", "adult", "sex")
        private val ADULT_STRONG = listOf(
            "pornhub", "xvideos", "xnxx", "xhamster", "youporn", "redtube", "chaturbate",
            "brazzers", "hentai", "stripchat", "bongacams", "livejasmin", "spankbang"
        )
        private val PORN_PREFIX_SUFFIXES = listOf("o", "tube", "star", "video", "pics", "gif", "vid", "cam", "hub")

        /** يستخرج اسم النطاق من سطر (hosts أو نطاق أو رابط كامل). null إن لم يصلح. */
        fun parseHost(raw: String): String? {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return null
            val hash = line.indexOf('#')
            if (hash > 0) line = line.substring(0, hash).trim()

            var entry: String
            if (line.startsWith("http://") || line.startsWith("https://")) {
                entry = line.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
            } else if (line.startsWith("||")) {
                entry = line.substring(2).substringBefore('^')
            } else {
                val parts = line.split(WHITESPACE)
                entry = when {
                    parts.size == 1 -> parts[0]
                    parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
                    else -> return null
                }
            }
            entry = entry.lowercase().trimEnd('.')
            if (entry in SKIP || entry in NEVER_BLOCK) return null
            return if (DOMAIN_RE.matches(entry)) entry else null
        }

        /** كلمات دالة في اسم النطاق نفسه (تُستثنى المدن والأسماء الشائعة مثل pornic). */
        fun looksAdultHost(host: String): Boolean {
            val labels = host.split('.')
            if (labels.size >= 2 && labels.last() in ADULT_TLDS) return true
            for (i in 0 until labels.size - 1) {
                val l = labels[i]
                for (s in ADULT_STRONG) if (l.contains(s)) return true
                if (l == "porn" || l == "xxx" || l == "porno") return true
                if (l.length > 4 && l.endsWith("porn")) return true
                if (l.startsWith("porn")) {
                    for (suffix in PORN_PREFIX_SUFFIXES) if (l.startsWith("porn$suffix")) return true
                }
                for (tok in l.split('-')) {
                    if (tok == "porn" || tok == "xxx" || tok == "porno") return true
                }
            }
            return false
        }
    }
}
