package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Price a deterministic replay on the real adapter's serialized tools and messages. No network.
 * Assume the common prefix of adjacent requests is cached; TokenEstimate is not the provider tokenizer. */
internal class HotelReplayMeter {
    private var previous = emptyList<String>()
    private var cached = 0L
    private var input = 0L
    val rewrites = mutableListOf<Int>()
    private val model = OpenAiCompatModel("http://localhost", null, "fixture", Capabilities(true, false, 1_000_000, false),
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).jsonObject
            val blocks = (body.getValue("tools").jsonArray + body.getValue("messages").jsonArray).map { it.toString() }
            val first = (0 until minOf(previous.size, blocks.size)).firstOrNull { previous[it] != blocks[it] }
                ?: minOf(previous.size, blocks.size)
            cached += blocks.take(first).sumOf(TokenEstimate::of)
            if (first < previous.size && first < blocks.size) {
                rewrites += requests
                cached += TokenEstimate.of(previous[first].commonPrefixWith(blocks[first]))
            }
            input += blocks.sumOf(TokenEstimate::of)
            previous = blocks
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""".toResponseBody()).build()
        }.build())

    private var requests = 0
    suspend fun observe(step: Int, view: List<Message>, specs: List<ToolSpec>) {
        requests = step
        model.complete("酒店回放", view, specs)
    }

    // Same output budget in both arms: the 34-request reference run reported 8,154 output tokens.
    val estimatedCost get() = (cached * 0.04 + (input - cached) * 2 + 8154 * 8) / 1_000_000
    override fun toString() = "rewrites=$rewrites input=$input cached=$cached miss=${input - cached} cost=$estimatedCost"
}
