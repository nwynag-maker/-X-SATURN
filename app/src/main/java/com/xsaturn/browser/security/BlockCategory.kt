package com.xsaturn.browser.security

/** أنواع ما تحجبه الحماية. كل عدّاد في لوحة X SATURN SECURITY يأتي من حجب فعلي بهذه الأنواع. */
enum class BlockCategory(val key: String) {
    AD("ad"),
    TRACKER("tracker"),
    POPUP("popup"),
    REDIRECT("redirect"),
    HIDDEN("hidden"),
    MALWARE("malware"),
    PHISHING("phishing"),
    ADULT("adult");

    companion object {
        fun fromKey(key: String): BlockCategory? = values().firstOrNull { it.key == key }
    }
}
