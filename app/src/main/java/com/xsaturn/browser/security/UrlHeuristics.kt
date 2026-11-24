package com.xsaturn.browser.security

import com.xsaturn.browser.browser.UrlUtils
import java.net.IDN

/**
 * فحص استدلالي للروابط (بدون قوائم): يكشف أنماط الاحتيال الشائعة.
 * متحفّظ عمداً لتقليل الإنذارات الكاذبة. يعيد رمز السبب أو null.
 *
 * الرموز: userinfo | homograph | lookalike | brand_subdomain | brand_keywords |
 *         brand_freehost | ip_login | payload
 */
object UrlHeuristics {

    private val KEYWORDS = listOf(
        "login", "signin", "sign-in", "verify", "verification", "secure", "security",
        "account", "update", "confirm", "wallet", "support", "billing", "password",
        "recover", "unlock", "suspended", "authenticate", "auth", "session",
        "دخول", "تسجيل", "تحقق", "تحديث", "حساب", "كلمة-السر"
    )

    private val PATH_KEYWORDS = listOf(
        "login", "signin", "verify", "account", "password", "secure", "wallet",
        "auth", "oauth", "session", "reset", "recover"
    )

    /** امتدادات تنفيذية/أرشيف خطرة في مسار التنزيل. */
    private val PAYLOAD_EXTS = listOf(
        ".exe", ".msi", ".bat", ".cmd", ".scr", ".ps1", ".vbs",
        ".apk", ".dmg", ".pkg", ".jar", ".hta", ".dll"
    )

    /** استضافة مجانية شائعة يُستغلّ اسم العلامة عليها. */
    private val FREE_HOSTS = setOf(
        "000webhostapp.com", "github.io", "netlify.app", "vercel.app", "pages.dev",
        "web.app", "firebaseapp.com", "herokuapp.com", "glitch.me", "surge.sh",
        "repl.co", "replit.dev", "workers.dev", "pages.github.io", "gitlab.io",
        "blogspot.com", "wordpress.com", "weebly.com", "wixsite.com",
        "webflow.io", "render.com", "onrender.com", "fly.dev", "deno.dev",
        "ngrok.io", "ngrok-free.app", "trycloudflare.com", "loca.lt"
    )

    /**
     * علامة تجارية -> نطاقات إضافية مشروعة.
     * يشمل علامات عالمية + علامات خليجية/عربية شائعة مستهدفة بالتصيّد.
     */
    private val BRANDS: Map<String, Set<String>> = mapOf(
        "paypal" to setOf("paypalobjects.com"),
        "google" to setOf("gmail.com", "googleusercontent.com", "gstatic.com", "googleapis.com", "youtube.com", "goo.gl", "withgoogle.com"),
        "facebook" to setOf("fb.com", "fbcdn.net", "facebook.net", "messenger.com", "fb.me"),
        "instagram" to setOf("cdninstagram.com"),
        "whatsapp" to setOf("whatsapp.net"),
        "apple" to setOf("icloud.com", "mzstatic.com", "cdn-apple.com"),
        "icloud" to setOf("apple.com"),
        "microsoft" to setOf(
            "live.com", "microsoftonline.com", "office.com", "office365.com", "outlook.com",
            "windows.com", "azure.com", "msn.com", "bing.com", "xbox.com", "skype.com",
            "sharepoint.com", "onedrive.com", "msft.net", "azureedge.net", "windows.net"
        ),
        "amazon" to setOf("amazonaws.com", "amzn.com", "media-amazon.com", "ssl-images-amazon.com"),
        "netflix" to setOf("nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net"),
        "ebay" to setOf("ebaystatic.com", "ebayimg.com"),
        "binance" to emptySet(),
        "coinbase" to emptySet(),
        "metamask" to emptySet(),
        "steampowered" to setOf("steamcommunity.com", "steamstatic.com"),
        "discord" to setOf("discordapp.com", "discord.gg", "discordapp.net", "discord.media"),
        "linkedin" to setOf("licdn.com"),
        "dropbox" to emptySet(),
        "adobe" to setOf("adobelogin.com", "typekit.net"),
        "telegram" to setOf("t.me", "telegram.me"),
        "twitter" to setOf("t.co", "twimg.com", "x.com"),
        "tiktok" to setOf("tiktokcdn.com", "tiktokv.com", "byteoversea.com"),
        "fedex" to emptySet(),
        "dhl" to emptySet(),
        // علامات خليجية / عربية شائعة في حملات التصيّد
        "alrajhi" to setOf("alrajhibank.com.sa", "alrajhi-capital.com"),
        "alinma" to setOf("alinma.com"),
        "alahli" to setOf("alahli.com", "ncb.com.sa"),
        "samba" to setOf("samba.com"),
        "riyadbank" to setOf("riyadbank.com"),
        "stc" to setOf("stc.com.sa", "stcpay.com.sa"),
        "mobily" to setOf("mobily.com.sa"),
        "zain" to setOf("sa.zain.com", "zain.com"),
        "noon" to setOf("noon.com", "noongroup.com"),
        "jarir" to setOf("jarir.com"),
        "namshi" to setOf("namshi.com"),
        "careem" to setOf("careem.com"),
        "jahez" to setOf("jahez.net"),
        "hungerstation" to setOf("hungerstation.com"),
        "tamara" to setOf("tamara.co"),
        "tabby" to setOf("tabby.ai"),
        "absher" to setOf("absher.sa"),
        "nafath" to emptySet(),
        "tawakkalna" to emptySet()
    )

    private val TLD_LIKE = setOf(
        "com", "net", "org", "co", "gov", "edu", "sa", "ae", "kw", "bh", "qa", "om",
        "eg", "io", "app", "dev", "xyz", "top", "site", "online", "shop", "store",
        "info", "biz", "me", "cc", "tv", "uk", "us", "de", "fr"
    )

    /** خرائط حروف متشابهة بصرياً (confusable folding) لتقليد العلامات. */
    private val CONFUSABLE: Map<Char, Char> = mapOf(
        '0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't',
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x',
        'у' to 'y', 'і' to 'i', 'ј' to 'j',
        'α' to 'a', 'ο' to 'o', 'ρ' to 'p', 'υ' to 'y', 'χ' to 'x'
    )

    fun analyze(url: String, host: String): String? {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return null

        // 1) كلمة مرور/اسم قبل @ تُظهر نطاقاً مخادعاً: https://paypal.com@evil.tk/
        val afterScheme = if (url.contains("://")) url.substringAfter("://") else url
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) {
            val userInfo = authority.substringBeforeLast('@')
            if (userInfo.contains('.')) return "userinfo"
        }

        // 2) خلط حروف لاتينية مع سيريلية/يونانية في نفس الجزء
        if (isMixedScript(h)) return "homograph"

        val isIp = IPV4.matches(h)
        if (isIp) {
            if (!isPrivateIp(h)) {
                val lowerUrl = url.lowercase()
                val path = lowerUrl.substringAfter("://", lowerUrl).substringAfter('/', "")
                if (PATH_KEYWORDS.any { path.contains(it) }) return "ip_login"
            }
            return null
        }

        // 3) ملفات تنفيذية مشبوهة على استضافة مجانية / IP / نطاقات قصيرة العمر
        detectPayload(url, h)?.let { return it }

        val site = UrlUtils.siteOf(h)
        val siteLabel = site.substringBefore('.')
        val labels = h.split('.')
        val tokens = h.split('.', '-')
        val lowerUrl = url.lowercase()
        val path = lowerUrl.substringAfter("://", lowerUrl).substringAfter('/', "")
        val foldedLabel = foldConfusable(siteLabel)

        for ((brand, extras) in BRANDS) {
            val legit = siteLabel == brand || foldedLabel == brand || site in extras
            if (legit) continue

            // 4) paypal.com.evil.top : اسم العلامة كجزء فرعي متبوع بنهاية نطاق شكلية
            for (i in 0 until labels.size - 2) {
                if ((labels[i] == brand || foldConfusable(labels[i]) == brand) &&
                    labels[i + 1] in TLD_LIKE
                ) return "brand_subdomain"
            }

            // 5) العلامة على استضافة مجانية: alrajhi-login.github.io
            if (site in FREE_HOSTS) {
                val hostHasBrand = tokens.any { it == brand || foldConfusable(it) == brand } ||
                    h.contains(brand)
                if (hostHasBrand) return "brand_freehost"
            }

            // 6) paypal-secure-login.xyz : العلامة + كلمة تحفيزية
            val brandInTokens = tokens.any { it == brand || foldConfusable(it) == brand }
            if (brandInTokens) {
                val hostHasKeyword = tokens.any { it in KEYWORDS } || KEYWORDS.any { h.contains(it) }
                val pathHasKeyword = PATH_KEYWORDS.any { path.contains(it) }
                if (hostHasKeyword || pathHasKeyword) return "brand_keywords"
            }

            // 7) تقليد الاسم: paypa1 / g00gle / rnicrosoft / faceboook / alrajhl
            if (isLookalike(siteLabel, brand) || isLookalike(foldedLabel, brand)) return "lookalike"
        }
        return null
    }

    // ------------------------------------------------------------ مساعدات

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isPrivateIp(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 169 && p[1] == 254) ||
            p[0] == 0
    }

    fun isMixedScript(host: String): Boolean {
        val unicode = try {
            IDN.toUnicode(host)
        } catch (e: Exception) {
            host
        }
        for (label in unicode.split('.')) {
            var latin = false
            var other = false
            for (ch in label) {
                val code = ch.code
                if ((code in 0x61..0x7A) || (code in 0x41..0x5A)) latin = true
                else if (code in 0x0370..0x03FF || code in 0x0400..0x04FF) other = true
            }
            if (latin && other) return true
        }
        return false
    }

    /** يطوي الحروف المتشابهة بصرياً إلى شكل لاتيني مبسّط. */
    fun foldConfusable(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase()) {
            sb.append(CONFUSABLE[ch] ?: ch)
        }
        return sb.toString().replace("rn", "m")
    }

    private fun isLookalike(label: String, brand: String): Boolean {
        if (label == brand || label.length < 5) return false
        // استبدال الأرقام بالحروف الشبيهة
        for (variant in leetVariants(label)) {
            if (variant == brand) return true
        }
        val folded = foldConfusable(label)
        if (folded == brand) return true
        // مسافة تحرير واحدة للعلامات الطويلة فقط (لتجنب مواقع مشروعة مثل paypay)
        if (brand.length >= 8 && label.length >= 8 && editDistanceAtMostOne(label, brand)) return true
        if (brand.length >= 7 && folded.length >= 7 && editDistanceAtMostOne(folded, brand)) return true
        return false
    }

    private fun leetVariants(label: String): List<String> {
        val base = label
            .replace('0', 'o')
            .replace('3', 'e')
            .replace('4', 'a')
            .replace('5', 's')
            .replace("rn", "m")
        return listOf(base.replace('1', 'l'), base.replace('1', 'i'))
    }

    fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (Math.abs(la - lb) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < la && j < lb) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    la > lb -> i++
                    lb > la -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        edits += (la - i) + (lb - j)
        return edits <= 1
    }

    /**
     * يكشف تنزيلات تنفيذية مشبوهة: امتداد خطير + (استضافة مجانية أو مسار /download/).
     */
    private fun detectPayload(url: String, host: String): String? {
        val lower = url.lowercase()
        val path = lower.substringAfter("://", lower).substringAfter('/', "")
        val pathNoQuery = path.substringBefore('?').substringBefore('#')
        val hasExt = PAYLOAD_EXTS.any { pathNoQuery.endsWith(it) }
        if (!hasExt) return null
        val site = UrlUtils.siteOf(host)
        // نكتفي بالاستضافة المجانية وعناوين IP المباشرة: مسار مثل /download شائع في مواقع مشروعة
        // (ومنها مواقع تنزيل الألعاب والتطبيقات)، فلا نعتبره دليلاً وحده.
        val suspiciousHost = site in FREE_HOSTS || IPV4.matches(host)
        if (suspiciousHost) return "payload"
        return null
    }
}
