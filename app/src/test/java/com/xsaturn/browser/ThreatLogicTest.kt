package com.xsaturn.browser

import com.xsaturn.browser.security.ThreatEngine
import com.xsaturn.browser.security.UrlHeuristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** نفس الحالات التي تحقّقت منها نسخة Python المكافئة أثناء التطوير. شغّلها: gradle testDebugUnitTest */
class ThreatLogicTest {

    private fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringAfterLast('@')

    private fun analyze(url: String): String? = UrlHeuristics.analyze(url, host(url))

    @Test fun deceptiveUrls() {
        assertEquals("userinfo", analyze("https://paypal.com@evil.tk/"))
        assertEquals("brand_subdomain", analyze("https://paypal.com.secure-login.xyz/"))
        assertEquals("brand_keywords", analyze("https://paypal-support.example.com/"))
        assertEquals("lookalike", analyze("https://paypa1.com/"))
        assertEquals("lookalike", analyze("https://g00gle.com/"))
        assertEquals("lookalike", analyze("https://rnicrosoft.com/"))
        assertEquals("lookalike", analyze("https://faceboook.com/"))
        assertEquals("homograph", analyze("https://xn--pple-43d.com/"))
        assertEquals("ip_login", analyze("http://45.33.12.9/login/verify"))
    }

    @Test fun regionalBrandPhishing() {
        assertEquals("brand_keywords", analyze("https://alrajhi-secure-login.xyz/"))
        assertEquals("brand_keywords", analyze("https://stc-pay-verify.top/account"))
        assertEquals("brand_freehost", analyze("https://alrajhi-login.github.io/"))
        assertEquals("brand_freehost", analyze("https://noon-support.netlify.app/login"))
        assertEquals("lookalike", analyze("https://alrajhl.com/"))
    }

    @Test fun payloadDetection() {
        assertEquals("payload", analyze("https://files.example.github.io/setup.exe"))
        assertEquals("payload", analyze("http://45.33.12.9/files/update.apk"))
        // مسار /download على موقع عادي ليس دليلاً وحده
        assertNull(analyze("https://www.microsoft.com/download/something.exe"))
        assertNull(analyze("https://cdn.example.com/download/update.apk"))
    }

    @Test fun confusableFolding() {
        // الرقم 1 يُطوى إلى l، والحرف السيريلي а إلى a
        assertEquals("paypal", UrlHeuristics.foldConfusable("paypa1"))
        assertEquals("paypal", UrlHeuristics.foldConfusable("p\u0430ypal"))
    }

    @Test fun legitimateUrlsAreNotFlagged() {
        assertNull(analyze("https://www.paypal.com/signin"))
        assertNull(analyze("https://support.microsoft.com/account"))
        assertNull(analyze("https://accounts.google.com/login"))
        assertNull(analyze("https://google-analytics.com/"))
        assertNull(analyze("https://paypay.ne.jp/"))
        assertNull(analyze("http://192.168.1.1/login"))
        assertNull(analyze("https://amazonaws.com/x"))
        assertNull(analyze("https://www.amazon.sa/account"))
        assertNull(analyze("https://pineapple-support.com/"))
        assertNull(analyze("https://ar.wikipedia.org/wiki/x"))
        assertNull(analyze("https://www.alrajhibank.com.sa/"))
        assertNull(analyze("https://www.stc.com.sa/"))
        assertNull(analyze("https://www.noon.com/saudi-en/"))
        assertNull(analyze("https://www.mobile.com/"))
    }

    @Test fun adultNames() {
        assertTrue(ThreatEngine.looksAdultHost("www.pornhub.com"))
        assertTrue(ThreatEngine.looksAdultHost("freeporn.net"))
        assertTrue(ThreatEngine.looksAdultHost("site.xxx"))
        assertTrue(ThreatEngine.looksAdultHost("my-xxx-tube.com"))
        assertFalse(ThreatEngine.looksAdultHost("pornic.fr"))
        assertFalse(ThreatEngine.looksAdultHost("sussex.ac.uk"))
        assertFalse(ThreatEngine.looksAdultHost("essex.gov.uk"))
        assertFalse(ThreatEngine.looksAdultHost("popcorn.com"))
        assertFalse(ThreatEngine.looksAdultHost("wikipedia.org"))
    }

    @Test fun hostsParsing() {
        assertEquals("bad.example.com", ThreatEngine.parseHost("127.0.0.1\tbad.example.com"))
        assertEquals("ads.x.net", ThreatEngine.parseHost("0.0.0.0 ads.x.net # c"))
        assertEquals("evil.test", ThreatEngine.parseHost("https://evil.test/a?b=1"))
        assertEquals("tracker.io", ThreatEngine.parseHost("||tracker.io^"))
        assertNull(ThreatEngine.parseHost("# comment"))
        assertNull(ThreatEngine.parseHost("127.0.0.1 localhost"))
        assertNull(ThreatEngine.parseHost("blogspot.com"))
        assertEquals("foo.blogspot.com", ThreatEngine.parseHost("foo.blogspot.com"))
    }
}
