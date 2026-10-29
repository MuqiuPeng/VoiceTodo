package app.voicetodo.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Large-file download shared by the updater and the speech packs: resumes a
 * partial `<dest>.part` with a Range request, then checks size and SHA-256
 * before renaming it into place, so `dest` existing means it is complete.
 */
object Download {
    const val USER_AGENT = "VoiceTodo"

    suspend fun fetch(
        url: String,
        dest: File,
        size: Long,
        sha256: String?,
        onProgress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (dest.exists() && dest.length() == size) return@withContext dest
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        if (part.length() > size) part.delete()

        var have = part.length()
        onProgress(have)
        if (have < size) {
            val conn = open(url)
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            try {
                when (conn.responseCode) {
                    206 -> Unit
                    200 -> { have = 0; part.delete() } // Server ignored the range; start over.
                    else -> error("下载失败 HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastReport > 1_000_000) {
                                lastReport = have
                                onProgress(have)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        if (part.length() != size) error("文件大小不对（${part.length()} / $size）")
        if (sha256 != null && !sha256(part).equals(sha256, ignoreCase = true)) {
            part.delete()
            error("文件校验失败，已删除，请重试")
        }
        if (!part.renameTo(dest)) error("无法保存文件")
        onProgress(size)
        dest
    }

    fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
