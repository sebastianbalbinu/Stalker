package com.sebas.stalker

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val code: Long, val url: String, val notes: String)

/** Revisa en GitHub si hay una versión nueva de Stalker. */
object Updates {
    private const val API = "https://api.github.com/repos/sebastianbalbinu/Stalker/releases/latest"
    const val PAGE = "https://sebastianbalbinu.github.io/Stalker/"

    val available = MutableStateFlow<UpdateInfo?>(null)
    val status = MutableStateFlow("")
    val dismissed = MutableStateFlow(false)

    fun currentCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    suspend fun check(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("stalker", Context.MODE_PRIVATE)
        val last = prefs.getLong("updateCheck", 0L)
        if (!force && System.currentTimeMillis() - last < 6 * 3_600_000L) return@withContext
        status.value = tr("Buscando…")
        if (force) dismissed.value = false
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            prefs.edit().putLong("updateCheck", System.currentTimeMillis()).apply()
            val o = JSONObject(text)
            val tag = o.optString("tag_name").removePrefix("v")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: 0L
            val assets = o.optJSONArray("assets")
            var url = "https://github.com/sebastianbalbinu/Stalker/releases/latest/download/Stalker.apk"
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url", url)
                }
            }
            if (code > currentCode(ctx)) {
                available.value = UpdateInfo(tag, code, url, o.optString("body"))
                status.value = tr("Hay una versión nueva: %s", tag)
            } else {
                available.value = null
                status.value = tr("Tienes la última versión")
            }
        } catch (e: Exception) {
            status.value = tr("No se pudo revisar (¿sin internet?)")
        }
    }
}
