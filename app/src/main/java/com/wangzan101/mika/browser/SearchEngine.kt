package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 起始页和地址栏用的搜索引擎。每个引擎只是一个前缀:搜索网址是前缀加编码后的词,
 * 反过来从网址认出是哪个引擎、搜的是什么(用来在「最近打开」和地址栏里显示搜的词)。
 * 名字按界面语言取([displayName]):品牌名多数语言照写,简中写「必应」「谷歌」「百度」。
 */
enum class SearchEngine(val id: String, private val nameRes: Int, private val prefix: String) {
    Bing("bing", R.string.browser_engine_bing, "https://www.bing.com/search?q="),
    Google("google", R.string.browser_engine_google, "https://www.google.com/search?q="),
    DuckDuckGo("duckduckgo", R.string.browser_engine_duckduckgo, "https://duckduckgo.com/?q="),
    Baidu("baidu", R.string.browser_engine_baidu, "https://www.baidu.com/s?wd=");

    val displayName: String get() = tr(nameRes)

    fun searchUrl(query: String): String = prefix + URLEncoder.encode(query.trim(), "UTF-8")

    /** 网址是这个引擎的搜索结果页时为搜的词,否则为 null。 */
    fun queryOf(url: String): String? {
        if (!url.startsWith(prefix)) return null
        val q = url.removePrefix(prefix).substringBefore('&').substringBefore('#')
        return runCatching { URLDecoder.decode(q, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** 网址是哪个引擎的搜索结果页;都不是为 null。 */
        fun of(url: String): SearchEngine? = entries.firstOrNull { it.queryOf(url) != null }

        fun fromId(id: String?): SearchEngine? = entries.firstOrNull { it.id == id }
    }
}
