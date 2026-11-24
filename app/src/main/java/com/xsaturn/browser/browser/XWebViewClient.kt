package com.xsaturn.browser.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.xsaturn.browser.R
import com.xsaturn.browser.adblock.AdBlocker
import com.xsaturn.browser.adblock.BlockType
import com.xsaturn.browser.adblock.CosmeticScript
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.security.BlockCategory
import com.xsaturn.browser.security.SecurityStats
import com.xsaturn.browser.security.ThreatEngine
import com.xsaturn.browser.security.ThreatVerdict
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * قلب الحماية: يعترض الطلبات، يحجب المواقع الخطرة والإعلانات، يمنع التحويلات التلقائية،
 * ويخدم صفحات X SATURN SEARCH. لا يعرف شيئاً عن الواجهة؛ يتواصل عبر BrowserCallbacks.
 */
class XWebViewClient(
    private val context: Context,
    private val settings: SettingsRepository,
    private val blocker: AdBlocker,
    private val threats: ThreatEngine,
    private val stats: SecurityStats,
    private val pages: InternalPages,
    private val guard: PopupGuard,
    private val callbacks: BrowserCallbacks
) : WebViewClient() {

    private companion object {
        /** صورة GIF شفافة 1×1. */
        val PIXEL_GIF: ByteArray = intArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
            0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x21, 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02,
            0x44, 0x01, 0x00, 0x3B
        ).map { it.toByte() }.toByteArray()
    }

    /** مواقع سمح لها المستخدم بالاتصال غير المشفّر (HTTP) لهذه الجلسة فقط. */
    private val httpAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** مواقع جرّبنا ترقيتها من HTTP إلى HTTPS تلقائياً. */
    private val upgraded: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** مواقع قال المستخدم "لا تسأل مجدداً" عن الانتقال منها (لهذه الجلسة). */
    private val navTrusted: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** يرقّي HTTP إلى HTTPS عند الحاجة ويعيد الرابط الذي يجب تحميله. */
    private fun secureTarget(url: Uri): String {
        if (url.scheme == "http") {
            val h = url.host?.lowercase()
            if (h != null && !isLocalHost(h) && h !in httpAllowed) {
                upgraded.add(h)
                return url.buildUpon().scheme("https").build().toString()
            }
        }
        return url.toString()
    }

    /**
     * انتقال ناتج عن نقرة إلى موقع آخر: نسأل المستخدم (إن كان الخيار مفعّلاً) ثم نحمّل الهدف.
     * يُستعمل أيضاً للنوافذ المنبثقة التي فُتحت بنقرة.
     */
    fun navigateWithConfirm(view: WebView, target: Uri) {
        val fromHost = UrlUtils.hostOf(view.url)
        if (fromHost == null || UrlUtils.sameSite(fromHost, target.host) ||
            !isActive() || !settings.confirmNavigation ||
            navTrusted.contains(UrlUtils.siteOf(fromHost))
        ) {
            view.post { view.loadUrl(secureTarget(target)) }
            return
        }
        callbacks.onConfirmNavigation(
            target.toString(), fromHost,
            { view.post { view.loadUrl(secureTarget(target)) } },
            { navTrusted.add(UrlUtils.siteOf(fromHost)) }
        )
    }

    /** روابط جرّبنا الرجوع بها إلى HTTP تلقائياً (لمنع التكرار اللانهائي). */
    private val fallbackTried: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** عنوان HTTP الأصلي إن فشلت الترقية إلى HTTPS، لعرض زر «المتابعة عبر HTTP». */
    @Volatile var pendingHttpUrl: String? = null

    fun allowHttp(host: String?) {
        if (host != null) httpAllowed.add(host.lowercase())
    }

    private fun isLocalHost(h: String): Boolean =
        h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2" || h.endsWith(".local")

    @Volatile private var pageHost: String? = null
    @Volatile private var lastBlockedUrl: String? = null
    private val pageBlocked = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    private val countUpdatePending = AtomicBoolean(false)

    /** مواقع تابع المستخدم إليها رغم التحذير (لهذه الجلسة فقط). */
    private val bypass: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    fun currentCount(): Int = pageBlocked.get()

    fun isActive(): Boolean = settings.isProtectionActive(pageHost)

    fun string(resId: Int): String = context.getString(resId)

    /** يسجّل حجباً حقيقياً (آمن من أي خيط): عدّاد الصفحة + الإحصائيات الدائمة. */
    fun registerBlock(category: BlockCategory, host: String?, n: Int = 1) {
        stats.record(category, host, n)
        pageBlocked.addAndGet(n)
        if (countUpdatePending.compareAndSet(false, true)) {
            main.postDelayed({
                countUpdatePending.set(false)
                callbacks.onBlockedCountChanged(pageBlocked.get())
            }, 250)
        }
    }

    fun flushStats() {
        stats.flush()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
    }

    private fun bypassed(host: String?): Boolean =
        host != null && bypass.contains(UrlUtils.siteOf(host))

    private fun threatFor(url: Uri, withHeuristics: Boolean): ThreatVerdict? {
        val host = url.host?.lowercase() ?: return null
        return if (withHeuristics) {
            threats.checkNavigation(
                url.toString(), host, settings.blockMalware, settings.blockPhishing, settings.blockAdult
            )
        } else {
            threats.checkHost(host, settings.blockMalware, settings.blockPhishing, settings.blockAdult)
        }
    }

    // ------------------------------------------------------------ دورة الصفحة

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageBlocked.set(0)
        pendingHttpUrl = null
        pageHost = UrlUtils.hostOf(url)
        view.settings.javaScriptCanOpenWindowsAutomatically = !(isActive() && settings.blockPopups)
        guard.injectFallback(view)
        callbacks.onPageStarted(url)
        callbacks.onBlockedCountChanged(0)
    }

    override fun onPageFinished(view: WebView, url: String) {
        callbacks.onPageFinished(url)
        flushStats()
        if (UrlUtils.hostOf(url) == UrlUtils.SEARCH_HOST || url == lastBlockedUrl) return
        if (isActive()) {
            runCosmetic(view)
            // محتوى الإعلانات غالباً يتأخر؛ السكربت آمن للتكرار
            main.postDelayed({ if (view.url == url) runCosmetic(view) }, 1500)
            main.postDelayed({ if (view.url == url) runCosmetic(view) }, 4000)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        callbacks.onUrlChanged(url)
    }

    private fun runCosmetic(view: WebView) {
        if (!isActive()) return
        val selectors = if (settings.hideElements) blocker.cosmeticSelectors else emptyList()
        val script = CosmeticScript.build(context, selectors, settings.blockOverlays)
        view.evaluateJavascript(script) { value ->
            val result = CosmeticScript.parseResult(value)
            if (result.first > 0) registerBlock(BlockCategory.HIDDEN, pageHost, result.first)
            if (result.second > 0) registerBlock(BlockCategory.POPUP, pageHost, result.second)
        }
    }

    // ------------------------------------------------------------ حجب الطلبات

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host?.lowercase()

        // صفحات X SATURN الداخلية (البحث، الرئيسية، التحذير) تُخدم محلياً
        if (host == UrlUtils.SEARCH_HOST) {
            return try {
                pages.handle(request)
            } catch (e: Exception) {
                emptyResponse(500, "Internal Error")
            }
        }

        val scheme = url.scheme
        if (scheme != "http" && scheme != "https") return null

        if (request.isForMainFrame) {
            pageHost = host
            if (host != null && settings.protectionEnabled &&
                !settings.isSiteAllowed(host) && !bypassed(host)
            ) {
                val verdict = threatFor(url, true)
                if (verdict != null) {
                    registerBlock(verdict.category, host)
                    lastBlockedUrl = url.toString()
                    return pages.blockedPage(url.toString(), verdict)
                }
            }
            return null // لا نحجب الصفحة الرئيسية نفسها إلا إن كانت خطرة
        }

        if (!settings.isProtectionActive(pageHost)) return null

        // الطلبات الفرعية غير المشفّرة (HTTP) تُحجب: أغلب الإعلانات المنبثقة وإطاراتها تأتي هكذا،
        // ولا يُسمح بها إلا في موقع وافق المستخدم صراحةً على فتحه عبر HTTP.
        if (scheme == "http" && host != null && !isLocalHost(host) &&
            (pageHost == null || pageHost !in httpAllowed)
        ) {
            return neutralResponse(url)
        }

        // مواقع خطرة داخل الصفحة (إطارات/سكربتات/صور) من أي طرف
        if (host != null && !bypassed(pageHost)) {
            val verdict = threatFor(url, false)
            if (verdict != null) {
                registerBlock(verdict.category, host)
                return emptyResponse(403, "Blocked")
            }
        }

        val type = blocker.check(
            url, pageHost, settings.blockTrackers, settings.strictMode
        ) ?: return null
        registerBlock(if (type == BlockType.TRACKER) BlockCategory.TRACKER else BlockCategory.AD, host)
        // ردّ «ناجح» فارغ بدل خطأ 403: مواقع كشف المانع تعتبر فشل التحميل دليلاً على وجوده
        return neutralResponse(url)
    }

    private fun neutralResponse(url: Uri): WebResourceResponse {
        val path = url.path?.lowercase().orEmpty()
        val mime: String
        val body: ByteArray
        when {
            path.endsWith(".js") || path.contains("adsbygoogle") -> {
                mime = "application/javascript"
                body = "window.canRunAds=true;window.adsbygoogle=window.adsbygoogle||[];".toByteArray()
            }
            path.endsWith(".gif") || path.endsWith(".png") || path.endsWith(".jpg") ||
                path.endsWith(".jpeg") || path.endsWith(".webp") -> {
                mime = "image/gif"
                body = PIXEL_GIF
            }
            path.endsWith(".css") -> {
                mime = "text/css"
                body = ByteArray(0)
            }
            else -> {
                mime = "text/html"
                body = ByteArray(0)
            }
        }
        return WebResourceResponse(
            mime, "UTF-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(body)
        )
    }

    private fun emptyResponse(code: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "UTF-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0))
        )

    // ------------------------------------------------------------ منع التحويلات

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        val gesture = request.hasGesture()
        val fromHost = UrlUtils.hostOf(view.url)
        val active = settings.isProtectionActive(fromHost)

        // روابط تحكم داخلية
        if ((scheme == "http" || scheme == "https") && url.host?.lowercase() == UrlUtils.SEARCH_HOST) {
            when (url.path) {
                "/proceed" -> {
                    handleProceed(view, url)
                    return true
                }
                "/security" -> {
                    callbacks.onOpenSecurity()
                    return true
                }
            }
            return false
        }

        when (scheme) {
            "http", "https" -> {
                // انتقال لموقع إعلاني معروف: يُحجب حتى لو جاء بعد نقرة (اختطاف نقرات)
                if (request.isForMainFrame && active && fromHost != null &&
                    !UrlUtils.sameSite(fromHost, url.host) &&
                    blocker.check(url, fromHost, false) != null
                ) {
                    registerBlock(BlockCategory.AD, url.host?.lowercase())
                    callbacks.onRedirectBlocked(url.toString())
                    return true
                }
                if (request.isForMainFrame && active && settings.blockRedirects &&
                    !gesture && !request.isRedirect && fromHost != null &&
                    !UrlUtils.sameSite(fromHost, url.host)
                ) {
                    val verdict = threatFor(url, false)
                    registerBlock(verdict?.category ?: BlockCategory.REDIRECT, url.host?.lowercase())
                    callbacks.onRedirectBlocked(url.toString())
                    return true
                }
                // نقرة تقود إلى موقع آخر: نسأل المستخدم (الانتقال أو البقاء)
                if (request.isForMainFrame && active && settings.confirmNavigation &&
                    gesture && !request.isRedirect && fromHost != null &&
                    !UrlUtils.sameSite(fromHost, url.host) &&
                    !navTrusted.contains(UrlUtils.siteOf(fromHost))
                ) {
                    callbacks.onConfirmNavigation(
                        url.toString(), fromHost,
                        { view.post { view.loadUrl(secureTarget(url)) } },
                        { navTrusted.add(UrlUtils.siteOf(fromHost)) }
                    )
                    return true
                }
                // HTTPS أولاً: نحاول ترقية الرابط غير المشفّر تلقائياً، وإن فشل نعرض للمستخدم خيار المتابعة
                if (scheme == "http" && request.isForMainFrame) {
                    val h = url.host?.lowercase()
                    if (h != null && !isLocalHost(h) && h !in httpAllowed) {
                        upgraded.add(h)
                        val secure = url.buildUpon().scheme("https").build().toString()
                        view.post { view.loadUrl(secure) }
                        return true
                    }
                }
                return false
            }
            "about", "blob", "javascript" -> return false
            "data" -> {
                if (request.isForMainFrame && !gesture) {
                    registerBlock(BlockCategory.REDIRECT, fromHost)
                    return true
                }
                return false
            }
            else -> {
                if (gesture && (scheme == "tel" || scheme == "mailto" || scheme == "sms")) {
                    openExternally(url.toString())
                } else {
                    registerBlock(BlockCategory.REDIRECT, fromHost)
                    callbacks.onNotice(context.getString(R.string.external_blocked))
                }
                return true
            }
        }
    }

    private fun handleProceed(view: WebView, url: Uri) {
        val target = url.getQueryParameter("url") ?: return
        val t = Uri.parse(target)
        val s = t.scheme?.lowercase()
        val h = t.host?.lowercase()
        if ((s == "http" || s == "https") && h != null && h != UrlUtils.SEARCH_HOST) {
            bypass.add(UrlUtils.siteOf(h))
            view.loadUrl(target)
        }
    }

    private fun openExternally(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            callbacks.onNotice(context.getString(R.string.external_blocked))
        }
    }

    // ------------------------------------------------------------ الأخطاء

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (!request.isForMainFrame || errorResponse.statusCode < 400) return
        val u = request.url
        val h = u.host?.lowercase() ?: return
        // ترقية HTTPS رُفضت من الخادم (أنظمة روابط الإعلانات تعمل غالباً عبر HTTP فقط):
        // نكمل مرة واحدة عبر HTTP بدل صفحة خطأ فارغة.
        if (u.scheme == "https" && h in upgraded && h !in httpAllowed && fallbackTried.add(u.toString())) {
            httpAllowed.add(h)
            val plain = u.buildUpon().scheme("http").build().toString()
            callbacks.onNotice(context.getString(R.string.http_fallback_notice))
            view.post { view.loadUrl(plain) }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val desc = error.description?.toString().orEmpty()
        if (desc.contains("ERR_ABORTED")) return
        val host = request.url.host?.lowercase()
        var message = if (desc.contains("CLEARTEXT"))
            context.getString(R.string.error_cleartext)
        else
            context.getString(R.string.error_generic)
        if (request.url.scheme == "https" && host != null && host in upgraded) {
            pendingHttpUrl = request.url.buildUpon().scheme("http").build().toString()
            message = context.getString(R.string.error_https_failed)
        }
        callbacks.onMainFrameError(request.url.toString(), message)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel() // لا نتجاوز أخطاء الشهادات أبداً
        if (error.url == view.url || view.url == null) {
            val host = try { Uri.parse(error.url).host?.lowercase() } catch (e: Exception) { null }
            if (host != null && host in upgraded) {
                pendingHttpUrl = error.url.replaceFirst("https://", "http://")
            }
            callbacks.onMainFrameError(error.url, context.getString(R.string.error_ssl))
        }
    }
}
