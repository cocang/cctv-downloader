package com.chouchang.cctvdl.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/** 已保存文件的操作 */
object FileActions {
    fun exists(ctx: Context, uri: String): Boolean {
        if (uri.isEmpty()) return false
        val u = Uri.parse(uri)
        if (u.scheme == "file") return File(u.path ?: return false).exists()
        return runCatching { ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    fun open(ctx: Context, uri: String): Boolean = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun share(ctx: Context, uri: String, title: String): Boolean = runCatching {
        val u = Uri.parse(uri)
        val s = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u).putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        s.clipData = ClipData.newRawUri(title, u)
        ctx.startActivity(Intent.createChooser(s, "分享视频").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    /** 打开所在位置：自定义文件夹用系统文件管理器打开该目录，其余打开「下载」 */
    fun openLocation(ctx: Context, treeUri: String?): Boolean = runCatching {
        if (!treeUri.isNullOrEmpty()) {
            val tree = Uri.parse(treeUri)
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        true
    }.getOrElse {
        runCatching { ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    fun delete(ctx: Context, uri: String): Boolean = runCatching {
        val u = Uri.parse(uri)
        when {
            u.scheme == "file" -> File(u.path!!).delete()
            DocumentsContract.isDocumentUri(ctx, u) -> DocumentsContract.deleteDocument(ctx.contentResolver, u)
            else -> ctx.contentResolver.delete(u, null, null) > 0
        }
    }.getOrDefault(false)
}

object Fmt {
    fun size(b: Long): String = when {
        b <= 0 -> "—"
        b >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.ROOT, "%d KB", (b / 1024).coerceAtLeast(1))
    }

    fun speed(bps: Double): String = if (bps <= 0) "" else size(bps.toLong()) + "/s"

    fun duration(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        val h = s / 3600; val m = s % 3600 / 60; val r = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, r) else String.format(Locale.ROOT, "%d:%02d", m, r)
    }

    fun eta(sec: Double): String {
        if (sec <= 0 || sec.isNaN() || sec.isInfinite()) return ""
        val s = sec.toLong()
        return when {
            s < 60 -> "剩余 ${s} 秒"
            s < 3600 -> "剩余 ${s / 60} 分钟"
            else -> "剩余 ${s / 3600} 小时 ${s % 3600 / 60} 分"
        }
    }

    fun date(publishedAt: String): String = publishedAt.take(10)

    fun ago(ms: Long): String {
        if (ms <= 0) return ""
        val d = (System.currentTimeMillis() - ms) / 1000
        return when {
            d < 60 -> "刚刚"
            d < 3600 -> "${d / 60} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            d < 86400 * 7 -> "${d / 86400} 天前"
            else -> java.text.SimpleDateFormat("M月d日", Locale.CHINA).format(java.util.Date(ms))
        }
    }
}
