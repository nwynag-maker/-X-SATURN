package com.xsaturn.browser.browser

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.xsaturn.browser.data.SettingsRepository

/** إعدادات WebView الآمنة في مكان واحد. */
object WebViewConfigurator {

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(webView: WebView, prefs: SettingsRepository) {
        val debuggable =
            (webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        with(webView.settings) {
            // JavaScript لازم لمعظم المواقع. لا نضيف أي JavascriptInterface إطلاقاً.
            javaScriptEnabled = true
            domStorageEnabled = true

            // لا وصول للملفات المحلية
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)

            // نوافذ: نتحكم بها في XChromeClient
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true

            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // إزالة علامة WebView من UA كي لا تقدّم المواقع نسخاً مبتورة
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        applyDynamic(webView, prefs)
    }

    /** إعدادات تتغير مع تفضيلات المستخدم؛ تُستدعى عند كل عودة من شاشة الإعدادات. */
    fun applyDynamic(webView: WebView, prefs: SettingsRepository) {
        webView.settings.safeBrowsingEnabled = prefs.safeBrowsing
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, !(prefs.protectionEnabled && prefs.blockTrackers))
        }
    }
}
