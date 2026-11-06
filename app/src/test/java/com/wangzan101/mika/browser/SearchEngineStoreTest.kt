package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchEngineStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun defaultsToBing() {
        assertEquals(SearchEngine.Bing, SearchEngineStore(tmp.root).engine.value)
    }

    @Test
    fun remembersTheChoice() {
        SearchEngineStore(tmp.root).set(SearchEngine.Google)
        assertEquals(SearchEngine.Google, SearchEngineStore(tmp.root).engine.value)
    }

    @Test
    fun unknownOrBrokenFileFallsBackToBing() {
        File(tmp.root, "browser_search_engine.txt").writeText("nope")
        assertEquals(SearchEngine.Bing, SearchEngineStore(tmp.root).engine.value)
    }
}
