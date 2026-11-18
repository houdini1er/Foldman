package dev.foldman.radio

import dev.foldman.BuildConfig
import dev.foldman.deck.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Searches the community Radio Browser directory (radio-browser.info), a free list of about
 * 50 000 stations with no key. Its mirrors come and go, so each is tried in turn.
 */
object RadioBrowser {
    private val servers = listOf("de1", "de2", "fi1", "nl1", "at1")

    /** Stations whose name matches [query], most listened first. Throws when no mirror answers. */
    suspend fun search(query: String): List<RadioStation> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        var last: Exception? = null
        for (server in servers) {
            try {
                val url = "https://$server.api.radio-browser.info/json/stations/search" +
                    "?name=$q&limit=60&hidebroken=true&order=clickcount&reverse=true"
                return@withContext parse(get(url))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No Radio Browser server answered.")
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 6000
        readTimeout = 8000
        setRequestProperty("User-Agent", "Foldman/${BuildConfig.VERSION_NAME}")
        try {
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
            inputStream.bufferedReader().use { it.readText() }
        } finally {
            disconnect()
        }
    }

    private fun parse(json: String): List<RadioStation> {
        val array = JSONArray(json)
        val seen = HashSet<String>()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val stream = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val name = o.optString("name").trim()
            if (stream.isBlank() || name.isBlank() || !seen.add(stream)) return@mapNotNull null
            RadioStation(
                name = name,
                url = stream,
                favicon = o.optString("favicon").trim().takeIf { it.startsWith("http") },
                info = describe(o),
            )
        }
    }

    private fun describe(o: JSONObject): String? {
        val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotBlank() }.take(2)
        val codec = o.optString("codec").takeIf { it.isNotBlank() && it != "UNKNOWN" }
        val kbps = o.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbps" }
        return (listOf(o.optString("countrycode").takeIf { it.isNotBlank() }) + tags + listOf(codec, kbps))
            .filterNotNull().joinToString(" · ").ifBlank { null }
    }

    /** A few French stations to start from, all tested; the user can save more from a search. */
    val defaults = listOf(
        RadioStation("FIP", "https://icecast.radiofrance.fr/fip-hifi.aac", info = "Radio France · éclectique"),
        RadioStation("France Inter", "https://icecast.radiofrance.fr/franceinter-hifi.aac", info = "Radio France · généraliste"),
        RadioStation("France Info", "https://icecast.radiofrance.fr/franceinfo-hifi.aac", info = "Radio France · info"),
        RadioStation("France Culture", "https://icecast.radiofrance.fr/franceculture-hifi.aac", info = "Radio France · culture"),
        RadioStation("France Musique", "https://icecast.radiofrance.fr/francemusique-hifi.aac", info = "Radio France · classique"),
        RadioStation("Mouv'", "https://icecast.radiofrance.fr/mouv-hifi.aac", info = "Radio France · hip-hop"),
        RadioStation("TSF Jazz", "https://tsfjazz.ice.infomaniak.ch/tsfjazz-high.mp3", info = "Jazz"),
        RadioStation("Radio Nova", "http://novazz.ice.infomaniak.ch/novazz-128.mp3", info = "Groove · électro"),
    )

    fun toJson(stations: List<RadioStation>): String = JSONArray().apply {
        stations.forEach { s ->
            put(JSONObject().put("name", s.name).put("url", s.url).putOpt("favicon", s.favicon).putOpt("info", s.info))
        }
    }.toString()

    fun fromJson(json: String): List<RadioStation> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RadioStation(o.getString("name"), o.getString("url"), o.optString("favicon").ifBlank { null }, o.optString("info").ifBlank { null })
        }
    }.getOrDefault(emptyList())
}
