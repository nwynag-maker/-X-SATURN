package com.xsaturn.browser.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xsaturn.browser.R
import com.xsaturn.browser.adblock.AdBlocker
import com.xsaturn.browser.data.SettingsRepository
import com.xsaturn.browser.security.BlockCategory

/** يتحكم بالنوافذ المنبثقة وصلاحيات المواقع وحوارات JavaScript. */
class XChromeClient(
    private val settings: SettingsRepository,
    private val blocker: AdBlocker,
    private val callbacks: BrowserCallbacks,
    private val client: XWebViewClient
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callbacks.onProgress(newProgress)
    }

    // ------------------------------------------------------------ النوافذ المنبثقة

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val active = client.isActive()
        if (active && settings.blockPopups && !isUserGesture) {
            client.registerBlock(BlockCategory.POPUP, UrlUtils.hostOf(view.url))
            return false
        }
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        // نافذة مؤقتة غير مرئية تلتقط الرابط المطلوب ثم نفتحه في التبويب الحالي ونتخلص منها.
        val temp = WebView(view.context)
        temp.settings.javaScriptEnabled = true
        temp.settings.allowFileAccess = false
        temp.settings.allowContentAccess = false

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            temp.stopLoading()
            temp.destroy()
        }

        temp.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val target: Uri = request.url
                val scheme = target.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    val pageHost = UrlUtils.hostOf(view.url)
                    val isAd = active &&
                        blocker.check(target, pageHost, settings.blockTrackers) != null
                    if (isAd) {
                        client.registerBlock(BlockCategory.POPUP, target.host?.lowercase())
                    } else {
                        client.navigateWithConfirm(view, target)
                    }
                    handler.post { finish() }
                }
                return true
            }
        }
        handler.postDelayed({ finish() }, 8000)

        transport.webView = temp
        resultMsg.sendToTarget()
        return true
    }

    // ------------------------------------------------------------ الصلاحيات

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
        callbacks.onNotice(client.string(R.string.permission_denied))
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        callback.invoke(origin, false, false)
    }

    // ------------------------------------------------------------ حوارات JavaScript

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        MaterialAlertDialogBuilder(view.context)
            .setTitle(UrlUtils.hostOf(url) ?: "")
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        MaterialAlertDialogBuilder(view.context)
            .setTitle(UrlUtils.hostOf(url) ?: "")
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String?,
        result: JsPromptResult
    ): Boolean {
        result.cancel()
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.confirm() // لا نسمح للصفحة بحجز المستخدم برسالة «هل تريد المغادرة؟»
        return true
    }
}
