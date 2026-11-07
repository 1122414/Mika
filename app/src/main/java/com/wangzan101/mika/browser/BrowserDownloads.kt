package com.wangzan101.mika.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 浏览器下载列表里的一项:[status] 是系统 DownloadManager 的状态码。 */
data class BrowserDownload(
    val id: Long,
    val title: String,
    val status: Int,
    val soFar: Long,
    val total: Long,
    val mime: String?,
    val reason: Int,
) {
    /** 还在排队、下载或暂停。 */
    val running: Boolean
        get() = status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
    val done: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val failed: Boolean get() = status == DownloadManager.STATUS_FAILED

    /** 知道总大小时的进度百分比。 */
    val percent: Int?
        get() = if (running && total > 0) ((soFar * 100) / total).toInt().coerceIn(0, 100) else null
}

/**
 * 用户浏览器里的下载:交给系统 [DownloadManager],文件落在系统「下载」目录,通知栏显示进度。
 * 下载的链接只收 http/https(blob:、data: 这类网页自己生成的链接系统下载不了,开始失败)。
 * User-Agent 和 Cookie 从标签带过去;重名时在扩展名前加「 (2)」。列表、取消、删除、打开都问系统要。
 */
class BrowserDownloads(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)

    /** 开始下载;返回保存的文件名。链接不支持、开始失败或系统没有下载服务时为 null。 */
    fun enqueue(url: String, userAgent: String, contentDisposition: String?, mime: String?): String? {
        val m = manager ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val name = DownloadNames.unique(DownloadNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mime)), existingNames(m))
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        mime?.takeIf { it.isNotBlank() }?.let(request::setMimeType)
        userAgent.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("User-Agent", it) }
        WebTab.cookieManager().getCookie(url)?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        return runCatching { m.enqueue(request) }.getOrNull()?.let { name }
    }

    /** 这个 App 的下载,最新的在前,至多 [limit] 条。 */
    suspend fun list(limit: Int = LIMIT): List<BrowserDownload> = withContext(Dispatchers.IO) {
        val m = manager ?: return@withContext emptyList()
        runCatching {
            m.query(DownloadManager.Query()).use { c ->
                val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val title = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val soFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val mime = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)
                val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val out = ArrayList<BrowserDownload>()
                while (c.moveToNext()) {
                    out += BrowserDownload(
                        id = c.getLong(id),
                        title = c.getString(title).orEmpty().ifBlank { DOWNLOAD },
                        status = c.getInt(status),
                        soFar = c.getLong(soFar).coerceAtLeast(0),
                        total = c.getLong(total),
                        mime = c.getString(mime),
                        reason = c.getInt(reason),
                    )
                }
                out.sortedByDescending { it.id }.take(limit)
            }
        }.getOrDefault(emptyList())
    }

    /** 取消下载或删掉记录(系统会把已下载的文件一起删掉)。 */
    fun remove(id: Long) {
        runCatching { manager?.remove(id) }
    }

    /** 已下载文件的 content:// 地址;没有完成时为 null。 */
    fun contentUri(id: Long): Uri? = runCatching { manager?.getUriForDownloadedFile(id) }.getOrNull()

    private fun existingNames(m: DownloadManager): Set<String> {
        val names = HashSet<String>()
        runCatching {
            m.query(DownloadManager.Query()).use { c ->
                val i = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                if (i >= 0) while (c.moveToNext()) c.getString(i)?.let(names::add)
            }
        }
        return names
    }

    companion object {
        const val LIMIT = 50
        private const val DOWNLOAD = "download"
    }
}

/** 下载文件名:去掉路径分隔符和系统不认的字符,太长时截短(保留扩展名);重名时加「 (2)」。 */
object DownloadNames {
    private const val MAX = 100
    private const val BAD = "/\\:*?\"<>|"

    fun sanitize(raw: String): String {
        val cleaned = raw.map { if (it.isISOControl() || it in BAD) '_' else it }.joinToString("").trim().trimEnd('.')
        val name = cleaned.ifEmpty { "download" }
        return truncate(name)
    }

    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length)
        var i = 2
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun truncate(name: String): String {
        if (name.length <= MAX) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot in 1 until name.length && name.length - dot <= 10) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.dropLast(ext.length)
        return base.take(MAX - ext.length) + ext
    }
}
