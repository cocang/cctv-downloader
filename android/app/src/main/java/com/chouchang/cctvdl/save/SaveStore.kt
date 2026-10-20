package com.chouchang.cctvdl.save

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.chouchang.cctvdl.LocalServer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 规范化保存：
 *  - 位置：系统「下载」/「Movies」（MediaStore，Android 10+ 免权限）或用户授权的任意文件夹（SAF）
 *  - 目录：根文件夹 + 可选「剧名」子文件夹；文件名按命名规则生成并清理非法字符
 *  - 同名：自动编号 / 覆盖 / 跳过
 *  - 写入期间对其他应用不可见（IS_PENDING 或 .part 临时文件），完成后校验大小再公开
 *  - 写入 MediaStore 元数据（标题、来源页面），可选同时保存封面
 *  - 开始前与写入中检查剩余空间，避免写满存储
 */
class SaveStore(context: Context) : LocalServer.Sink {
    private val ctx = context.applicationContext
    private val cr: ContentResolver = ctx.contentResolver

    override fun create(mime: String?, meta: String?): LocalServer.Output {
        val m = SaveMeta.parse(meta ?: "{}")
        val s = SaveSettings.load(ctx)
        val type = mime?.takeIf { it.isNotEmpty() } ?: "video/mp4"
        val stem = Naming.fileStem(m, s.naming)
        val sub = Naming.subFolder(m, s)
        val name = "$stem.${m.ext}"
        return when {
            s.effectiveLocation == SaveLocation.CUSTOM -> SafOutput(Uri.parse(s.customTree), s.customLabel, sub, stem, m.ext, type, s, m)
            Build.VERSION.SDK_INT >= 29 -> StoreOutput(s.effectiveLocation, sub, stem, m.ext, type, s, m)
            else -> LegacyOutput(s.effectiveLocation, sub, name, type, s, m)
        }
    }

    // ---------------------------------------------------------------------
    // 公共工具
    // ---------------------------------------------------------------------
    private fun rootDirName(loc: SaveLocation) =
        if (loc == SaveLocation.MOVIES) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS

    private fun rootLabel(loc: SaveLocation) = if (loc == SaveLocation.MOVIES) "Movies" else "下载"

    private fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }
        .getOrDefault(Long.MAX_VALUE)

    private fun checkSpace() {
        val free = freeBytes()
        if (free < MIN_FREE_START) throw IOException("存储空间不足：仅剩 ${free / 1048576} MB，请清理后重试")
    }

    /** 写入过程中定期检查剩余空间 */
    private inner class GuardedStream(out: OutputStream, private val check: Boolean) : FilterOutputStream(out) {
        var written = 0L
        private var sinceCheck = 0L
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
            sinceCheck += len
            if (check && sinceCheck >= CHECK_EVERY) {
                sinceCheck = 0
                if (freeBytes() < MIN_FREE_WRITING) throw IOException("存储空间不足，已停止保存")
            }
        }
        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
    }

    private fun describe(uri: Uri, name: String, path: String, size: Long, location: String) = linkedMapOf(
        "uri" to uri.toString(), "name" to name, "path" to path, "size" to size.toString(), "location" to location,
    )

    private fun fetchCover(url: String): ByteArray? = runCatching {
        if (url.isEmpty()) return null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Referer", "https://tv.cctv.com/")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
        c.inputStream.use { it.readBytes() }.takeIf { it.size > 512 }
    }.getOrNull()

    // ---------------------------------------------------------------------
    // Android 10+：MediaStore（Downloads / Video）
    // ---------------------------------------------------------------------
    @RequiresApi(29)
    private inner class StoreOutput(
        private val loc: SaveLocation, sub: String, stem: String, private val ext: String, mime: String,
        private val s: SaveSettings, private val meta: SaveMeta,
    ) : LocalServer.Output {
        private val collection: Uri = if (loc == SaveLocation.MOVIES) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private val relPath = rootDirName(loc) + (if (sub.isEmpty()) "" else "/$sub") + "/"
        private val display = "$stem.$ext"
        private val label = rootLabel(loc) + (if (sub.isEmpty()) "" else "/$sub")
        private var existing: Map<String, String>? = null
        private lateinit var uri: Uri
        private lateinit var stream: GuardedStream

        init {
            val found = findExisting(display)
            when {
                found != null && s.conflict == ConflictRule.SKIP -> existing = describe(found.first, display, "$label/$display", found.second, label)
                found != null && s.conflict == ConflictRule.OVERWRITE -> runCatching { cr.delete(found.first, null, null) }
                else -> {}
            }
            if (existing == null) {
                checkSpace()
                // 自动编号：MediaStore 遇到同名会自己改名，这里先算好更规范的「名称 (2)」
                var name = display
                if (s.conflict == ConflictRule.RENAME) {
                    var i = 2
                    while (findExisting(name) != null && i < 1000) name = "$stem ($i).$ext".also { i++ }
                }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.TITLE, meta.title)
                }
                val extra = ContentValues(cv).apply {
                    if (loc == SaveLocation.DOWNLOADS) {
                        if (meta.pageUrl.isNotEmpty()) put(MediaStore.DownloadColumns.DOWNLOAD_URI, meta.pageUrl)
                        put(MediaStore.DownloadColumns.REFERER_URI, "https://tv.cctv.com/")
                    } else {
                        put(MediaStore.Video.VideoColumns.DESCRIPTION, listOf(meta.series, meta.channel, meta.quality).filter { it.isNotEmpty() }.joinToString(" · "))
                    }
                }
                val u = runCatching { cr.insert(collection, extra) }.getOrNull()
                    ?: runCatching { cr.insert(collection, cv) }.getOrElse { throw IOException("无法创建文件：${it.message}") }
                    ?: throw IOException("无法创建文件")
                uri = u
                val raw = cr.openOutputStream(uri, "w") ?: run { cr.delete(uri, null, null); throw IOException("无法写入文件") }
                stream = GuardedStream(BufferedOutputStream(raw, 1 shl 20), true)
            }
        }

        private fun findExisting(name: String): Pair<Uri, Long>? = runCatching {
            cr.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relPath, name), null)?.use { c ->
                if (c.moveToFirst()) Pair(Uri.withAppendedPath(collection, c.getLong(0).toString()), c.getLong(1)) else null
            }
        }.getOrNull()

        override fun existing() = existing
        override fun target() = if (existing != null) null else linkedMapOf("name" to display, "path" to "$label/$display")
        override fun stream(): OutputStream = stream

        override fun commit(): Map<String, String> {
            stream.close()
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            var name = display
            var size = stream.written
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: display
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (size in 1 until stream.written) {
                runCatching { cr.delete(uri, null, null) }
                throw IOException("保存校验失败：文件不完整（${size}/${stream.written} 字节）")
            }
            if (s.saveCover && s.coverSupported) saveCover(name.substringBeforeLast('.'))
            return describe(uri, name, "$label/$name", stream.written, label)
        }

        private fun saveCover(stem: String) {
            val bytes = fetchCover(meta.cover) ?: return
            runCatching {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                }
                val u = cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: return
                cr.openOutputStream(u, "w")?.use { it.write(bytes) }
            }.onFailure { Log.w(TAG, "cover", it) }
        }

        override fun abort() {
            if (existing != null) return
            runCatching { stream.close() }
            runCatching { cr.delete(uri, null, null) }
        }
    }

    // ---------------------------------------------------------------------
    // Android 8 / 9：公共目录文件（需要存储权限）
    // ---------------------------------------------------------------------
    private inner class LegacyOutput(
        loc: SaveLocation, sub: String, private val name: String, private val mime: String,
        private val s: SaveSettings, private val meta: SaveMeta,
    ) : LocalServer.Output {
        @Suppress("DEPRECATION")
        private val dir = File(Environment.getExternalStoragePublicDirectory(rootDirName(loc)), sub)
        private val label = rootLabel(loc) + (if (sub.isEmpty()) "" else "/$sub")
        private var existing: Map<String, String>? = null
        private lateinit var part: File
        private lateinit var stream: GuardedStream

        init {
            val dest = File(dir, name)
            if (dest.exists() && s.conflict == ConflictRule.SKIP) {
                existing = describe(Uri.fromFile(dest), name, "$label/$name", dest.length(), label)
            } else {
                checkSpace()
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建文件夹，请在系统设置中允许本应用访问存储")
                part = File(dir, ".${System.currentTimeMillis()}.part")
                stream = GuardedStream(BufferedOutputStream(FileOutputStream(part), 1 shl 20), true)
            }
        }

        override fun existing() = existing
        override fun target() = if (existing != null) null else linkedMapOf("name" to name, "path" to "$label/$name")
        override fun stream(): OutputStream = stream

        override fun commit(): Map<String, String> {
            stream.close()
            if (part.length() != stream.written) { part.delete(); throw IOException("保存校验失败：文件不完整") }
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var dest = File(dir, name)
            when (s.conflict) {
                ConflictRule.OVERWRITE -> if (dest.exists()) dest.delete()
                else -> { var i = 2; while (dest.exists()) dest = File(dir, "$stem ($i).$ext").also { i++ } }
            }
            if (!part.renameTo(dest)) throw IOException("无法保存文件")
            val paths = mutableListOf(dest.absolutePath)
            if (s.saveCover) fetchCover(meta.cover)?.let { b ->
                runCatching { File(dir, dest.nameWithoutExtension + ".jpg").also { it.writeBytes(b); paths += it.absolutePath } }
            }
            var scanned: Uri? = null
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { p, u -> if (p == dest.absolutePath) { scanned = u; latch.countDown() } }
            runCatching { latch.await(8, TimeUnit.SECONDS) }
            return describe(scanned ?: Uri.fromFile(dest), dest.name, "$label/${dest.name}", dest.length(), label)
        }

        override fun abort() {
            if (existing != null) return
            runCatching { stream.close() }
            runCatching { part.delete() }
        }
    }

    // ---------------------------------------------------------------------
    // 自定义文件夹（Storage Access Framework）
    // ---------------------------------------------------------------------
    private inner class SafOutput(
        tree: Uri, rootName: String, sub: String, private val stem: String, private val ext: String, private val mime: String,
        private val s: SaveSettings, private val meta: SaveMeta,
    ) : LocalServer.Output {
        private val label = rootName.ifEmpty { "自定义文件夹" } + (if (sub.isEmpty()) "" else "/$sub")
        private val dirDoc: Uri
        private var existing: Map<String, String>? = null
        private lateinit var doc: Uri
        private lateinit var stream: GuardedStream
        private var finalName = "$stem.$ext"

        init {
            var dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (part in sub.split('/').filter { it.isNotEmpty() }) {
                dir = findChild(tree, dir, part)?.first
                    ?: runCatching { DocumentsContract.createDocument(cr, dir, DocumentsContract.Document.MIME_TYPE_DIR, part) }.getOrNull()
                    ?: throw IOException("无法在所选文件夹中创建「$part」，请在设置中重新选择保存位置")
            }
            dirDoc = dir
            val found = findChild(tree, dirDoc, finalName)
            when {
                found != null && s.conflict == ConflictRule.SKIP -> existing = describe(found.first, finalName, "$label/$finalName", found.second, label)
                found != null && s.conflict == ConflictRule.OVERWRITE -> runCatching { DocumentsContract.deleteDocument(cr, found.first) }
                found != null -> { var i = 2; while (findChild(tree, dirDoc, "$stem ($i).$ext") != null && i < 1000) i++; finalName = "$stem ($i).$ext" }
            }
            if (existing == null) {
                doc = runCatching { DocumentsContract.createDocument(cr, dirDoc, mime, finalName) }.getOrNull()
                    ?: throw IOException("无法在所选文件夹中创建文件，请在设置中重新授权")
                val raw = cr.openOutputStream(doc, "w") ?: throw IOException("无法写入所选文件夹")
                stream = GuardedStream(BufferedOutputStream(raw, 1 shl 20), false)
            }
        }

        private fun findChild(tree: Uri, parent: Uri, name: String): Pair<Uri, Long>? = runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name)
                    return@runCatching Pair(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), if (c.isNull(2)) 0L else c.getLong(2))
                null
            }
        }.getOrNull()

        override fun existing() = existing
        override fun target() = if (existing != null) null else linkedMapOf("name" to finalName, "path" to "$label/$finalName")
        override fun stream(): OutputStream = stream

        override fun commit(): Map<String, String> {
            stream.close()
            var name = finalName
            cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
            if (s.saveCover) fetchCover(meta.cover)?.let { b ->
                runCatching {
                    DocumentsContract.createDocument(cr, dirDoc, "image/jpeg", name.substringBeforeLast('.') + ".jpg")
                        ?.let { u -> cr.openOutputStream(u, "w")?.use { it.write(b) } }
                }
            }
            return describe(doc, name, "$label/$name", stream.written, label)
        }

        override fun abort() {
            if (existing != null) return
            runCatching { stream.close() }
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
        }
    }

    companion object {
        private const val TAG = "CCTVDL"
        private const val MIN_FREE_START = 300L * 1024 * 1024
        private const val MIN_FREE_WRITING = 64L * 1024 * 1024
        private const val CHECK_EVERY = 32L * 1024 * 1024
    }
}
