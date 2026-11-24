package com.xsaturn.browser.adblock

import android.content.Context
import android.net.Uri
import com.xsaturn.browser.browser.UrlUtils
import java.io.File
import java.io.IOException

enum class BlockType { AD, TRACKER, PATTERN }

data class FilterStats(
    val adDomains: Int = 0,
    val trackerDomains: Int = 0,
    val patterns: Int = 0,
    val cosmeticRules: Int = 0
)

/**
 * محرك الحجب. مستقل تماماً عن واجهة المستخدم: يستقبل رابط الطلب واسم مضيف الصفحة ويعيد نوع الحجب أو null.
 *
 * المصادر:
 *  - assets/filters/ (ملفات txt)  (قوائم مدمجة)
 *  - filesDir/filters/ (ملفات txt) (قوائم يستوردها المستخدم من جهازه)
 *  - filesDir/filters_remote/ (قوائم تنزيل تلقائي)
 *
 * الوضع الصارم (strict): يطبّق أنماط المسارات الإعلانية حتى على طلبات الطرف الأول.
 */
class AdBlocker(private val context: Context) {

    @Volatile private var adHosts: Set<String> = emptySet()
    @Volatile private var trackerHosts: Set<String> = emptySet()
    @Volatile private var patterns: List<String> = emptyList()
    /** قواعد استثناء بسيطة: @@||example.com^ تُخزَّن كنطاق. */
    @Volatile private var exceptions: Set<String> = emptySet()

    @Volatile
    var cosmeticSelectors: List<String> = emptyList()
        private set

    @Volatile
    var stats: FilterStats = FilterStats()
        private set

    private val importDir: File get() = File(context.filesDir, "filters").also { it.mkdirs() }

    /** قوائم تنزّلها ListUpdater من مصادر عامة (تُعامل كنطاقات إعلانات). */
    private val remoteDir: File get() = File(context.filesDir, "filters_remote").also { it.mkdirs() }

    /** يحمّل كل القوائم ويستبدل الحالية دفعة واحدة. آمن للاستدعاء من خيط خلفي. */
    @Synchronized
    fun load() {
        val ads = HashSet<String>()
        val trackers = HashSet<String>()
        val cosmetic = LinkedHashSet<String>()
        val pats = ArrayList<String>()
        val except = HashSet<String>()

        readAsset("filters/ads.txt") { parseLine(it, ads, cosmetic, except) }
        readAsset("filters/trackers.txt") { parseLine(it, trackers, cosmetic, except) }
        readAsset("filters/cosmetic.txt") { line ->
            val s = line.trim()
            if (s.isNotEmpty() && !s.startsWith("!")) cosmetic.add(s)
        }
        readAsset("filters/patterns.txt") { line ->
            val s = line.trim().lowercase()
            if (s.isNotEmpty() && !s.startsWith("#")) pats.add(s)
        }

        importDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                try {
                    file.bufferedReader().useLines { seq ->
                        seq.forEach { parseLine(it, ads, cosmetic, except) }
                    }
                } catch (e: IOException) {
                }
            }

        remoteDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                try {
                    file.bufferedReader().useLines { seq ->
                        seq.forEach { parseLine(it, ads, cosmetic, except) }
                    }
                } catch (e: IOException) {
                }
            }

        adHosts = ads
        trackerHosts = trackers
        patterns = pats
        exceptions = except
        cosmeticSelectors = cosmetic.toList()
        stats = FilterStats(ads.size, trackers.size, pats.size, cosmetic.size)
    }

    fun clearImported() {
        importDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { it.delete() }
        load()
    }

    fun newImportFile(): File = File(importDir, "import_${System.currentTimeMillis()}.txt")

    /**
     * @param url رابط الطلب الفرعي.
     * @param pageHost مضيف الصفحة الحالية (لتحديد الطرف الثالث).
     * @param blockTrackers هل نحجب نطاقات التتبع.
     * @param strictMode إن true: تُطبَّق أنماط المسارات الإعلانية حتى على الطرف الأول.
     * @return نوع الحجب أو null للسماح.
     */
    fun check(
        url: Uri,
        pageHost: String?,
        blockTrackers: Boolean,
        strictMode: Boolean = false
    ): BlockType? {
        val host = url.host?.lowercase() ?: return null
        if (isExcepted(host)) return null

        val sameSite = pageHost != null && UrlUtils.sameSite(pageHost, host)
        val full = url.toString().lowercase()

        // الطرف الأول: في الوضع المتوازن لا نلمس شيئاً؛ في الصارم نطبّق الأنماط فقط.
        if (sameSite) {
            if (!strictMode) return null
            for (p in patterns) {
                if (full.contains(p)) return BlockType.PATTERN
            }
            return null
        }

        // طرف ثالث: نطاقات + أنماط
        val ads = adHosts
        val trackers = trackerHosts
        var h = host
        while (true) {
            if (h in ads) return BlockType.AD
            if (blockTrackers && h in trackers) return BlockType.TRACKER
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break
            h = h.substring(dot + 1)
        }

        for (p in patterns) {
            if (full.contains(p)) return BlockType.PATTERN
        }
        return null
    }

    private fun isExcepted(host: String): Boolean {
        var h = host
        while (true) {
            if (h in exceptions) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break
            h = h.substring(dot + 1)
        }
        return false
    }

    private fun readAsset(path: String, onLine: (String) -> Unit) {
        try {
            context.assets.open(path).bufferedReader().useLines { seq -> seq.forEach(onLine) }
        } catch (e: IOException) {
        }
    }

    private fun parseLine(
        raw: String,
        hosts: MutableSet<String>,
        cosmetic: MutableSet<String>,
        except: MutableSet<String>
    ) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return

        // قاعدة إخفاء عامة بصيغة ABP:  ##.class
        if (line.startsWith("##")) {
            val sel = line.substring(2).trim()
            if (sel.isNotEmpty() && cosmetic.size < MAX_COSMETIC) cosmetic.add(sel)
            return
        }
        if (line.startsWith("#")) return

        // استثناء ABP البسيط فقط: @@||example.com^  (أي استثناء بخيارات أو مسار يُتجاهل)
        if (line.startsWith("@@")) {
            val rest = line.substring(2)
            if (rest.startsWith("||")) simpleHost(rest.substring(2))?.let { except.add(it) }
            return
        }

        // صيغ غير مدعومة نتجاهلها بدل أن نطبّقها خطأً
        if (line.contains("##") || line.contains("#@#") ||
            line.contains("#?#") || line.contains("#\$#")
        ) return

        var entry: String
        if (line.startsWith("||")) {
            entry = line.substring(2)
            val caret = entry.indexOf('^')
            if (caret >= 0) {
                if (entry.substring(caret + 1).isNotEmpty()) return // يحتوي خيارات أو مساراً
                entry = entry.substring(0, caret)
            }
        } else {
            // صيغة hosts (0.0.0.0 domain) أو نطاق في سطر وحده
            val parts = line.split(WHITESPACE)
            entry = when {
                parts.size == 1 -> parts[0]
                parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
                else -> return
            }
        }

        entry = entry.lowercase()
        if (DOMAIN_RE.matches(entry) && entry !in NEVER_BLOCK) hosts.add(entry)
    }

    /** نطاق بسيط بلا مسار أو خيارات، أو null. */
    private fun simpleHost(raw: String): String? {
        var e = raw
        val caret = e.indexOf('^')
        if (caret >= 0) {
            if (e.substring(caret + 1).isNotEmpty()) return null
            e = e.substring(0, caret)
        }
        e = e.lowercase()
        return if (DOMAIN_RE.matches(e)) e else null
    }

    private companion object {
        const val MAX_COSMETIC = 5000
        val WHITESPACE = Regex("\\s+")
        val DOMAIN_RE = Regex(
            "^[a-z0-9]([a-z0-9_-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9_-]*[a-z0-9])?)+\$"
        )

        /** شبكة أمان: نطاقات أساسية لا تُحجب حتى لو وردت في قائمة مستوردة بالخطأ. */
        val NEVER_BLOCK = setOf(
            "google.com", "gstatic.com", "googleapis.com", "youtube.com", "facebook.com",
            "microsoft.com", "apple.com", "amazon.com", "cloudflare.com", "github.com",
            "wikipedia.org", "duckduckgo.com", "akamaihd.net", "cloudfront.net"
        )
    }
}
