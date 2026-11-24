package com.xsaturn.browser.adblock

import android.content.Context
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** قراءة سكربتات JavaScript من assets/js مع تخزين مؤقت. */
object ScriptAssets {
    private val cache = ConcurrentHashMap<String, String>()

    fun read(context: Context, name: String): String =
        cache.getOrPut(name) {
            context.assets.open("js/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
}

/**
 * يبني سكربت JavaScript يُحقن بعد تحميل الصفحة لـ:
 *  1) إخفاء العناصر الإعلانية بمحددات CSS (بعد التحقق من صحة كل محدد على حدة).
 *  2) تعطيل الطبقات الشفافة الكبيرة التي تغطي الصفحة وتسرق النقرات.
 *  3) قراءة عدّاد النوافذ التي حجبها حارس النوافذ داخل الصفحة.
 * يعيد مصفوفة [عناصر معالَجة, نوافذ محجوبة]، والسكربت آمن للتكرار.
 */
object CosmeticScript {

    fun build(context: Context, selectors: List<String>, disableOverlays: Boolean): String {
        val template = ScriptAssets.read(context, "cosmetic.js")
        return template
            .replace("__SELECTORS__", JSONArray(selectors).toString())
            .replace("__OVERLAYS__", if (disableOverlays) "true" else "false")
    }

    /** يحلل ناتج evaluateJavascript مثل "[3,1]". */
    fun parseResult(value: String?): Pair<Int, Int> {
        if (value == null) return Pair(0, 0)
        val parts = value.trim().removePrefix("[").removeSuffix("]").split(",")
        val a = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val b = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return Pair(a, b)
    }
}
