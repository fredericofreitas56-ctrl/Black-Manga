package com.blackmanga.app

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class MdGridGenre(val id: String, val name: String)

private object MdGridApi {
    private const val API = "https://api.mangadex.org"

    private fun json(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "BlackManga/0.5.3 (Android)")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("MangaDex respondeu HTTP " + code + ". Tenta novamente.")
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    suspend fun genres(): List<MdGridGenre> = withContext(Dispatchers.IO) {
        val data = json(API + "/manga/tag?limit=100").optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val attrs = item.optJSONObject("attributes") ?: JSONObject()
            if (!attrs.optString("group").equals("genre", true)) return@mapNotNull null
            val names = attrs.optJSONObject("name") ?: JSONObject()
            val name = names.optString("en").ifBlank { names.optString("pt-br") }
            if (name.isBlank()) null else MdGridGenre(item.optString("id"), name)
        }.distinctBy { it.name.lowercase() }.sortedBy { it.name }
    }

    suspend fun catalog(query: String, genreId: String?, sort: String, offset: Int): List<MdManga> =
        withContext(Dispatchers.IO) {
            val order = if (sort == "popular") "followedCount" else "latestUploadedChapter"
            val p = mutableListOf(
                "limit=32", "offset=" + offset, "includes%5B%5D=cover_art",
                "order%5B" + order + "%5D=desc", "contentRating%5B%5D=safe",
                "contentRating%5B%5D=suggestive"
            )
            if (query.isNotBlank()) p.add("title=" + URLEncoder.encode(query.trim(), "UTF-8"))
            if (!genreId.isNullOrBlank()) p.add("includedTags%5B%5D=" + URLEncoder.encode(genreId, "UTF-8"))
            val data = json(API + "/manga?" + p.joinToString("&")).optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).mapNotNull { i ->
                val item = data.optJSONObject(i) ?: return@mapNotNull null
                val attr = item.optJSONObject("attributes") ?: JSONObject()
                val titles = attr.optJSONObject("title") ?: JSONObject()
                val title = sequenceOf("en", "pt-br", "ja-ro", "ja").map { titles.optString(it, "") }
                    .firstOrNull { it.isNotBlank() }
                    ?: titles.keys().asSequence().map { titles.optString(it, "") }.firstOrNull { it.isNotBlank() }
                    ?: ""
                if (title.isBlank()) return@mapNotNull null
                val descObj = attr.optJSONObject("description") ?: JSONObject()
                val desc = sequenceOf("pt-br", "en").map { descObj.optString(it, "") }.firstOrNull { it.isNotBlank() }.orEmpty()
                var cover: String? = null
                val rel = item.optJSONArray("relationships")
                if (rel != null) for (j in 0 until rel.length()) {
                    val r = rel.optJSONObject(j) ?: continue
                    if (r.optString("type") == "cover_art") {
                        val file = r.optJSONObject("attributes")?.optString("fileName").orEmpty()
                        if (file.isNotBlank()) cover = "https://uploads.mangadex.org/covers/" + item.optString("id") + "/" + file + ".256.jpg"
                    }
                }
                MdManga(item.optString("id"), title, desc, cover)
            }
        }
    suspend fun byId(id: String): MdManga? = withContext(Dispatchers.IO) {
        val item = json(API + "/manga/" + URLEncoder.encode(id, "UTF-8") + "?includes%5B%5D=cover_art")
            .optJSONObject("data") ?: return@withContext null
        val attr = item.optJSONObject("attributes") ?: JSONObject()
        val titles = attr.optJSONObject("title") ?: JSONObject()
        val title = sequenceOf("en", "pt-br", "ja-ro", "ja").map { titles.optString(it, "") }
            .firstOrNull { it.isNotBlank() }
            ?: titles.keys().asSequence().map { titles.optString(it, "") }.firstOrNull { it.isNotBlank() }
            ?: ""
        if (title.isBlank()) return@withContext null
        val descObj = attr.optJSONObject("description") ?: JSONObject()
        val desc = sequenceOf("pt-br", "en").map { descObj.optString(it, "") }.firstOrNull { it.isNotBlank() }.orEmpty()
        var cover: String? = null
        val rel = item.optJSONArray("relationships")
        if (rel != null) for (j in 0 until rel.length()) {
            val r = rel.optJSONObject(j) ?: continue
            if (r.optString("type") == "cover_art") {
                val file = r.optJSONObject("attributes")?.optString("fileName").orEmpty()
                if (file.isNotBlank()) cover = "https://uploads.mangadex.org/covers/" + item.optString("id") + "/" + file + ".256.jpg"
            }
        }
        MdManga(item.optString("id"), title, desc, cover)
    }

}

@Composable
internal fun MangaDexScreen(startUrl: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var mangas by remember { mutableStateOf<List<MdManga>>(emptyList()) }
    var genres by remember { mutableStateOf<List<MdGridGenre>>(emptyList()) }
    var chosenGenre by remember { mutableStateOf<MdGridGenre?>(null) }
    var selected by remember { mutableStateOf<MdManga?>(null) }
    var chapters by remember { mutableStateOf<List<MdChapter>>(emptyList()) }
    var readerPages by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("A carregar o catálogo…") }
    var searchVisible by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("updated") }
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showGenres by remember { mutableStateOf(false) }

    fun load(reset: Boolean = true, q: String = query, genre: MdGridGenre? = chosenGenre, order: String = sort) {
        val start = if (reset) 0 else offset
        scope.launch {
            if (reset) loading = true else loadingMore = true
            error = ""
            try {
                val rows = MdGridApi.catalog(q.trim(), genre?.id, order, start)
                mangas = if (reset) rows else mangas + rows
                offset = start + rows.size
                hasMore = rows.size == 32
                info = if (rows.isEmpty() && reset) "Nenhum título encontrado." else
                    (if (order == "popular") "Mais populares" else "Atualizados") + " · " + mangas.size + " títulos"
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Não foi possível carregar o catálogo."
                if (reset) mangas = emptyList()
            } finally { loading = false; loadingMore = false }
        }
    }

    fun openManga(manga: MdManga) {
        scope.launch {
            loading = true
            error = ""
            try {
                selected = manga
                chapters = MangaDexApi.chapters(manga.id)
                readerPages = emptyList()
                info = "Escolhe um capítulo para ler."
            } catch (e: Exception) {
                selected = null
                error = e.localizedMessage ?: "Não foi possível carregar os capítulos."
            } finally { loading = false }
        }
    }

    LaunchedEffect(startUrl) {
        loading = true
        try {
            genres = MdGridApi.genres()
            val parts = runCatching { java.net.URI(startUrl).path.orEmpty().split("/").filter { it.isNotBlank() } }.getOrDefault(emptyList())
            val id = if (parts.size >= 2 && parts[0] == "title") parts[1] else ""
            if (id.isNotBlank()) {
                val manga = MdGridApi.byId(id)
                if (manga != null) {
                    selected = manga
                    chapters = MangaDexApi.chapters(manga.id)
                    info = "Escolhe um capítulo para ler."
                } else load()
            } else load()
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Falha ao abrir MangaDex."
        } finally { loading = false }
    }

    val quickNames = listOf("Action", "Comedy", "Drama", "Fantasy", "Romance", "Adventure", "Mystery", "Horror", "Sports")
    val quick = quickNames.mapNotNull { n -> genres.firstOrNull { it.name.equals(n, true) } }

    Column(Modifier.fillMaxSize().background(Color(0xFFFFF8F6))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                when {
                    readerPages.isNotEmpty() -> readerPages = emptyList()
                    selected != null -> { selected = null; chapters = emptyList() }
                    else -> onBack()
                }
            }) { Text("←", color = Color(0xFF403733), fontSize = 26.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { searchVisible = !searchVisible }) { Text("⌕", color = Color(0xFF403733), fontSize = 29.sp) }
            Box {
                TextButton(onClick = { showSort = true }) { Text("▦", color = Color(0xFF403733), fontSize = 22.sp) }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    DropdownMenuItem(text = { Text("Atualizados") }, onClick = { showSort = false; sort = "updated"; load(true, order = "updated") })
                    DropdownMenuItem(text = { Text("Mais populares") }, onClick = { showSort = false; sort = "popular"; load(true, order = "popular") })
                    DropdownMenuItem(text = { Text("Todos os géneros") }, onClick = { showSort = false; showGenres = true })
                }
            }
        }
        Text(
            if (readerPages.isNotEmpty()) readerTitle else selected?.title ?: "MangaDex",
            Modifier.padding(horizontal = 17.dp),
            color = Color(0xFF211D1C), fontSize = 27.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        if (readerPages.isNotEmpty()) {
            Text(readerPages.size.toString() + " páginas", Modifier.padding(16.dp), color = Color(0xFF8C563F))
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context -> WebView(context).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = WebViewClient()
                    setBackgroundColor(AndroidColor.rgb(255, 248, 246))
                    loadDataWithBaseURL("https://mangadex.org/", mdReaderHtml(readerTitle, readerPages), "text/html", "UTF-8", null)
                } },
                update = { it.loadDataWithBaseURL("https://mangadex.org/", mdReaderHtml(readerTitle, readerPages), "text/html", "UTF-8", null) }
            )
        } else if (selected != null) {
            Text("Capítulos em português e inglês", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF8C563F), fontSize = 12.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chapters.forEach { chapter ->
                    Card(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            loading = true
                            error = ""
                            try {
                                val result = MangaDexApi.pageUrls(chapter.id)
                                readerPages = result.second
                                readerTitle = selected!!.title + " · " + chapter.name
                                if (readerPages.isEmpty()) error = "Este capítulo não tem páginas disponíveis."
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Não foi possível abrir o capítulo."
                            } finally { loading = false }
                        }
                    }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(13.dp)) {
                            Text(chapter.name, color = Color(0xFF211D1C), fontWeight = FontWeight.SemiBold)
                            Text(if (chapter.language == "pt-br") "Português (Brasil)" else "English", color = Color(0xFF8C563F), fontSize = 11.sp)
                        }
                    }
                }
                if (chapters.isEmpty() && !loading) Text("Não foram encontrados capítulos nestes idiomas.", color = Color.DarkGray)
            }
        } else {
            Text(if (sort == "popular") "Mais populares" else "Atualizado",
                Modifier.padding(start = 17.dp, end = 17.dp, top = 2.dp, bottom = 9.dp),
                color = Color(0xFF8C563F), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (searchVisible) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Pesquisar títulos") }, singleLine = true)
                    Button(onClick = { load(true, q = query) }, enabled = !loading && query.trim().length >= 2) { Text("Ir") }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = chosenGenre == null, onClick = {
                    chosenGenre = null
                    load(true, genre = null)
                }, label = { Text("☷ Géneros") })
                quick.forEach { genre ->
                    FilterChip(selected = chosenGenre?.id == genre.id, onClick = {
                        chosenGenre = if (chosenGenre?.id == genre.id) null else genre
                        load(true, genre = chosenGenre)
                    }, label = { Text(genre.name) })
                }
            }
            Text(info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFF775F56), fontSize = 11.sp)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 76.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                items(mangas, key = { it.id }) { manga ->
                    Column(Modifier.fillMaxWidth().clickable { openManga(manga) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DDDA))) {
                            if (!manga.coverUrl.isNullOrBlank()) {
                                AsyncImage(model = manga.coverUrl, contentDescription = manga.title,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.69f), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.69f).background(Color(0xFFE8DDDA)), contentAlignment = Alignment.Center) {
                                    Text("BLACK MANGA", color = Color(0xFF8C563F), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(manga.title, Modifier.fillMaxWidth(), color = Color(0xFF211D1C), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.Center) {
                            if (loadingMore) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF8C563F))
                            else Button(onClick = { load(false) }) { Text("Carregar mais") }
                        }
                    }
                }
            }
        }

        if (loading && mangas.isEmpty() && selected == null && readerPages.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF8C563F)) }
        }
        if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 14.dp, vertical = 5.dp), color = Color(0xFFB3261E), fontSize = 12.sp)
        Text("Catálogo do MangaDex · disponibilidade dependente do idioma e da licença",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = Color(0xFF8A817E), fontSize = 9.sp)
    }

    if (showGenres) {
        AlertDialog(onDismissRequest = { showGenres = false }, title = { Text("Escolher género") },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { chosenGenre = null; showGenres = false; load(true, genre = null) }, modifier = Modifier.fillMaxWidth()) { Text("Todos os géneros") }
                    genres.forEach { genre ->
                        TextButton(onClick = { chosenGenre = genre; showGenres = false; load(true, genre = genre) }, modifier = Modifier.fillMaxWidth()) { Text(genre.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGenres = false }) { Text("Fechar") } }
        )
    }
}

private fun mdReaderHtml(title: String, urls: List<String>): String {
    val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val images = urls.joinToString("\n") { raw ->
        val url = raw.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        "<img src=\"" + url + "\" alt=\"Página\" />"
    }
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0;background:#fff8f6;color:#211d1c;font-family:sans-serif}h2{padding:12px;font-size:18px}img{display:block;width:100%;height:auto;margin:0 auto 8px}</style></head><body><h2>$safeTitle</h2>$images</body></html>"""
}
