package com.xsaturn.browser.security

import android.content.Context
import com.xsaturn.browser.data.SettingsRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * يحدّث القوائم من مصادر عامة معروفة. يرسل طلب تنزيل عادياً فقط (دون أي بيانات عن تصفحك).
 * كل مصدر مستقل: فشل واحد لا يوقف الباقي، والنتيجة تُعرض في لوحة الأمان بصدق.
 */
class ListUpdater(private val context: Context, private val settings: SettingsRepository) {

    enum class Target { ADS, MALWARE, PHISHING, ADULT }

    data class Source(val id: String, val url: String, val target: Target, val maxBytes: Long)
    data class Result(val id: String, val ok: Boolean, val message: String)

    private val adsDir: File get() = File(context.filesDir, "filters_remote").also { it.mkdirs() }
    private val securityDir: File get() = File(context.filesDir, "security").also { it.mkdirs() }

    fun updateAll(): List<Result> {
        val results = ArrayList<Result>()
        for (src in SOURCES) {
            results.add(update(src))
        }
        val okCount = results.count { it.ok }
        if (okCount > 0) settings.listsUpdatedAt = System.currentTimeMillis()
        settings.listsStatus = results.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.id + ": " + it.message }
        return results
    }

    private fun update(src: Source): Result {
        val dir = if (src.target == Target.ADS) adsDir else securityDir
        val prefix = when (src.target) {
            Target.ADS -> ""
            Target.MALWARE -> "malware_"
            Target.PHISHING -> "phishing_"
            Target.ADULT -> "adult_"
        }
        val tmp = File(dir, "$prefix${src.id}.tmp")
        val dest = File(dir, "$prefix${src.id}.txt")
        return try {
            val lines = download(src, tmp)
            if (lines < MIN_LINES) throw IOException("empty list ($lines lines)")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("rename failed")
            Result(src.id, true, "$lines")
        } catch (e: Exception) {
            tmp.delete()
            Result(src.id, false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** يحمّل إلى ملف مؤقت ويعيد عدد الأسطر غير المعلّقة. */
    private fun download(src: Source, out: File): Int {
        if (!src.url.startsWith("https://")) throw IOException("not https")
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 45000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "XSaturn-Browser/0.2 (list updater)")
        var lines = 0
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            var total = 0L
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                out.bufferedWriter(Charsets.UTF_8).use { writer ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        total += line.length + 1
                        if (total > src.maxBytes) throw IOException("file too large")
                        val t = line.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!")) lines++
                        writer.write(line)
                        writer.newLine()
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return lines
    }

    companion object {
        private const val MIN_LINES = 10

        /** مصادر عامة معروفة. عدّلها هنا إن تغيّر أي رابط. */
        val SOURCES: List<Source> = listOf(
            Source(
                "stevenblack-ads",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                Target.ADS, 12L * 1024 * 1024
            ),
            Source(
                "peterlowe",
                "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
                Target.ADS, 4L * 1024 * 1024
            ),
            Source(
                "urlhaus",
                "https://urlhaus.abuse.ch/downloads/hostfile/",
                Target.MALWARE, 8L * 1024 * 1024
            ),
            Source(
                "phishingdb",
                "https://raw.githubusercontent.com/mitchellkrogza/Phishing.Database/master/phishing-domains-ACTIVE.txt",
                Target.PHISHING, 20L * 1024 * 1024
            ),
            Source(
                "stevenblack-porn",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
                Target.ADULT, 10L * 1024 * 1024
            )
        )
    }
}
