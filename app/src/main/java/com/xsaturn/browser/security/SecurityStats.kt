package com.xsaturn.browser.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class BlockEvent(val time: Long, val category: BlockCategory, val host: String)

data class StatsSnapshot(
    val total: Long,
    val today: Long,
    val byCategory: Map<BlockCategory, Long>,
    /** آخر 7 أيام من الأقدم إلى الأحدث: (تاريخ yyyy-MM-dd, عدد) */
    val last7Days: List<Pair<String, Long>>,
    val topDomains: List<Pair<String, Long>>,
    /** الأحدث أولاً */
    val recent: List<BlockEvent>
)

/**
 * إحصائيات الحجب الفعلية. تُسجَّل كل عملية حجب حقيقية هنا (طلب شبكة، نافذة، تحويل، موقع خطير...)
 * وتُحفظ محلياً فقط في SharedPreferences. لا شيء يُرسل خارج الجهاز.
 */
class SecurityStats(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xsaturn_stats", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val totals = LongArray(BlockCategory.values().size)
    private val daily = LinkedHashMap<String, Long>()
    private val domains = HashMap<String, Long>()
    private val events = ArrayList<BlockEvent>() // الأحدث في النهاية
    private var savePending = false

    init {
        load()
    }

    @Synchronized
    fun record(category: BlockCategory, host: String?, n: Int = 1) {
        if (n <= 0) return
        totals[category.ordinal] += n.toLong()

        val day = LocalDate.now().toString()
        daily[day] = (daily[day] ?: 0L) + n
        if (daily.size > 30) {
            val old = daily.keys.sorted().take(daily.size - 30)
            for (k in old) daily.remove(k)
        }

        val h = (host ?: "").lowercase()
        if (h.isNotEmpty()) {
            domains[h] = (domains[h] ?: 0L) + n
            if (domains.size > 300) trimDomains()
        }

        events.add(BlockEvent(System.currentTimeMillis(), category, h))
        if (events.size > MAX_EVENTS) events.removeAt(0)

        scheduleSave()
    }

    @Synchronized
    fun total(): Long = totals.sum()

    @Synchronized
    fun snapshot(): StatsSnapshot {
        val byCat = LinkedHashMap<BlockCategory, Long>()
        for (c in BlockCategory.values()) byCat[c] = totals[c.ordinal]

        val today = LocalDate.now()
        val week = ArrayList<Pair<String, Long>>()
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong()).toString()
            week.add(Pair(d, daily[d] ?: 0L))
        }

        val top = domains.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { Pair(it.key, it.value) }

        return StatsSnapshot(
            total = totals.sum(),
            today = daily[today.toString()] ?: 0L,
            byCategory = byCat,
            last7Days = week,
            topDomains = top,
            recent = events.reversed()
        )
    }

    @Synchronized
    fun reset() {
        for (i in totals.indices) totals[i] = 0L
        daily.clear()
        domains.clear()
        events.clear()
        saveNow()
    }

    /** حفظ فوري (يُستدعى عند إيقاف التطبيق مؤقتاً). */
    @Synchronized
    fun flush() {
        saveNow()
    }

    // ------------------------------------------------------------ التخزين

    private fun trimDomains() {
        val keep = domains.entries.sortedByDescending { it.value }.take(200)
        domains.clear()
        for (e in keep) domains[e.key] = e.value
    }

    private fun scheduleSave() {
        if (savePending) return
        savePending = true
        handler.postDelayed({ flush() }, 3000L)
    }

    private fun saveNow() {
        savePending = false
        try {
            val root = JSONObject()
            val t = JSONObject()
            for (c in BlockCategory.values()) t.put(c.key, totals[c.ordinal])
            root.put("totals", t)

            val d = JSONObject()
            for ((k, v) in daily) d.put(k, v)
            root.put("daily", d)

            val dm = JSONObject()
            for ((k, v) in domains) dm.put(k, v)
            root.put("domains", dm)

            val ev = JSONArray()
            for (e in events) {
                val a = JSONArray()
                a.put(e.time)
                a.put(e.category.key)
                a.put(e.host)
                ev.put(a)
            }
            root.put("events", ev)

            prefs.edit().putString("data", root.toString()).apply()
        } catch (e: Exception) {
            // فشل الحفظ لا يجب أن يكسر المتصفح
        }
    }

    private fun load() {
        val raw = prefs.getString("data", null) ?: return
        try {
            val root = JSONObject(raw)
            val t = root.optJSONObject("totals")
            if (t != null) {
                for (c in BlockCategory.values()) totals[c.ordinal] = t.optLong(c.key, 0L)
            }
            val d = root.optJSONObject("daily")
            if (d != null) {
                val keys = d.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    daily[k] = d.optLong(k, 0L)
                }
            }
            val dm = root.optJSONObject("domains")
            if (dm != null) {
                val keys = dm.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    domains[k] = dm.optLong(k, 0L)
                }
            }
            val ev = root.optJSONArray("events")
            if (ev != null) {
                for (i in 0 until ev.length()) {
                    val a = ev.optJSONArray(i) ?: continue
                    val cat = BlockCategory.fromKey(a.optString(1)) ?: continue
                    events.add(BlockEvent(a.optLong(0), cat, a.optString(2)))
                }
            }
        } catch (e: Exception) {
            // بيانات تالفة: نبدأ من الصفر
        }
    }

    private companion object {
        const val MAX_EVENTS = 100
    }
}
