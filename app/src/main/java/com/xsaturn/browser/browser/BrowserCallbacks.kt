package com.xsaturn.browser.browser

/** الواجهة بين طبقة المتصفح (WebView) وطبقة العرض (MainActivity). */
interface BrowserCallbacks {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onUrlChanged(url: String)
    fun onProgress(percent: Int)
    fun onBlockedCountChanged(count: Int)
    fun onRedirectBlocked(url: String)
    fun onNotice(message: String)
    fun onMainFrameError(url: String?, message: String)
    fun onOpenSecurity()

    /**
     * صفحة تريد نقلك إلى موقع آخر بعد نقرة. الواجهة تسأل المستخدم:
     * [proceed] للانتقال، [trustSite] لعدم السؤال مجدداً عن الموقع الحالي في هذه الجلسة.
     */
    fun onConfirmNavigation(url: String, fromHost: String?, proceed: () -> Unit, trustSite: () -> Unit)
}
