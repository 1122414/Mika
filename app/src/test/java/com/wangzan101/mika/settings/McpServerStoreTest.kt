package com.wangzan101.mika.settings

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpServerStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 只反转字节;用来确认 secret 文件不是明文。 */
    private class ReverseCipher : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(data: ByteArray) = data.reversedArray()
    }

    private fun store() = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient())

    @Test fun addsKeepsAndEncryptsToken() {
        val s = store()
        val added = s.add("Demo", "https://mcp.example.com/mcp", "secret-token").getOrThrow()
        assertEquals(listOf("Demo"), s.servers.value.map { it.name })
        assertEquals("secret-token", s.token(added.id))

        val secrets = File(tmp.root, "mcp-server-secrets.bin").readBytes()
        assertFalse(String(secrets, Charsets.UTF_8).contains("secret-token"))

        val reopened = store()
        assertEquals("Demo", reopened.servers.value.single().name)
        assertEquals("secret-token", reopened.token(added.id))
    }

    @Test fun enableUpdateAndRemove() {
        val s = store()
        val added = s.add("A", "https://a.example/mcp").getOrThrow()
        s.setEnabled(added.id, false)
        assertFalse(s.servers.value.single().enabled)
        s.update(s.servers.value.single().copy(name = "B")).getOrThrow()
        assertEquals("B", s.servers.value.single().name)
        s.setToken(added.id, "t")
        assertNotNull(s.token(added.id))
        s.remove(added.id)
        assertTrue(s.servers.value.isEmpty())
        assertEquals(null, s.token(added.id))
    }

    @Test fun rejectsUnsafeUrls() {
        val s = store()
        assertTrue(s.add("x", "http://example.com/mcp").isFailure)
        assertTrue(s.add("x", "http://localhost:1234/mcp").isFailure) // 需要显式 allowLocalHttp
        assertTrue(s.add("x", "https://user:pw@example.com/mcp").isFailure)
        assertTrue(s.add("x", "http://169.254.169.254/mcp").isFailure)
        assertTrue(s.add("x", "not a url").isFailure)
        assertNotNull(s.add("x", "https://example.com/mcp").getOrNull())
    }

    @Test fun localHttpIsAllowedOnlyWhenOptedIn() {
        val s = store()
        val added = s.add("local", "http://localhost:1/mcp").let { it.exceptionOrNull() }
        assertNotNull(added)
        val ok = s.add("local", "https://localhost/mcp").getOrThrow().let {
            s.update(it.copy(allowLocalHttp = true)).getOrThrow()
        }
        assertEquals(true, ok.allowLocalHttp)
        // 改成 http 的本地地址后可以通过
        assertEquals(null, s.validate(ok.copy(url = "http://localhost:1/mcp")))
    }

    @Test fun connectionsConnectsEnabledServersAndReportsFailures() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"}}}"""
            ))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"s","inputSchema":{"type":"object"}}]}}"""
            ))
            val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient())
            val added = s.add("demo", "https://localhost/mcp").getOrThrow()
            val url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString()
            s.update(added.copy(url = url, allowLocalHttp = true)).getOrThrow()
            val connections = s.connections()
            assertEquals(0, connections.failures.size)
            assertEquals(1, connections.connections.size)
            assertEquals(listOf("search"), connections.connections.single().endpoint.tools().map { it.name })
            connections.close()
        } finally {
            server.shutdown()
        }
    }

    @Test fun testConnectionReturnsServerInfo() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"demo"},"instructions":"hi"}}"""
            ))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a","description":"","inputSchema":{"type":"object"}}]}}"""
            ))
            val s = McpServerStore(tmp.root, ReverseCipher(), OkHttpClient())
            val added = s.add("demo", "https://localhost/mcp").getOrThrow()
            val config = added.copy(url = server.url("/mcp").newBuilder().host("127.0.0.1").build().toString(), allowLocalHttp = true)
            s.update(config).getOrThrow()
            val result = s.test(config).getOrThrow()
            assertEquals("demo", result.serverName)
            assertEquals("hi", result.instructions)
            assertEquals(listOf("a"), result.tools)
        } finally {
            server.shutdown()
        }
    }
}
