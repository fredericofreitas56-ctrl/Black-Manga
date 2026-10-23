package com.blackmanga.app

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class MdManga(val id: String, val title: String, val description: String, val coverUrl: String?)
internal data class MdChapter(val id: String, val name: String, val chapter: String, val language: String)

internal object MangaDexApi {
    private const val API = "https://api.mangadex.org"

    fun supports(rawUrl: String): Boolean = try {
        val host = java.net.URI(rawUrl.trim()).host.orEmpty().lowercase().removePrefix("www.")
        host == "mangadex.org" || host == "api.mangadex.org"
    } catch (_: Exception) { false }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "BlackManga/0.4.4 (Android app)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("MangaDex respondeu HTTP $code. Tenta novamente mais tarde.")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    suspend fun search(query: String): List<MdManga> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = getJson("$API/manga?title=$q&limit=30&includes[]=cover_art&order[relevance]=desc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica")
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val attrs = item.optJSONObject("attributes") ?: JSONObject()
            val titles = attrs.optJSONObject("title") ?: JSONObject()
            val title = sequenceOf("pt-br", "en", "ja-ro", "ja").map { titles.optString(it, "") }
                .firstOrNull { it.isNotBlank() } ?: titles.keys().asSequence().map { titles.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: ""
            if (title.isBlank()) return@mapNotNull null
            val descriptions = attrs.optJSONObject("description") ?: JSONObject()
            val description = sequenceOf("pt-br", "en").map { descriptions.optString(it, "") }.firstOrNull { it.isNotBlank() }.orEmpty()
            val relationships = item.optJSONArray("relationships")
            var cover: String? = null
            if (relationships != null) for (j in 0 until relationships.length()) {
                val rel = relationships.optJSONObject(j) ?: continue
                if (rel.optString("type") == "cover_art") {
                    val fileName = rel.optJSONObject("attributes")?.optString("fileName").orEmpty()
                    if (fileName.isNotBlank()) cover = "https://uploads.mangadex.org/covers/${item.optString("id")}/$fileName.256.jpg"
                }
            }
            MdManga(item.optString("id"), title, description, cover)
        }
    }

    suspend fun chapters(mangaId: String): List<MdChapter> = withContext(Dispatchers.IO) {
        val url = "$API/manga/$mangaId/feed?limit=100&order[chapter]=desc&translatedLanguage[]=pt-br&translatedLanguage[]=en&includes[]=scanlation_group"
        val json = getJson(url)
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val attrs = item.optJSONObject("attributes") ?: JSONObject()
            val chapter = attrs.optString("chapter", "").ifBlank { "?" }
            val title = attrs.optString("title", "")
            val lang = attrs.optString("translatedLanguage", "")
            MdChapter(item.optString("id"), if (title.isBlank()) "Capítulo $chapter" else "Capítulo $chapter · $title", chapter, lang)
        }
    }

    suspend fun pageUrls(chapterId: String): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val json = getJson("$API/at-home/server/$chapterId")
        val base = json.optString("baseUrl")
        val chapter = json.optJSONObject("chapter") ?: JSONObject()
        val hash = chapter.optString("hash")
        val files = chapter.optJSONArray("dataSaver") ?: chapter.optJSONArray("data") ?: return@withContext base to emptyList()
        val saver = chapter.has("dataSaver")
        val urls = (0 until files.length()).mapNotNull { i ->
            val file = files.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = if (saver) "data-saver" else "data"
            "$base/$path/$hash/$file"
        }
        base to urls
    }
}

@Composable
private fun MangaDexScreenLegacy(startUrl: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var mangas by remember { mutableStateOf<List<MdManga>>(emptyList()) }
    var chapters by remember { mutableStateOf<List<MdChapter>>(emptyList()) }
    var selectedManga by remember { mutableStateOf<MdManga?>(null) }
    var pageUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("Pesquisa títulos no catálogo oficial do MangaDex.") }

    LaunchedEffect(startUrl) {
        val uri = runCatching { java.net.URI(startUrl) }.getOrNull()
        val id = uri?.path.orEmpty().split("/").filter { it.isNotBlank() }.let { parts ->
            if (parts.size >= 2 && parts[0] == "title") parts[1] else ""
        }
        if (id.isNotBlank()) {
            query = ""
            loading = true
            try {
                val found = withContext(Dispatchers.IO) {
                    val q = URLEncoder.encode(id, "UTF-8")
                    val json = URL("https://api.mangadex.org/manga/$q?includes[]=cover_art").readText()
                    JSONObject(json).optJSONObject("data")
                }
                if (found != null) {
                    val attrs = found.optJSONObject("attributes") ?: JSONObject()
                    val titleObj = attrs.optJSONObject("title") ?: JSONObject()
                    val title = sequenceOf("en", "pt-br", "ja-ro").map { titleObj.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: "MangaDex"
                    val manga = MdManga(found.optString("id"), title, "", null)
                    selectedManga = manga
                    chapters = MangaDexApi.chapters(manga.id)
                    notice = "Escolhe um capítulo para ler."
                }
            } catch (e: Exception) { error = e.localizedMessage ?: "Não foi possível abrir este título." }
            finally { loading = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                when {
                    pageUrls.isNotEmpty() -> pageUrls = emptyList()
                    chapters.isNotEmpty() -> { chapters = emptyList(); selectedManga = null }
                    else -> onBack()
                }
            }) { Text("Voltar") }
            Column(Modifier.weight(1f)) {
                Text("MangaDex · Catálogo", color = Color(0xFFB7A0FF), style = MaterialTheme.typography.titleMedium)
                Text("API oficial · pesquisa integrada", color = Color(0xFFB8B5C5), fontSize = 11.sp)
            }
        }
        if (pageUrls.isNotEmpty()) {
            Text(readerTitle, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("${pageUrls.size} páginas", color = Color(0xFFB8B5C5))
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context -> WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    webViewClient = WebViewClient()
                    setBackgroundColor(AndroidColor.rgb(16,16,20))
                    loadDataWithBaseURL("https://mangadex.org/", mdReaderHtml(readerTitle, pageUrls), "text/html", "UTF-8", null)
                } },
                update = { it.loadDataWithBaseURL("https://mangadex.org/", mdReaderHtml(readerTitle, pageUrls), "text/html", "UTF-8", null) }
            )
        } else if (selectedManga != null) {
            Text(selectedManga!!.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Capítulos em português (Brasil) e inglês", color = Color(0xFFB8B5C5), fontSize = 12.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                chapters.forEach { chapter ->
                    Card(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            loading = true; error = ""
                            try {
                                val result = MangaDexApi.pageUrls(chapter.id)
                                pageUrls = result.second
                                readerTitle = "${selectedManga!!.title} · ${chapter.name}"
                                if (pageUrls.isEmpty()) error = "Este capítulo não tem páginas disponíveis nesta API."
                            } catch (e: Exception) { error = e.localizedMessage ?: "Não foi possível carregar as páginas." }
                            finally { loading = false }
                        }
                    }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22))) {
                        Column(Modifier.padding(12.dp)) {
                            Text(chapter.name, color = Color.White)
                            Text(if (chapter.language == "pt-br") "Português (Brasil)" else "English", color = Color(0xFFB8B5C5), fontSize = 11.sp)
                        }
                    }
                }
                if (chapters.isEmpty() && !loading) Text("Não foram encontrados capítulos em português ou inglês para este título.", color = Color(0xFFB8B5C5))
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pesquisar mangas") },
                placeholder = { Text("Nome do título") },
                singleLine = true
            )
            Button(onClick = {
                scope.launch {
                    loading = true; error = ""; mangas = emptyList()
                    try {
                        if (query.trim().length < 2) throw IllegalArgumentException("Escreve pelo menos 2 letras para pesquisar.")
                        mangas = MangaDexApi.search(query)
                        notice = if (mangas.isEmpty()) "Não foram encontrados títulos com esse nome." else "${mangas.size} títulos encontrados."
                    } catch (e: Exception) { error = e.localizedMessage ?: "A pesquisa falhou." }
                    finally { loading = false }
                }
            }, enabled = !loading && query.trim().length >= 2, modifier = Modifier.fillMaxWidth()) { Text("Pesquisar títulos") }
            Text(notice, color = Color(0xFFB8B5C5), fontSize = 12.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mangas.forEach { manga ->
                    Card(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            selectedManga = manga; chapters = emptyList(); error = ""; loading = true
                            try { chapters = MangaDexApi.chapters(manga.id); notice = "Escolhe um capítulo para ler." }
                            catch (e: Exception) { error = e.localizedMessage ?: "Não foi possível carregar capítulos." }
                            finally { loading = false }
                        }
                    }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(manga.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            if (manga.description.isNotBlank()) Text(manga.description.take(220), color = Color(0xFFB8B5C5), fontSize = 12.sp)
                            Text("Ver capítulos", color = Color(0xFFB7A0FF), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (loading) CircularProgressIndicator()
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF9CA7), fontSize = 12.sp)
        Text("Usa os títulos e capítulos disponibilizados pelo MangaDex. A disponibilidade varia por idioma e licença.", color = Color(0xFFB8B5C5), fontSize = 10.sp)
    }
}

private fun mdReaderHtml(title: String, urls: List<String>): String {
    val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val images = urls.joinToString("\n") { raw ->
        val url = raw.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        "<img src=\"$url\" alt=\"Página\" />"
    }
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0;background:#101014;color:#fff;font-family:sans-serif}h2{padding:12px;font-size:18px}img{display:block;width:100%;height:auto;margin:0 auto 8px}</style></head><body><h2>$safeTitle</h2>$images</body></html>"""
}
