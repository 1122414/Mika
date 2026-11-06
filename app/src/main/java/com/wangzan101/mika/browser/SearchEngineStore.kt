package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 用户在起始页选的搜索引擎,下次打开还是它。存在 App 私有目录的一个小文件里(引擎的 id);
 * 文件不存在、坏了或认不出来时用 [SearchEngine.Bing]。
 */
class SearchEngineStore(dir: File) {
    private val file = File(dir, FILE)
    private val _engine = MutableStateFlow(load())

    val engine: StateFlow<SearchEngine> = _engine.asStateFlow()

    @Synchronized
    fun set(engine: SearchEngine) {
        if (_engine.value == engine) return
        _engine.value = engine
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(engine.id)
            if (!tmp.renameTo(file)) {
                file.writeText(engine.id)
                tmp.delete()
            }
        }
    }

    private fun load(): SearchEngine = runCatching {
        file.takeIf { it.isFile }?.readText()?.trim()?.let(SearchEngine::fromId)
    }.getOrNull() ?: SearchEngine.Bing

    private companion object {
        const val FILE = "browser_search_engine.txt"
    }
}
