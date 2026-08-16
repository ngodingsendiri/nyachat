package com.startupmini.nyachat.data.remote

import android.util.Log
import com.startupmini.nyachat.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Informasi release terbaru dari GitHub. */
data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val apkUrl: String?,
    val releaseUrl: String,
    /** Catatan rilis (body GitHub API), sudah dibersihkan dari sintaks markdown. */
    val body: String = ""
)

/**
 * Pengecek update otomatis dari GitHub Release (repo ini).
 * - checkLatest(): ambil release terbaru via GitHub API.
 * - isNewer(): bandingkan versi (semantic version) dengan versi terpasang.
 * - downloadApk(): unduh APK ke cache app untuk di-install.
 */
object GitHubUpdateChecker {

    private const val TAG = "UpdateChecker"
    // Path repo dari Constants.Links (SATU sumber kebenaran — audit remote/ 2026-08-13):
    // sebelumnya literal diduplikasi di sini, rename repo tidak merambat ke API update.
    private const val API_URL =
        "https://api.github.com/repos/${Constants.Links.GITHUB_OWNER_REPO}/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Ambil release terbaru dari GitHub. null kalau gagal / belum ada release. */
    suspend fun checkLatest(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(API_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub API ${response.code}")
                    null
                } else {
                    val root = JSONObject(response.body?.string().orEmpty())
                    val tag = root.optString("tag_name", "")
                    val releaseUrl = root.optString("html_url", "")
                    val assets = root.optJSONArray("assets") ?: JSONArray()

                    // Prefer APK release (bertanda tangan); fallback ke APK debug.
                    var releaseApk: String? = null
                    var debugApk: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk")) {
                            if (name.contains("release")) releaseApk = url else debugApk = url
                        }
                    }
                    GitHubRelease(
                        tagName = tag,
                        versionName = tag.removePrefix("r").removePrefix("v"),
                        apkUrl = releaseApk ?: debugApk,
                        releaseUrl = releaseUrl,
                        body = sanitizeReleaseNotes(root.optString("body", ""))
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cek update gagal: ${e.message}")
            null
        }
    }

    /** Bandingkan versi remote vs terpasang (major.minor.patch). */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = parseVersion(remoteVersion) ?: return false
        val current = parseVersion(currentVersion) ?: return false
        return remote[0] > current[0] ||
            (remote[0] == current[0] && remote[1] > current[1]) ||
            (remote[0] == current[0] && remote[1] == current[1] && remote[2] > current[2])
    }

    private fun parseVersion(version: String): List<Int>? {
        val parts = version.trim().removePrefix("r").removePrefix("v").split(".")
            .mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return listOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 }
        )
    }

    /**
     * Unduh APK ke path tujuan (cache app — tanpa izin eksternal).
     * [onProgress] dipanggil dengan progres 0..1 (tidak lebih dari ~4x/detik)
     * — dipakai dialog update untuk menampilkan bar progres.
     */
    suspend fun downloadApk(
        url: String,
        destination: File,
        onProgress: (Float) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Response kosong")
                val total = body.contentLength()
                destination.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = 0L
                        // Throttle: laporkan tiap ~256 KB supaya UI tidak spam-recompose
                        // saat progres di-marshal ke main thread.
                        var lastReported = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0 && (read - lastReported >= 256 * 1024 || read >= total)) {
                                lastReported = read
                                onProgress((read.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        if (total <= 0) onProgress(1f)
                    }
                }
            }
        }
    }

    /**
     * Bersihkan catatan rilis GitHub (markdown) untuk tampil polos di dialog:
     * buang judul/heading, penekanan tebal/miring, dan inline code.
     */
    private fun sanitizeReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("\\*\\*|__"), "")
            .replace(Regex("\\*|`"), "")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "• ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}
