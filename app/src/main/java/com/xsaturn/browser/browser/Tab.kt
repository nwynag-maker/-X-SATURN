package com.xsaturn.browser.browser

import android.graphics.Bitmap
import android.webkit.WebView

/** تبويب واحد: له WebView وعميل حماية خاص به، وحالة يحتفظ بها عند التبديل. */
class Tab(val id: Int, val webView: WebView) {
    lateinit var client: XWebViewClient
    lateinit var guard: PopupGuard

    var url: String = ""
    var title: String = ""
    var loading: Boolean = false
    var desktop: Boolean = false
    var errorMessage: String? = null

    /** تبويب مستعاد لم يُحمَّل بعد؛ يُحمَّل عند أول فتح له. */
    var needsLoad: Boolean = false

    /** معاينة مصغّرة لأعلى الصفحة (للعرض في شاشة التبويبات). */
    var thumb: Bitmap? = null
}
