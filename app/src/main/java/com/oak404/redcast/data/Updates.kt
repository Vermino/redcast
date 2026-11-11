package com.oak404.redcast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A published release with an APK attached. */
data class Release(val version: String, val notes: String, val apkUrl: String, val apkBytes: Long)

/**
 * Finds and fetches new versions from the project's GitHub releases. Android's installer only
 * accepts an update signed with the same key as the installed app, so a swapped file can't install.
 */
class Updates(private val http: OkHttpClient, private val latestUrl: String = LATEST_URL) {

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Redcast")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("Couldn't check for updates (${response.code})")
            // A captive portal or proxy answers 200 with a page of its own.
            try {
                parseRelease(JSONObject(response.body?.string().orEmpty()))
            } catch (e: JSONException) {
                throw IOException("Couldn't read the update reply", e)
            }
        }
    }

    /** Downloads [release] into [dir], reporting progress from 0 to 1. */
    suspend fun download(release: Release, dir: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "redcast-${release.version}.apk")
            val request = Request.Builder().url(release.apkUrl).header("User-Agent", "Redcast").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val body = response.body ?: throw IOException("Download failed")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkBytes
                var done = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
                if (release.apkBytes > 0 && done != release.apkBytes) {
                    file.delete()
                    throw IOException("Download was incomplete")
                }
            }
            file
        }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/Vermino/redcast/releases/latest"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** Null for drafts, pre-releases, and releases without an APK. */
internal fun parseRelease(json: JSONObject): Release? {
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
    val version = json.optString("tag_name").removePrefix("v").ifEmpty { return null }
    val assets = json.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
    val url = apk.optString("browser_download_url").ifEmpty { return null }
    return Release(version, json.optString("body").trim(), url, apk.optLong("size"))
}

/** Whether [remote] is a later version than [local], comparing "1.10.2" style numbers part by part. */
internal fun isNewer(remote: String, local: String): Boolean {
    fun parts(version: String) = version.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    val a = parts(remote)
    val b = parts(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}
