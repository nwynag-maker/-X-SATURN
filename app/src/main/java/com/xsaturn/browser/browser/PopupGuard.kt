package com.xsaturn.browser.browser

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xsaturn.browser.adblock.ScriptAssets
import com.xsaturn.browser.data.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * يثبّت سكربت منع النوافذ المنبثقة واختطاف النقرات قبل تشغيل سكربتات أي صفحة.
 * إن لم يدعم WebView الجهاز الميزة، يُحقن السكربت عند بدء كل صفحة كحل بديل (أقل إحكاماً).
 */
class PopupGuard(
    private val context: Context,
    private val webView: WebView,
    private val settings: SettingsRepository
) {
    private var handler: ScriptHandler? = null
    private val supported: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** يبني السكربت بحسب الإعدادات الحالية. */
    private fun script(): String {
        val cfg = JSONObject()
        cfg.put("on", settings.protectionEnabled && settings.blockPopups)
        cfg.put("allowed", JSONArray(settings.allowedSites.toList()))
        return ScriptAssets.read(context, "popup_guard.js").replace("__CONFIG__", cfg.toString())
    }

    /** يستدعى عند التشغيل وعند أي تغيير في الإعدادات أو المواقع المستثناة. */
    fun install() {
        if (!supported) return
        try {
            handler?.remove()
            handler = WebViewCompat.addDocumentStartJavaScript(webView, script(), setOf("*"))
        } catch (e: Exception) {
            handler = null
        }
    }

    /** حل بديل للأجهزة التي لا تدعم الحقن المبكر. */
    fun injectFallback(view: WebView) {
        if (supported) return
        view.evaluateJavascript(script(), null)
    }
}
