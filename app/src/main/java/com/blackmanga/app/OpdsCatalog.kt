package com.blackmanga.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpsURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String = "",
    val title: String = ""
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val summary: String,
    val links: List<OpdsLink>
) {
    val navigationLink: OpdsLink?
        get() = links.firstOrNull {
            it.type.contains("atom+xml", ignoreCase = true) ||
                it.type.contains("opds+json", ignoreCase = true) ||
                it.rel == "subsection" || it.rel == "collection"
        }

    val acquisitionLink: OpdsLink?
        get() = links.firstOrNull { it.rel.startsWith("http://opds-spec.org/acquisition") ||
            it.rel.startsWith("https://opds-spec.org/acquisition") }

    val openLink: OpdsLink?
        get() = navigationLink ?: acquisitionLink ?: links.firstOrNull {
            it.rel == "alternate" || it.rel == "self"
        } ?: links.firstOrNull { !it.rel.contains("image", ignoreCase = true) }
}

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val links: List<OpdsLink>,
    val searchTemplate: String? = null
) {
    val nextLink: OpdsLink?
        get() = links.firstOrNull { it.rel == "next" }
}

data class SavedManga(
    val id: String,
    val title: String,
    val summary: String,
    val url: String
)

object BlackMangaLibrary {
    private const val PREFS = "black_manga"
    private const val KEY_LIBRARY = "saved_manga_json"

    fun load(context: Context): List<SavedManga> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIBRARY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                SavedManga(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    summary = obj.optString("summary"),
                    url = obj.optString("url")
                ).takeIf { it.title.isNotBlank() && it.url.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, item: SavedManga): List<SavedManga> {
        val updated = (load(context).filterNot { it.id == item.id } + item).takeLast(500)
        val array = JSONArray()
        updated.forEach { saved ->
            array.put(JSONObject().apply {
                put("id", saved.id)
                put("title", saved.title)
                put("summary", saved.summary)
                put("url", saved.url)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIBRARY, array.toString()).apply()
        return updated
    }

    fun remove(context: Context, id: String): List<SavedManga> {
        val updated = load(context).filterNot { it.id == id }
        val array = JSONArray()
        updated.forEach { saved ->
            array.put(JSONObject().apply {
                put("id", saved.id)
                put("title", saved.title)
                put("summary", saved.summary)
                put("url", saved.url)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIBRARY, array.toString()).apply()
        return updated
    }
}

object OpdsCatalogClient {
    private const val MAX_DOCUMENT_BYTES = 3 * 1024 * 1024

    fun load(rawUrl: String): OpdsFeed {
        val uri = validateHttps(rawUrl)
        val response = fetchDocument(uri)
        val first = response.bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
        val feed = if (first == '{') {
            parseJsonFeed(response.bytes, response.finalUrl)
        } else {
            parseAtomFeed(response.bytes, response.finalUrl)
        }
        val searchLink = feed.links.firstOrNull {
            it.rel == "search" && it.type.contains("opensearchdescription+xml", ignoreCase = true)
        }
        val template = searchLink?.let {
            try { loadSearchTemplate(it.href) } catch (_: Exception) { null }
        }
        return feed.copy(searchTemplate = template)
    }

    fun searchUrl(template: String, query: String): String? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        var result = template
            .replace("{searchTerms}", encoded)
            .replace("{searchTerms?}", encoded)
            .replace("{searchTerms}", encoded, ignoreCase = true)
        if (result == template && template.contains("{")) return null
        return try {
            validateHttps(result)
            result
        } catch (_: Exception) {
            null
        }
    }

    private data class HttpDocument(val bytes: ByteArray, val finalUrl: String)

    private fun validateHttps(rawUrl: String): String {
        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("Endereço inválido. Usa uma URL completa.")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "O catálogo precisa de usar HTTPS e um nome de servidor válido."
        }
        require(uri.userInfo == null) { "Não coloques credenciais dentro do endereço." }
        return uri.toASCIIString()
    }

    private fun fetchDocument(rawUrl: String): HttpDocument {
        val url = validateHttps(rawUrl)
        var connection: HttpsURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "BlackManga/0.2 (OPDS-compatible client)")
            connection.setRequestProperty("Accept", "application/atom+xml, application/opds+json, application/json, application/xml, text/xml;q=0.9")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("O servidor respondeu HTTP $code.")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_DOCUMENT_BYTES) {
                        throw IllegalStateException("O catálogo excede o limite de 3 MB para esta versão.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val finalUrl = connection.url.toString()
            validateHttps(finalUrl)
            return HttpDocument(output.toByteArray(), finalUrl)
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadSearchTemplate(searchDescriptionUrl: String): String? {
        val document = fetchDocument(searchDescriptionUrl)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(document.bytes), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("Url", ignoreCase = true)) {
                val type = parser.getAttributeValue(null, "type").orEmpty()
                val template = parser.getAttributeValue(null, "template").orEmpty()
                if (template.isNotBlank() &&
                    (type.contains("atom+xml", ignoreCase = true) || type.contains("opds", ignoreCase = true))) {
                    val marker = "BLACKMANGASEARCHTERM"
                    val safeTemplate = template.replace("{searchTerms}", marker)
                    val resolved = try {
                        URI(document.finalUrl).resolve(safeTemplate).toString()
                    } catch (_: Exception) {
                        safeTemplate
                    }
                    return resolved.replace(marker, "{searchTerms}")
                }
            }
            event = parser.next()
        }
        return null
    }

    private class EntryBuilder {
        var id: String = ""
        var title: String = ""
        var summary: String = ""
        val links = mutableListOf<OpdsLink>()
        fun build(): OpdsEntry = OpdsEntry(
            id = id.ifBlank { links.firstOrNull()?.href ?: title },
            title = title.ifBlank { "Sem título" },
            summary = summary,
            links = links.toList()
        )
    }

    private fun parseAtomFeed(bytes: ByteArray, baseUrl: String): OpdsFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) { }
        parser.setInput(ByteArrayInputStream(bytes), null)

        val entries = mutableListOf<OpdsEntry>()
        val feedLinks = mutableListOf<OpdsLink>()
        var feedTitle = ""
        var entry: EntryBuilder? = null
        var capture: String? = null
        val text = StringBuilder()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "entry" -> entry = EntryBuilder()
                        "link" -> {
                            val rel = parser.getAttributeValue(null, "rel").orEmpty()
                            val rawHref = parser.getAttributeValue(null, "href").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val title = parser.getAttributeValue(null, "title").orEmpty()
                            if (rawHref.isNotBlank()) {
                                val href = resolve(baseUrl, rawHref)
                                val link = OpdsLink(rel, href, type, title)
                                if (entry != null) entry!!.links.add(link) else feedLinks.add(link)
                            }
                        }
                        "title", "subtitle", "summary", "content", "id" -> {
                            capture = parser.name.lowercase()
                            text.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (capture != null) {
                    text.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (capture == name) {
                        val value = text.toString().trim()
                        val current = entry
                        if (current != null) {
                            when (name) {
                                "title" -> if (current.title.isBlank()) current.title = value
                                "summary", "content" -> if (current.summary.isBlank()) current.summary = value
                                "id" -> current.id = value
                            }
                        } else if (name == "title" && feedTitle.isBlank()) {
                            feedTitle = value
                        }
                        capture = null
                        text.setLength(0)
                    }
                    if (name == "entry" && entry != null) {
                        entries.add(entry!!.build())
                        entry = null
                        capture = null
                    }
                }
            }
            event = parser.next()
        }
        if (feedTitle.isBlank()) throw IllegalArgumentException("A resposta não parece ser um catálogo Atom/OPDS reconhecido.")
        return OpdsFeed(feedTitle, entries, feedLinks)
    }

    private fun parseJsonFeed(bytes: ByteArray, baseUrl: String): OpdsFeed {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val metadata = root.optJSONObject("metadata") ?: JSONObject()
        val title = metadata.optString("title", root.optString("title", "Catálogo OPDS"))
        val links = parseJsonLinks(root.optJSONArray("links"), baseUrl).toMutableList()
        val entries = mutableListOf<OpdsEntry>()

        val publications = root.optJSONArray("publications")
        if (publications != null) {
            for (i in 0 until publications.length()) {
                val item = publications.optJSONObject(i) ?: continue
                val itemMeta = item.optJSONObject("metadata") ?: JSONObject()
                val itemTitle = itemMeta.optString("title", "Sem título")
                val summary = itemMeta.optString("description", itemMeta.optString("subtitle", ""))
                val itemLinks = parseJsonLinks(item.optJSONArray("links"), baseUrl).toMutableList()
                val images = item.optJSONArray("images")
                if (images != null) for (j in 0 until images.length()) {
                    val image = images.optJSONObject(j) ?: continue
                    val href = image.optString("href")
                    if (href.isNotBlank()) itemLinks.add(OpdsLink("http://opds-spec.org/image", resolve(baseUrl, href), image.optString("type")))
                }
                entries.add(
                    OpdsEntry(
                        id = itemMeta.optString("identifier", itemLinks.firstOrNull()?.href ?: itemTitle),
                        title = itemTitle,
                        summary = summary,
                        links = itemLinks
                    )
                )
            }
        }
        val navigation = root.optJSONArray("navigation")
        if (navigation != null) for (i in 0 until navigation.length()) {
            val nav = navigation.optJSONObject(i) ?: continue
            val href = nav.optString("href")
            if (href.isBlank()) continue
            entries.add(
                OpdsEntry(
                    id = resolve(baseUrl, href),
                    title = nav.optString("title", "Abrir catálogo"),
                    summary = nav.optString("content", ""),
                    links = listOf(OpdsLink("subsection", resolve(baseUrl, href), nav.optString("type", "application/opds+json")))
                )
            )
        }
        return OpdsFeed(title, entries, links)
    }

    private fun parseJsonLinks(array: JSONArray?, baseUrl: String): List<OpdsLink> {
        if (array == null) return emptyList()
        val result = mutableListOf<OpdsLink>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val href = obj.optString("href")
            if (href.isBlank()) continue
            val relValue = obj.opt("rel")
            val rel = when (relValue) {
                is JSONArray -> (0 until relValue.length()).joinToString(" ") { relValue.optString(it) }
                else -> relValue?.toString().orEmpty()
            }
            result.add(OpdsLink(rel, resolve(baseUrl, href), obj.optString("type"), obj.optString("title")))
        }
        return result
    }

    private fun resolve(base: String, href: String): String {
        return try { URI(base).resolve(href).toString() } catch (_: Exception) { href }
    }
}
