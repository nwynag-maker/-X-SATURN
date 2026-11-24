package com.xsaturn.browser

import android.app.Application
import com.xsaturn.browser.adblock.AdBlocker
import com.xsaturn.browser.browser.InternalPages
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.search.SearchService
import com.xsaturn.browser.security.ListUpdater
import com.xsaturn.browser.security.SecurityStats
import com.xsaturn.browser.security.ThreatEngine

/** نقطة تهيئة التطبيق: تُنشئ الإعدادات والمحركات مرة واحدة وتحمّل القوائم في خيط خلفي. */
class XSaturnApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    lateinit var blocker: AdBlocker
        private set

    lateinit var threats: ThreatEngine
        private set

    lateinit var stats: SecurityStats
        private set

    lateinit var search: SearchService
        private set

    lateinit var pages: InternalPages
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        stats = SecurityStats(this)
        blocker = AdBlocker(this)
        threats = ThreatEngine(this)
        search = SearchService(settings, threats, stats)
        pages = InternalPages(this, search, stats)

        Thread({
            blocker.load()
            threats.load()
            autoUpdateIfDue()
        }, "xs-filter-load").start()
    }

    /** تحديث أسبوعي للقوائم إن كان مفعّلاً. تنزيل فقط، ولا تُرسل أي بيانات عن تصفحك. */
    private fun autoUpdateIfDue() {
        if (!settings.autoUpdateLists) return
        val age = System.currentTimeMillis() - settings.listsUpdatedAt
        if (age < WEEK_MS) return
        val results = ListUpdater(this, settings).updateAll()
        if (results.any { it.ok }) {
            blocker.load()
            threats.load()
        }
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
