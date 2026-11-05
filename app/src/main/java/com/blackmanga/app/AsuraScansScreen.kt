package com.blackmanga.app

import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class AsuraSeries(val slug: String, val title: String, val cover: String, val publicUrl: String)
private data class AsuraChapter(val number: String, val title: String, val locked: Boolean, val url: String)
private data class AsuraGenre(val name: String, val slug: String)

private object AsuraScansApi {
    private const val API = "https://api.asurascans.com/api"
    private const val SITE = "https://asurascans.com"
    private const val LIMIT = 20

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
        c.setRequestProperty("Accept", "application/json,text/html,application/xhtml+xml")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Referer", SITE + "/")
        c.setRequestProperty("Origin", SITE)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Asura Scans respondeu HTTP " + code + ".")
            return body
        } finally { c.disconnect() }
    }

    suspend fun catalog(query: String, offset: Int, genre: String?, sort: String): Pair<List<AsuraSeries>, Boolean> = withContext(Dispatchers.IO) {
        val params = mutableListOf("offset=$offset", "limit=$LIMIT", "sort=$sort", "order=desc")
        if (query.isNotBlank()) params.add("search=" + URLEncoder.encode(query.trim(), "UTF-8"))
        if (!genre.isNullOrBlank()) params.add("genres=" + URLEncoder.encode(genre, "UTF-8"))
        val root = JSONObject(get(API + "/series?" + params.joinToString("&")))
        val data = root.optJSONArray("data") ?: JSONArray()
        val rows = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = o.optString("slug").ifBlank { o.optString("public_url").trimEnd('/').substringAfterLast('/') }
            val title = o.optString("title").trim()
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val rawCover = o.optString("cover").ifBlank { o.optString("coverUrl").ifBlank { o.optString("cover_url") } }
            val cover = when {
                rawCover.startsWith("http") -> rawCover
                rawCover.startsWith("/") -> SITE + rawCover
                else -> rawCover
            }
            val publicUrl = o.optString("public_url").ifBlank { o.optString("publicUrl") }.ifBlank { "/comics/" + slug }
            AsuraSeries(slug, title, cover, publicUrl)
        }
        Pair(rows, root.optJSONObject("meta")?.optBoolean("has_more") ?: (rows.size == LIMIT))
    }

    suspend fun genres(): List<AsuraGenre> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get(SITE + "/browse"), SITE + "/")
        val raw = doc.selectFirst("[props*=availableGenres]")?.attr("props") ?: return@withContext emptyList()
        val root = unwrap(JSONTokener(raw).nextValue()) as? JSONObject ?: return@withContext emptyList()
        val arr = findArray(root, "availableGenres") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val slug = o.optString("slug")
            if (name.isBlank() || slug.isBlank()) null else AsuraGenre(name, slug)
        }
    }

    private fun getDocumentWithSlugFallback(slug: String): Pair<org.jsoup.nodes.Document, String> {
        return try {
            Jsoup.parse(get(SITE + "/comics/" + slug), SITE + "/") to slug
        } catch (first: Exception) {
            val stable = slug.replace(Regex("-[a-z0-9]{8}$"), "")
            if (stable == slug) throw first
            val doc = Jsoup.parse(get(SITE + "/comics/" + stable), SITE + "/")
            val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href")
                ?.substringAfter("/comics/")?.substringBefore("/")?.takeIf { it.isNotBlank() } ?: stable
            doc to canonical
        }
    }

    suspend fun chapters(series: AsuraSeries): List<AsuraChapter> = withContext(Dispatchers.IO) {
        val initialSlug = series.publicUrl.trimEnd('/').substringAfterLast('/').ifBlank { series.slug }
        val (doc, fallbackSlug) = getDocumentWithSlugFallback(initialSlug)
        val publicProps = runCatching { astroProp(doc, "publicUrl") }.getOrNull()
        val currentSlug = publicProps?.let { findString(it, "publicUrl") }
            ?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: fallbackSlug
        val props = astroProp(doc, "chapters")
        val arr = findArray(props, "chapters") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val raw = o.opt("number")?.toString().orEmpty()
            if (raw.isBlank() || raw == "null") return@mapNotNull null
            val n = raw.removeSuffix(".0")
            val early = o.optString("early_access_until")
            val earlyLocked = runCatching { java.time.Instant.parse(early).toEpochMilli() > System.currentTimeMillis() }.getOrDefault(false)
            val locked = o.optBoolean("is_premium") || o.optBoolean("is_locked") || earlyLocked
            AsuraChapter(n, o.optString("title"), locked, SITE + "/comics/" + currentSlug + "/chapter/" + n)
        }.sortedByDescending { it.number.toDoubleOrNull() ?: -1.0 }
    }

    suspend fun pages(chapter: AsuraChapter): Pair<List<String>, Int> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get(chapter.url), SITE + "/")
        val props = astroProp(doc, "pages")
        val arr = findArray(props, "pages") ?: JSONArray()
        val urls = mutableListOf<String>()
        var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (!url.startsWith("https://")) continue
            val tiles = o.optJSONArray("tiles")
            if (tiles != null && tiles.length() > 0) {
                val order = (0 until tiles.length()).map { tiles.optInt(it) }
                val cols = o.optInt("tile_cols", 4).coerceAtLeast(1)
                val rows = o.optInt("tile_rows", 5).coerceAtLeast(1)
                urls.add(rebuildTiles(url, order, cols, rows))
            } else urls.add(url)
        }
        Pair(urls, skipped)
    }

    private fun rebuildTiles(url: String, order: List<Int>, cols: Int, rows: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 25000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Referer", SITE + "/")
        val bytes = try { connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Imagem em mosaicos inválida.")
        val tileW = source.width / cols
        val tileH = source.height / rows
        val result = Bitmap.createBitmap(tileW * cols, tileH * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        order.forEachIndexed { sourceIndex, destinationIndex ->
            if (destinationIndex in 0 until (cols * rows)) {
                val src = Rect((sourceIndex % cols) * tileW, (sourceIndex / cols) * tileH,
                    ((sourceIndex % cols) + 1) * tileW, ((sourceIndex / cols) + 1) * tileH)
                val dst = Rect((destinationIndex % cols) * tileW, (destinationIndex / cols) * tileH,
                    ((destinationIndex % cols) + 1) * tileW, ((destinationIndex / cols) + 1) * tileH)
                canvas.drawBitmap(source, src, dst, null)
            }
        }
        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 92, output)
        source.recycle()
        result.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun astroProp(doc: org.jsoup.nodes.Document, vararg names: String): JSONObject {
        val selector = names.joinToString("") { "[props*=" + it + "]" }
        val raw = doc.selectFirst(selector)?.attr("props")
            ?: error("O site não forneceu os dados públicos de " + names.joinToString() + ".")
        return unwrap(JSONTokener(raw).nextValue()) as? JSONObject ?: error("Formato de dados do Asura não reconhecido.")
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        is JSONArray -> if (v.length() == 1 && v.opt(0) is Number) JSONObject.NULL
            else if (v.length() == 2 && v.opt(0) is Number) unwrap(v.opt(1))
            else JSONArray().also { a -> for (i in 0 until v.length()) a.put(unwrap(v.opt(i))) }
        is JSONObject -> JSONObject().also { o ->
            val it = v.keys()
            while (it.hasNext()) { val k = it.next(); o.put(k, unwrap(v.opt(k))) }
        }
        else -> v
    }

    private fun findString(root: JSONObject, name: String): String? {
        root.optString(name).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        val it = root.keys()
        while (it.hasNext()) {
            when (val value = root.opt(it.next())) {
                is JSONObject -> findString(value, name)?.let { found -> return found }
                is JSONArray -> for (i in 0 until value.length()) {
                    val child = value.optJSONObject(i)
                    if (child != null) findString(child, name)?.let { found -> return found }
                }
            }
        }
        return null
    }

    private fun findArray(root: JSONObject, name: String): JSONArray? {
        root.optJSONArray(name)?.let { return it }
        val it = root.keys()
        while (it.hasNext()) {
            val v = root.opt(it.next())
            if (v is JSONObject) findArray(v, name)?.let { found -> return found }
            if (v is JSONArray) {
                for (i in 0 until v.length()) {
                    val child = v.opt(i)
                    if (child is JSONObject) findArray(child, name)?.let { found -> return found }
                    if (child is JSONArray) {
                        for (j in 0 until child.length()) {
                            val nested = child.opt(j)
                            if (nested is JSONObject) findArray(nested, name)?.let { found -> return found }
                        }
                    }
                }
            }
        }
        return null
    }
}

@Composable
internal fun AsuraScansScreen(startUrl: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<AsuraSeries>>(emptyList()) }
    var genres by remember { mutableStateOf<List<AsuraGenre>>(emptyList()) }
    var chosenGenre by remember { mutableStateOf<AsuraGenre?>(null) }
    var sort by remember { mutableStateOf("latest") }
    var selected by remember { mutableStateOf<AsuraSeries?>(null) }
    var chapters by remember { mutableStateOf<List<AsuraChapter>>(emptyList()) }
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerTitle by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("A carregar catálogo…") }

    fun load(reset: Boolean = true) {
        val start = if (reset) 0 else offset
        scope.launch {
            if (reset) loading = true else loadingMore = true
            error = ""
            try {
                val result = AsuraScansApi.catalog(query, start, chosenGenre?.slug, sort)
                rows = if (reset) result.first else (rows + result.first).distinctBy { it.slug }
                offset = start + result.first.size
                more = result.second && result.first.isNotEmpty()
                info = rows.size.toString() + " títulos · Asura Scans"
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Erro ao carregar catálogo."
                if (reset) rows = emptyList()
            } finally { loading = false; loadingMore = false }
        }
    }

    fun open(item: AsuraSeries) {
        scope.launch {
            loading = true; error = ""; selected = item; pages = emptyList()
            try {
                chapters = AsuraScansApi.chapters(item)
                info = chapters.size.toString() + " capítulos públicos"
            } catch (e: Exception) {
                selected = null
                error = e.localizedMessage ?: "Não foi possível carregar capítulos."
            } finally { loading = false }
        }
    }

    LaunchedEffect(startUrl) {
        genres = runCatching { AsuraScansApi.genres() }.getOrDefault(emptyList())
        load()
    }
    val quickNames = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Mystery", "Horror")
    val quickGenres = quickNames.mapNotNull { name -> genres.firstOrNull { it.name.equals(name, true) } }

    Column(Modifier.fillMaxSize().background(Color(0xFFFFFBF8))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                when {
                    pages.isNotEmpty() -> pages = emptyList()
                    selected != null -> { selected = null; chapters = emptyList() }
                    else -> onBack()
                }
            }) { Text("←", fontSize = 25.sp, color = Color(0xFF352D2A)) }
            Spacer(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menu = true }) { Text("▦", color = Color(0xFF403733), fontSize = 22.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Mais recentes") }, onClick = { menu = false; sort = "latest"; load() })
                    DropdownMenuItem(text = { Text("Mais populares") }, onClick = { menu = false; sort = "popular"; load() })
                    DropdownMenuItem(text = { Text("Melhor classificação") }, onClick = { menu = false; sort = "rating"; load() })
                }
            }
        }
        Text(if (pages.isNotEmpty()) readerTitle else selected?.title ?: "Asura Scans",
            Modifier.padding(horizontal = 16.dp), color = Color(0xFF211D1C), fontSize = 24.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)

        if (pages.isNotEmpty()) {
            Text(pages.size.toString() + " páginas", Modifier.padding(12.dp), color = Color(0xFF805A49))
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = WebViewClient()
                    setBackgroundColor(AndroidColor.WHITE)
                    loadDataWithBaseURL("https://asurascans.com/", asuraHtml(readerTitle, pages), "text/html", "UTF-8", null)
                }
            }, update = { it.loadDataWithBaseURL("https://asurascans.com/", asuraHtml(readerTitle, pages), "text/html", "UTF-8", null) })
        } else if (selected != null) {
            Text(info, Modifier.padding(14.dp), color = Color(0xFF805A49), fontSize = 12.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                chapters.forEach { chapter ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = !chapter.locked) {
                        scope.launch {
                            loading = true; error = ""
                            try {
                                val result = AsuraScansApi.pages(chapter)
                                pages = result.first
                                readerTitle = (selected?.title ?: "") + " · Capítulo " + chapter.number
                                if (pages.isEmpty()) error = if (result.second > 0) "Este capítulo contém páginas com mosaicos protegidos que esta versão ainda não suporta." else "Não foram encontradas páginas públicas."
                            } catch (e: Exception) { error = e.localizedMessage ?: "Não foi possível abrir o capítulo." }
                            finally { loading = false }
                        }
                    }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(9.dp)) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Capítulo " + chapter.number, color = Color(0xFF211D1C), fontWeight = FontWeight.SemiBold)
                                if (chapter.title.isNotBlank()) Text(chapter.title, color = Color(0xFF775F56), fontSize = 12.sp)
                            }
                            Text(if (chapter.locked) "VIP" else "Ler →", color = if (chapter.locked) Color(0xFF9B3B32) else Color(0xFF805A49), fontSize = 12.sp)
                        }
                    }
                }
                if (chapters.isEmpty() && !loading) Text("Nenhum capítulo público encontrado.", color = Color.DarkGray)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Pesquisar manhwa") }, singleLine = true)
                Spacer(Modifier.width(7.dp))
                Button(onClick = { load() }, enabled = !loading && query.isNotBlank()) { Text("Buscar") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = chosenGenre == null, onClick = { chosenGenre = null; load() }, label = { Text("☷ Géneros") })
                quickGenres.forEach { genre ->
                    FilterChip(selected = chosenGenre?.slug == genre.slug, onClick = {
                        chosenGenre = if (chosenGenre?.slug == genre.slug) null else genre
                        load()
                    }, label = { Text(genre.name) })
                }
            }
            Text(info, Modifier.padding(horizontal = 15.dp, vertical = 4.dp), color = Color(0xFF805A49), fontSize = 11.sp)
            LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rows, key = { it.slug }) { item ->
                    Column(Modifier.clickable { open(item) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Card(shape = RoundedCornerShape(9.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEDE6E1))) {
                            if (item.cover.startsWith("https://")) AsyncImage(item.cover, item.title,
                                Modifier.fillMaxWidth().aspectRatio(0.69f), contentScale = ContentScale.Crop)
                            else Box(Modifier.fillMaxWidth().aspectRatio(0.69f), contentAlignment = Alignment.Center) { Text("ASURA") }
                        }
                        Text(item.title, color = Color(0xFF211D1C), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (more) item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        if (loadingMore) CircularProgressIndicator(Modifier.size(22.dp))
                        else Button(onClick = { load(false) }) { Text("Carregar mais") }
                    }
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF805A49))
        if (error.isNotBlank()) Text(error, Modifier.padding(10.dp), color = Color(0xFFB3261E), fontSize = 12.sp)
        Text("Conteúdo público · capítulos VIP não são abertos", Modifier.padding(8.dp), color = Color(0xFF8A817E), fontSize = 9.sp)
    }
}

private fun asuraHtml(title: String, urls: List<String>): String {
    val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val imgs = urls.joinToString("\n") { u ->
        "<img src=\"" + u.replace("&", "&amp;").replace(34.toChar().toString(), "&quot;") + "\" alt=\"Página\" />"
    }
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>body{margin:0;background:#fff;color:#211d1c;font-family:sans-serif}h2{padding:12px;font-size:18px}img{display:block;width:100%;height:auto;margin:0 auto 7px}</style></head><body><h2>" + safeTitle + "</h2>" + imgs + "</body></html>"
}
