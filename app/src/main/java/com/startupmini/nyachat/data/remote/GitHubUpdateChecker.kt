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
    val releaseUrl: String
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
                        releaseUrl = releaseUrl
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

    /** Unduh APK ke path tujuan (cache app — tanpa izin eksternal). */
    suspend fun downloadApk(url: String, destination: File) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Response kosong")
                destination.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
