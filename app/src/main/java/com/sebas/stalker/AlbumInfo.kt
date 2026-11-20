package com.sebas.stalker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** El librito del disco: datos de MusicBrainz y una reseña de Wikipedia. */
data class AlbumInfo(
    val date: String,
    val label: String,
    val country: String,
    val format: String,
    /** Rol → nombres (Producción → [..], Guitarra → [..]). */
    val credits: List<Pair<String, List<String>>>,
    val summary: String,
    val wikiUrl: String,
    val mbUrl: String,
)

/** Lo que se sabe de un artista. */
data class ArtistInfo(
    val kind: String,
    val area: String,
    val years: String,
    val summary: String,
    val wikiUrl: String,
    val mbUrl: String,
)

object Liner {
    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.lowercase().trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    private fun dir(ctx: Context) = File(ctx.filesDir, "liner").apply { mkdirs() }
    private fun lang() = if (I18n.english) "en" else "es"

    // ---------- Wikipedia ----------

    /** Resumen de Wikipedia a partir del enlace a Wikidata que trae MusicBrainz. */
    private fun wikipedia(rels: JSONArray?): Pair<String, String> {
        if (rels == null) return "" to ""
        var wikidata: String? = null
        var wikiDirect: String? = null
        for (i in 0 until rels.length()) {
            val r = rels.getJSONObject(i)
            val url = r.optJSONObject("url")?.optString("resource").orEmpty()
            when (r.optString("type")) {
                "wikidata" -> wikidata = url.substringAfterLast('/')
                "wikipedia" -> wikiDirect = url
            }
        }
        var title: String? = null
        var lang = lang()
        if (wikidata != null) {
            val body = MusicBrainz.http(
                "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$wikidata&props=sitelinks&sitefilter=${lang()}wiki|enwiki&format=json"
            )
            val links = body?.let { JSONObject(it).optJSONObject("entities")?.optJSONObject(wikidata)?.optJSONObject("sitelinks") }
            val mine = links?.optJSONObject("${lang()}wiki")?.optString("title")
            val en = links?.optJSONObject("enwiki")?.optString("title")
            if (!mine.isNullOrBlank()) title = mine else if (!en.isNullOrBlank()) { title = en; lang = "en" }
        }
        if (title == null && wikiDirect != null) {
            lang = wikiDirect.substringAfter("//").substringBefore('.')
            title = java.net.URLDecoder.decode(wikiDirect.substringAfterLast('/'), "UTF-8")
        }
        if (title == null) return "" to ""
        val path = MusicBrainz.enc(title.replace(' ', '_')).replace("+", "%20")
        val sum = MusicBrainz.http("https://$lang.wikipedia.org/api/rest_v1/page/summary/$path") ?: return "" to ""
        val o = JSONObject(sum)
        val url = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page").orEmpty()
        return o.optString("extract") to url
    }

    // ---------- Álbum ----------

    fun cachedAlbum(ctx: Context, a: Album): AlbumInfo? = read(File(dir(ctx), "a" + hash(a.artist + "|" + a.title) + lang() + ".json"))?.let { o ->
        val cr = o.optJSONArray("credits") ?: JSONArray()
        AlbumInfo(
            o.optString("date"), o.optString("label"), o.optString("country"), o.optString("format"),
            (0 until cr.length()).map { i ->
                val c = cr.getJSONObject(i)
                val ns = c.getJSONArray("n")
                c.getString("r") to (0 until ns.length()).map { ns.getString(it) }
            },
            o.optString("summary"), o.optString("wiki"), o.optString("mb"),
        )
    }

    private fun read(f: File): JSONObject? = try { if (f.exists()) JSONObject(f.readText()) else null } catch (e: Exception) { null }

    /** Busca el librito del disco. null si no hay internet o MusicBrainz no lo conoce. */
    suspend fun album(ctx: Context, a: Album): AlbumInfo? = withContext(Dispatchers.IO) {
        cachedAlbum(ctx, a)?.let { return@withContext it }
        try {
            val group = MusicBrainz.releaseGroup(a.title, a.artist) ?: return@withContext null
            val gid = group.getString("id")
            val full = MusicBrainz.ws("release-group/$gid?inc=url-rels")
            val (summary, wiki) = try { wikipedia(full?.optJSONArray("relations")) } catch (e: Exception) { "" to "" }
            // La edición original: oficial y la más antigua.
            val rels = MusicBrainz.ws("release?release-group=$gid&inc=labels+media&limit=25")?.optJSONArray("releases") ?: JSONArray()
            var best: JSONObject? = null
            for (i in 0 until rels.length()) {
                val r = rels.getJSONObject(i)
                if (r.optString("status") != "Official" && best != null) continue
                val d = r.optString("date")
                if (best == null || (d.isNotBlank() && (best.optString("date").isBlank() || d < best.optString("date")))) best = r
            }
            val label = best?.optJSONArray("label-info")?.let { li ->
                (0 until li.length()).mapNotNull { li.getJSONObject(it).optJSONObject("label")?.optString("name") }.distinct().joinToString(", ")
            }.orEmpty()
            val format = best?.optJSONArray("media")?.let { m ->
                (0 until m.length()).map { m.getJSONObject(it).optString("format") }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount().map { (f, n) -> if (n > 1) "$n × $f" else f }.joinToString(" + ")
            }.orEmpty()
            val credits = best?.let { credits(it.getString("id")) } ?: emptyList()
            val info = AlbumInfo(
                date = group.optString("first-release-date").ifBlank { best?.optString("date").orEmpty() },
                label = label,
                country = best?.optString("country").orEmpty(),
                format = format,
                credits = credits,
                summary = summary,
                wikiUrl = wiki,
                mbUrl = "https://musicbrainz.org/release-group/$gid",
            )
            val o = JSONObject().put("date", info.date).put("label", info.label).put("country", info.country)
                .put("format", info.format).put("summary", info.summary).put("wiki", info.wikiUrl).put("mb", info.mbUrl)
                .put("credits", JSONArray().apply {
                    credits.forEach { (r, n) -> put(JSONObject().put("r", r).put("n", JSONArray(n))) }
                })
            File(dir(ctx), "a" + hash(a.artist + "|" + a.title) + lang() + ".json").writeText(o.toString())
            info
        } catch (e: Exception) {
            null
        }
    }

    /** Créditos de la edición: producción, ingeniería, mezcla y músicos. */
    private suspend fun credits(releaseId: String): List<Pair<String, List<String>>> {
        val r = MusicBrainz.ws("release/$releaseId?inc=artist-rels+recordings+recording-level-rels") ?: return emptyList()
        val map = LinkedHashMap<String, LinkedHashSet<String>>()
        fun add(rels: JSONArray?) {
            if (rels == null) return
            for (i in 0 until rels.length()) {
                val x = rels.getJSONObject(i)
                val name = x.optJSONObject("artist")?.optString("name") ?: continue
                val type = x.optString("type")
                val attrs = x.optJSONArray("attributes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val role = when (type) {
                    "instrument" -> attrs.firstOrNull { it != "additional" && it != "guest" }?.let { role(it) } ?: continue
                    "vocal" -> role(attrs.firstOrNull { it.endsWith("vocals") } ?: "vocals")
                    else -> role(type)
                }
                map.getOrPut(role) { LinkedHashSet() }.add(name)
            }
        }
        add(r.optJSONArray("relations"))
        r.optJSONArray("media")?.let { media ->
            for (m in 0 until media.length()) {
                val tracks = media.getJSONObject(m).optJSONArray("tracks") ?: continue
                for (t in 0 until tracks.length()) add(tracks.getJSONObject(t).optJSONObject("recording")?.optJSONArray("relations"))
            }
        }
        val order = listOf("Producción", "Grabación", "Ingeniería", "Mezcla", "Masterización")
        return map.entries.sortedBy { e -> order.indexOf(e.key).let { if (it < 0) 99 else it } }
            .map { tr(it.key) to it.value.toList().take(8) }
            .take(16)
    }

    private fun role(mb: String): String = when (mb.lowercase()) {
        "producer" -> "Producción"
        "engineer", "audio" -> "Ingeniería"
        "recording" -> "Grabación"
        "mix" -> "Mezcla"
        "mastering" -> "Masterización"
        "arranger", "orchestrator" -> "Arreglos"
        "conductor" -> "Dirección"
        "vocals", "lead vocals" -> "Voz"
        "background vocals" -> "Coros"
        "guitar", "electric guitar", "acoustic guitar", "lead guitar", "rhythm guitar" -> "Guitarra"
        "bass guitar", "bass", "electric bass guitar", "double bass" -> "Bajo"
        "drums (drum set)", "drums", "drum set" -> "Batería"
        "percussion" -> "Percusión"
        "piano" -> "Piano"
        "keyboard", "keyboards", "organ", "hammond organ" -> "Teclados"
        "synthesizer" -> "Sintetizador"
        "saxophone", "tenor saxophone", "alto saxophone" -> "Saxofón"
        "trumpet" -> "Trompeta"
        "violin" -> "Violín"
        "cello" -> "Chelo"
        "strings" -> "Cuerdas"
        else -> mb.replaceFirstChar { it.uppercase() }
    }

    // ---------- Artista ----------

    suspend fun artist(ctx: Context, name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "r" + hash(name) + lang() + ".json")
        read(f)?.let { o ->
            return@withContext ArtistInfo(o.optString("kind"), o.optString("area"), o.optString("years"), o.optString("summary"), o.optString("wiki"), o.optString("mb"))
        }
        try {
            val q = "artist:\"${name.replace("\"", " ")}\""
            val found = MusicBrainz.ws("artist/?query=${MusicBrainz.enc(q)}&limit=3")?.optJSONArray("artists") ?: return@withContext null
            val hit = (0 until found.length()).map { found.getJSONObject(it) }.firstOrNull { it.optInt("score") >= 90 } ?: return@withContext null
            val id = hit.getString("id")
            val full = MusicBrainz.ws("artist/$id?inc=url-rels")
            val (summary, wiki) = try { wikipedia(full?.optJSONArray("relations")) } catch (e: Exception) { "" to "" }
            val span = hit.optJSONObject("life-span")
            val begin = span?.optString("begin").orEmpty().take(4)
            val end = span?.optString("end").orEmpty().take(4)
            val kind = when (hit.optString("type")) {
                "Group" -> tr("Banda")
                "Person" -> tr("Artista")
                "Orchestra" -> tr("Orquesta")
                "Choir" -> tr("Coro")
                else -> ""
            }
            val info = ArtistInfo(
                kind = kind,
                area = hit.optJSONObject("area")?.optString("name").orEmpty(),
                years = when {
                    begin.isNotBlank() && end.isNotBlank() -> "$begin – $end"
                    begin.isNotBlank() -> tr("desde %s", begin)
                    else -> ""
                },
                summary = summary,
                wikiUrl = wiki,
                mbUrl = "https://musicbrainz.org/artist/$id",
            )
            f.writeText(
                JSONObject().put("kind", info.kind).put("area", info.area).put("years", info.years)
                    .put("summary", info.summary).put("wiki", info.wikiUrl).put("mb", info.mbUrl).toString()
            )
            info
        } catch (e: Exception) {
            null
        }
    }
}
