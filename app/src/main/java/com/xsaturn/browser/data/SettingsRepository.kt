package com.xsaturn.browser.data

import android.content.Context
import com.xsaturn.browser.browser.UrlUtils

/** تخزين محلي فقط (SharedPreferences). لا شيء يُرسل خارج الجهاز. */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("xsaturn_settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    var protectionEnabled: Boolean
        get() = bool("protection", true)
        set(v) = putBool("protection", v)

    var blockPopups: Boolean
        get() = bool("block_popups", true)
        set(v) = putBool("block_popups", v)

    var blockRedirects: Boolean
        get() = bool("block_redirects", true)
        set(v) = putBool("block_redirects", v)

    /** يسأل قبل الانتقال إلى موقع آخر بعد نقرة (يحمي من أزرار الإعلانات الخادعة). */
    var confirmNavigation: Boolean
        get() = bool("confirm_navigation", true)
        set(v) = putBool("confirm_navigation", v)

    var blockTrackers: Boolean
        get() = bool("block_trackers", true)
        set(v) = putBool("block_trackers", v)

    var hideElements: Boolean
        get() = bool("hide_elements", true)
        set(v) = putBool("hide_elements", v)

    var blockOverlays: Boolean
        get() = bool("block_overlays", true)
        set(v) = putBool("block_overlays", v)

    /** مغلق افتراضياً لأنه يرسل بصمات مجزّأة للروابط إلى Google. */
    var safeBrowsing: Boolean
        get() = bool("safe_browsing", false)
        set(v) = putBool("safe_browsing", v)

    var blockMalware: Boolean
        get() = bool("block_malware", true)
        set(v) = putBool("block_malware", v)

    var blockPhishing: Boolean
        get() = bool("block_phishing", true)
        set(v) = putBool("block_phishing", v)

    var blockAdult: Boolean
        get() = bool("block_adult", true)
        set(v) = putBool("block_adult", v)

    /**
     * الوضع الصارم: يطبّق أنماط مسارات الإعلانات حتى على طلبات الطرف الأول
     * (مثل /ads/banner من نفس نطاق الصفحة). أقوى حجباً وقد يكسر بعض المواقع.
     */
    var strictMode: Boolean
        get() = bool("strict_mode", false)
        set(v) = putBool("strict_mode", v)

    /** البحث الآمن: يطلب من مزوّد البحث تصفية النتائج الإباحية. */
    var safeSearch: Boolean
        get() = bool("safe_search", true)
        set(v) = putBool("safe_search", v)

    /** تحديث قوائم الحجب أسبوعياً (تنزيل فقط، دون إرسال أي بيانات عنك). */
    var autoUpdateLists: Boolean
        get() = bool("auto_update_lists", true)
        set(v) = putBool("auto_update_lists", v)

    var listsUpdatedAt: Long
        get() = prefs.getLong("lists_updated_at", 0L)
        set(v) = prefs.edit().putLong("lists_updated_at", v).apply()

    var listsStatus: String
        get() = prefs.getString("lists_status", "") ?: ""
        set(v) = prefs.edit().putString("lists_status", v).apply()

    /** مفتاح Brave Search API (اختياري). يُخزَّن محلياً فقط. */
    var braveApiKey: String
        get() = prefs.getString("brave_key", "") ?: ""
        set(v) = prefs.edit().putString("brave_key", v.trim()).apply()

    /** عنوان خادم SearXNG خاص بك (اختياري)، يجب أن يبدأ بـ https:// */
    var searxUrl: String
        get() = prefs.getString("searx_url", "") ?: ""
        set(v) = prefs.edit().putString("searx_url", v.trim().trimEnd('/')).apply()

    // ---- المواقع المستثناة (تُخزَّن كنطاق موقع مثل example.com وتشمل نطاقاته الفرعية) ----

    val allowedSites: Set<String>
        get() = prefs.getStringSet("allowed_sites", emptySet())?.toSet() ?: emptySet()

    fun isSiteAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        return UrlUtils.siteOf(host) in allowedSites
    }

    @Synchronized
    fun setSiteAllowed(host: String, allowed: Boolean) {
        val site = UrlUtils.siteOf(host)
        val updated = allowedSites.toMutableSet()
        if (allowed) updated.add(site) else updated.remove(site)
        prefs.edit().putStringSet("allowed_sites", updated).apply()
    }

    @Synchronized
    fun removeAllowedSites(sites: Collection<String>) {
        val updated = allowedSites.toMutableSet()
        updated.removeAll(sites.toSet())
        prefs.edit().putStringSet("allowed_sites", updated).apply()
    }

    /** هل الحماية فعّالة فعلاً لهذا الموقع؟ */
    fun isProtectionActive(host: String?): Boolean =
        protectionEnabled && host != UrlUtils.SEARCH_HOST && !isSiteAllowed(host)
}
