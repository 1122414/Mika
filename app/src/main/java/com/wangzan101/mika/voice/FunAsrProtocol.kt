package com.wangzan101.mika.voice

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自建 Fun-ASR 实时服务(Fun-ASR 仓库里的 `serve_realtime_ws.py`)的协议:
 * 文字 `START` 开始一次识别(回 `{"event":"started"}`),之后送 16 kHz 单声道 PCM16 二进制帧;
 * 服务端约每半秒回一份到此刻的全文 `{"sentences":[{"text",...}],"partial":"...","is_final":false}`,
 * 每份都是整份替换(`sentences` 是定下来的句子,`partial` 是还在变的那句);
 * 文字 `STOP` 收尾:先回 `is_final` 为 true 的终结果,再回 `{"event":"stopped"}`。
 * 指定识别语言时先发 `LANGUAGE:<中文语言名>`(服务端回 `language_set`),再发 `START`,确认语言的回应排在「开始」前面。
 * 每次按住说话用一个新连接,不复用。
 */
internal class FunAsrProtocol(private val language: AsrLanguage = AsrLanguage.Auto) : LiveAsrProtocol {
    private var final: String? = null

    override fun opening() = listOfNotNull(language.takeIf { it != AsrLanguage.Auto }?.let { "LANGUAGE:${it.funAsrName}" }, "START")
    override fun closing() = listOf("STOP")

    override fun read(message: String): LiveAsrUpdate {
        val root = Json.parseToJsonElement(message).jsonObject
        when (root["event"]?.jsonPrimitive?.contentOrNull) {
            "started" -> return LiveAsrUpdate.Started
            // 没送过音频时服务端不给终结果、直接回 stopped:当没听清
            "stopped" -> return LiveAsrUpdate.Ended(final ?: "")
            null -> Unit
            else -> return LiveAsrUpdate.None // hotwords_set、language_set 等确认
        }
        val sentences = (root["sentences"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull?.trim() }
        val stable = joinAsr(sentences)
        if (root["is_final"]?.jsonPrimitive?.booleanOrNull == true) {
            final = stable
            return LiveAsrUpdate.Text(stable, "")
        }
        return LiveAsrUpdate.Text(stable, root["partial"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
    }

    companion object {
        /** 服务端默认端口。 */
        const val DEFAULT_PORT = 10095

        /** 卡片、诊断里只写主机和端口,不写路径和参数。 */
        fun hostOf(url: String): String = runCatching { URI(url).let { u -> u.host + if (u.port > 0) ":${u.port}" else "" } }.getOrDefault(url)

        /**
         * 用户填的服务地址规范成 ws(s) 地址:没写协议当 ws,http(s) 换成 ws(s),没写端口补 [DEFAULT_PORT];
         * 不是合法地址(没有主机、协议不对、带账号密码)返回 null。
         */
        fun normalize(input: String): String? {
            val raw = input.trim()
            if (raw.isEmpty() || raw.any(Char::isWhitespace)) return null
            val withScheme = if ("://" in raw) raw else "ws://$raw"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val scheme = when (uri.scheme?.lowercase()) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                else -> return null
            }
            val host = uri.host ?: return null
            if (uri.userInfo != null) return null
            val port = if (uri.port > 0) uri.port else if (scheme == "ws" && "://" !in raw) DEFAULT_PORT else -1
            val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://$hostPart${if (port > 0) ":$port" else ""}$path$query"
        }
    }
}
