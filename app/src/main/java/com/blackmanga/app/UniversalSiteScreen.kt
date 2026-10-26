package com.blackmanga.app

import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

internal data class UniversalSiteEntry(
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val description: String = ""
)

internal data class UniversalSitePage(
    val title: String,
    val url: String,
    val entries: List<UniversalSiteEntry>,
    val readerImages: List<String>
)

internal object UniversalSiteAdapter {
    private val genericLabels = setOf(
        "home", "homepage", "menu", "search", "buscar", "pesquisar", "login", "sign in",
        "register", "next", "previous", "back", "read more", "view more", "view details",
        "details", "more", "início", "inicio", "pesquisa", "entrar", "registar",
        "ver mais", "ver detalhes", "ler agora", "ler", "abrir", "capítulos", "capitulos",
        "géneros", "generos", "categorias", "contacto", "contato", "sobre"
    )

    private val excludedPaths = setOf(
        "category", "categories", "categoria", "categorias", "genre", "genres", "genero",
        "generos", "tag", "tags", "search", "buscar", "pesquisar", "login", "logout",
        "register", "signup", "account", "profile", "user", "users", "author", "authors",
        "contact", "contato", "about", "privacy", "terms", "politica", "termos"
    )

    fun isSafeHttpsUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl.trim())
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.host.none { it.isWhitespace() }
    } catch (_: Exception) {
        false
    }

    private fun normalizedHost(host: String): String =
        host.lowercase().removePrefix("www.").removePrefix("m.")

    fun hostsBelongTogether(first: String, second: String): Boolean {
        val a = normalizedHost(first)
        val b = normalizedHost(second)
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }

    private fun clean(value: String?): String = value.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun meaningful(value: String): Boolean {
        val normalized = clean(value).replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim().lowercase()
        return normalized.length in 2..140 && normalized !in genericLabels
    }

    fun load(rawUrl: String): UniversalSitePage {
        require(isSafeHttpsUrl(rawUrl)) {
            "Cola um endereço HTTPS completo, por exemplo https://exemplo.org."
        }
        val input = URI(rawUrl.trim())
        val response = Jsoup.connect(input.toASCIIString())
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
            .timeout(18000)
            .maxBodySize(5 * 1024 * 1024)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .execute()

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("O site respondeu HTTP ${response.statusCode()}. Pode exigir uma sessão ou bloquear leitores automáticos.")
        }

        val document = response.parse()
        val finalUrl = response.url().toString()
        val finalUri = URI(finalUrl)
        val baseHost = normalizedHost(finalUri.host.orEmpty())
        val currentPath = finalUri.path.orEmpty().trimEnd('/').ifBlank { "/" }

        fun internalLink(raw: String): String? {
            val value = raw.substringBefore("#").trim()
            if (!isSafeHttpsUrl(value)) return null
            val target = runCatching { URI(value) }.getOrNull() ?: return null
            if (!hostsBelongTogether(baseHost, target.host.orEmpty())) return null
            val path = target.path.orEmpty().trimEnd('/').ifBlank { "/" }
            if (path == currentPath && target.rawQuery.isNullOrBlank()) return null
            if (path.split('/').filter { it.isNotBlank() }.firstOrNull()?.lowercase()?.let { it in excludedPaths } == true) return null
            return value
        }

        fun imageUrl(image: Element?): String? {
            if (image == null) return null
            val candidate = sequenceOf("data-src", "data-lazy-src", "data-original", "data-url", "src")
                .map { attribute -> image.absUrl(attribute) }
                .firstOrNull { it.startsWith("https://", true) && !it.contains("data:image", true) }
                ?: return null
            val lowered = candidate.lowercase()
            if (listOf("logo", "avatar", "favicon", "emoji", "icon", "sprite", "banner-ad").any { lowered.contains(it) }) {
                return null
            }
            return candidate
        }

        fun headingFrom(element: Element): String? {
            val heading = element.selectFirst("h1,h2,h3,h4,h5,h6,[class*=title],[class*=titulo],[class*=name]")
                ?: return null
            return clean(heading.text()).takeIf { meaningful(it) }
        }

        fun titleFrom(anchor: Element): String {
            val direct = clean(anchor.text())
            if (meaningful(direct)) return direct.take(140)

            for (parent in anchor.parents().take(8)) {
                val heading = headingFrom(parent)
                if (!heading.isNullOrBlank()) return heading.take(140)
            }

            val imageAlt = clean(anchor.selectFirst("img")?.attr("alt"))
            if (meaningful(imageAlt)) return imageAlt.take(140)

            val titleAttribute = clean(anchor.attr("title")).ifBlank { clean(anchor.attr("aria-label")) }
            if (meaningful(titleAttribute)) return titleAttribute.take(140)

            return ""
        }

        // Regras para estruturas comuns de catálogos WordPress/Madara e temas semelhantes.
        val themeEntries = document.select(
            ".page-item-detail, .c-tabs-item__content, .bsx, .utao, .manga-item, .manga-card, " +
                ".series-item, .book-item, article.wp-manga, .listupd .bs, .listupd article, " +
                ".manga-list .item, .series-list .item, .manga-listing .manga"
        ).mapNotNull { card ->
            val anchor = card.selectFirst(
                ".post-title a[href], .tt a[href], .series-title a[href], .manga-name a[href], " +
                    ".manga-title a[href], .item-thumb a[href], .thumb a[href], .bsx a[href], " +
                    ".limit a[href], h2 a[href], h3 a[href], h4 a[href], a[href]"
            ) ?: return@mapNotNull null
            val link = internalLink(anchor.absUrl("href")) ?: return@mapNotNull null
            val title = headingFrom(card)
                ?: clean(anchor.attr("title")).takeIf { meaningful(it) }
                ?: clean(anchor.selectFirst("img")?.attr("alt")).takeIf { meaningful(it) }
                ?: clean(anchor.text()).takeIf { meaningful(it) }
                ?: return@mapNotNull null
            if (!meaningful(title)) return@mapNotNull null
            val cover = imageUrl(
                card.selectFirst(".manga-thumb img, .item-thumb img, .limit img, .thumb img, img")
            )
            val description = clean(card.selectFirst("p,.description,.summary,[class*=description],[class*=summary]")?.text()).take(260)
            UniversalSiteEntry(title.take(140), link, cover, description)
        }

        fun entryFromAnchor(anchor: Element): UniversalSiteEntry? {
            val link = internalLink(anchor.absUrl("href")) ?: return null
            if (anchor.parents().any { it.normalName() in setOf("nav", "footer") }) return null
            var card: Element = anchor
            for (parent in anchor.parents().take(7)) {
                val classes = (parent.className() + " " + parent.id()).lowercase()
                val cardLike = parent.normalName() in setOf("article", "li") ||
                    listOf("manga", "comic", "manhwa", "manhua", "series", "book", "item", "card", "entry", "post", "product", "result")
                        .any { classes.contains(it) }
                if (cardLike) {
                    card = parent
                    if (headingFrom(parent) != null || parent.selectFirst("img") != null) break
                }
            }
            val title = titleFrom(anchor).ifBlank { headingFrom(card).orEmpty() }
            if (!meaningful(title)) return null
            val image = imageUrl(anchor.selectFirst("img") ?: card.selectFirst("img"))
            val description = clean(
                card.selectFirst("p,.description,.summary,[class*=description],[class*=summary]")?.text()
            ).take(260)
            return UniversalSiteEntry(title.take(140), link, image, description)
        }

        val entries = (themeEntries + document.select("a[href]").mapNotNull { entryFromAnchor(it) })
            .distinctBy { it.url }
            .take(250)

        val path = finalUri.path.orEmpty().lowercase()
        val pathLooksLikeReader = path.startsWith("/r/") || listOf("chapter", "capitulo", "capítulo", "reading", "reader", "read", "leitura", "episode", "episodio", "episódio", "pagina", "page")
            .any { path.contains(it) } ||
            (baseHost.contains("vegitoons.black") && Regex("/capitulo/\\d+").containsMatchIn(finalUri.path.orEmpty()))

        // Explicit image selectors cover common manga-reader themes, even when the URL
        // does not include words such as "chapter" or "read".
        val themeReaderImages = document.select(
            ".reading-content img, .page-break img, .wp-manga-chapter-img, .chapter-content img, " +
                ".reader-area img, .chapter-images img, #readerarea img, .container-chapter-reader img, " +
                "#chapter_imgs img, .reading-detail img, .reader img, .comic-page img, .ts-main-image"
        ).mapNotNull { imageUrl(it) }.distinct()

        val readerLike = pathLooksLikeReader || themeReaderImages.size >= 2
        val allImages = document.select("img").mapNotNull { image ->
            val url = imageUrl(image) ?: return@mapNotNull null
            val classes = (image.className() + " " + image.id()).lowercase()
            val width = image.attr("width").toIntOrNull() ?: 0
            if (readerLike && (width >= 180 || listOf("page", "chapter", "reading", "reader", "manga", "scan").any { classes.contains(it) })) url
            else if (!readerLike && width >= 250 && listOf("page", "chapter", "reading", "reader", "manga-page", "scan-page").any { classes.contains(it) }) url
            else null
        }.distinct().take(120)

        val readerImages = if (readerLike) (themeReaderImages + allImages).distinct().take(120)
            else if (entries.size <= 1 && allImages.size >= 4) allImages
            else emptyList()

        val title = clean(document.selectFirst("h1")?.text()).ifBlank { clean(document.title()) }
            .ifBlank { finalUri.host.orEmpty() }

        return UniversalSitePage(title, finalUrl, entries, readerImages)
    }
}

@Composable
internal fun UniversalSiteScreen(startUrl: String, onBack: () -> Unit, onOpenInBrowser: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var page by remember(startUrl) { mutableStateOf<UniversalSitePage?>(null) }
    var loading by remember(startUrl) { mutableStateOf(false) }
    var error by remember(startUrl) { mutableStateOf("") }
    var filter by remember(startUrl) { mutableStateOf("") }
    var readerOpen by remember(startUrl) { mutableStateOf(false) }
    var history by remember(startUrl) { mutableStateOf(emptyList<String>()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun load(url: String, saveHistory: Boolean = true) {
        if (!UniversalSiteAdapter.isSafeHttpsUrl(url)) {
            error = "Por segurança, só são aceites endereços HTTPS válidos."
            return
        }
        val previous = currentUrl
        scope.launch {
            loading = true
            error = ""
            filter = ""
            page = null
            readerOpen = false
            if (saveHistory && previous != url) history = history + previous
            currentUrl = url
            try {
                val loaded = withContext(Dispatchers.IO) { UniversalSiteAdapter.load(url) }
                currentUrl = loaded.url
                page = loaded
                readerOpen = loaded.readerImages.isNotEmpty()
                if (loaded.entries.isEmpty() && loaded.readerImages.isEmpty()) {
                    error = "O site abriu, mas não expôs títulos reconhecíveis no HTML inicial. A aplicação vai tentar ler a página apresentada no navegador integrado."
                }
            } catch (e: Exception) {
                page = UniversalSitePage(
                    title = runCatching { URI(url).host.orEmpty() }.getOrDefault("Site"),
                    url = url,
                    entries = emptyList(),
                    readerImages = emptyList()
                )
                error = e.localizedMessage ?: "Não foi possível extrair este site automaticamente."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(startUrl) { load(startUrl, false) }

    val currentPage = page
    val visibleEntries = currentPage?.entries.orEmpty().filter { it.title.contains(filter.trim(), true) }
    val showBrowser = !loading && !readerOpen && visibleEntries.isEmpty()

    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1A1A22)).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = {
                when {
                    readerOpen -> readerOpen = false
                    history.isNotEmpty() -> {
                        val previous = history.last()
                        history = history.dropLast(1)
                        load(previous, false)
                    }
                    else -> onBack()
                }
            }) { Text("← Voltar") }
            Column(Modifier.weight(1f)) {
                Text(currentPage?.title ?: "A reconhecer site…", color = Color(0xFFB7A0FF), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(currentUrl, color = Color(0xFFB8B5C5), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (loading) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }

        if (error.isNotBlank()) {
            Text(error, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), color = Color(0xFFFFC58F), fontSize = 11.sp)
        }

        if (currentPage != null && readerOpen && currentPage.readerImages.isNotEmpty()) {
            Text("Leitor automático · ${currentPage.readerImages.size} páginas", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Color(0xFFB8B5C5), fontSize = 12.sp)
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = WebViewClient()
                        setBackgroundColor(AndroidColor.rgb(16, 16, 20))
                        loadDataWithBaseURL(currentPage.url, universalReaderHtml(currentPage.title, currentPage.readerImages), "text/html", "UTF-8", null)
                    }
                },
                update = { view ->
                    view.loadDataWithBaseURL(currentPage.url, universalReaderHtml(currentPage.title, currentPage.readerImages), "text/html", "UTF-8", null)
                }
            )
            Button(onClick = { readerOpen = false }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Voltar aos títulos")
            }
        } else if (currentPage != null && visibleEntries.isNotEmpty()) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                label = { Text("Pesquisar nesta fonte") },
                singleLine = true
            )
            Text("${visibleEntries.size} títulos/links encontrados", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = Color(0xFFB8B5C5), fontSize = 12.sp)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleEntries.forEach { entry ->
                    Card(
                        Modifier.fillMaxWidth().clickable { load(entry.url) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!entry.imageUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = entry.imageUrl,
                                    contentDescription = entry.title,
                                    modifier = Modifier.size(width = 84.dp, height = 116.dp),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(Modifier.size(width = 84.dp, height = 116.dp).background(Color(0xFF302A3B), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                    Text("MANGA", color = Color(0xFFB7A0FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (entry.description.isNotBlank()) {
                                    Text(entry.description, color = Color(0xFFB8B5C5), fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                                Text("Abrir página  ›", color = Color(0xFFB7A0FF), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        } else if (showBrowser) {
            Text(
                "Não foi possível reconhecer automaticamente um catálogo nesta página. Podes continuar a navegar no site ou abri-lo sem a tentativa de extração.",
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = Color(0xFFB8B5C5),
                fontSize = 11.sp
            )
            Button(
                onClick = { onOpenInBrowser(currentUrl) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) { Text("Abrir site sem reconhecimento automático") }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        tag = currentUrl
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setBackgroundColor(AndroidColor.rgb(255, 255, 255))
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                return url.isNullOrBlank() || !UniversalSiteAdapter.isSafeHttpsUrl(url)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val web = view ?: return
                                val finishedUrl = url ?: return
                                if (!UniversalSiteAdapter.isSafeHttpsUrl(finishedUrl)) return
                                val originalHost = runCatching { URI(startUrl).host.orEmpty() }.getOrDefault("")
                                val finishedHost = runCatching { URI(finishedUrl).host.orEmpty() }.getOrDefault("")
                                if (!UniversalSiteAdapter.hostsBelongTogether(originalHost, finishedHost)) return

                                web.tag = finishedUrl
                                web.postDelayed({
                                    if (web.url != finishedUrl) return@postDelayed
                                    web.evaluateJavascript(UNIVERSAL_DOM_EXTRACTION_SCRIPT) { raw ->
                                        val extracted = parseUniversalDom(raw, finishedUrl) ?: return@evaluateJavascript
                                        currentUrl = finishedUrl
                                        page = extracted
                                        readerOpen = extracted.readerImages.isNotEmpty()
                                        error = if (extracted.entries.isEmpty() && extracted.readerImages.isEmpty()) {
                                            "O site está aberto, mas a estrutura não permitiu reconhecer títulos automaticamente. Podes continuar a navegar na página."
                                        } else ""
                                    }
                                }, 900L)
                            }
                        }
                        loadUrl(currentUrl)
                        webViewRef = this
                    }
                },
                update = { view ->
                    val tagged = view.tag as? String
                    if (tagged != currentUrl) {
                        view.tag = currentUrl
                        view.loadUrl(currentUrl)
                    }
                }
            )
        } else if (!loading && currentPage != null && visibleEntries.isEmpty() && !readerOpen) {
            Text("Não foram encontrados títulos nesta página. Tenta outra secção do site.", Modifier.padding(16.dp), color = Color(0xFFB8B5C5))
        }
    }
}

private fun parseUniversalDom(raw: String, pageUrl: String): UniversalSitePage? {
    val payload = try {
        JSONTokener(raw).nextValue() as? String
    } catch (_: Exception) {
        null
    } ?: return null

    val data = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val host = runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault("")
    val array = data.optJSONArray("entries") ?: JSONArray()
    val entries = (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val title = item.optString("title").trim()
        val url = item.optString("url").trim()
        val itemHost = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        if (title.isBlank() || !UniversalSiteAdapter.isSafeHttpsUrl(url) ||
            !UniversalSiteAdapter.hostsBelongTogether(host, itemHost)) return@mapNotNull null
        UniversalSiteEntry(
            title = title.take(140),
            url = url,
            imageUrl = item.optString("image").takeIf { UniversalSiteAdapter.isSafeHttpsUrl(it) },
            description = item.optString("description").take(260)
        )
    }.distinctBy { it.url }.take(250)

    val images = data.optJSONArray("readerImages") ?: JSONArray()
    val readerImages = (0 until images.length()).mapNotNull { i ->
        images.optString(i).takeIf { UniversalSiteAdapter.isSafeHttpsUrl(it) }
    }.distinct().take(120)
    val title = data.optString("title").trim().ifBlank { host }
    return UniversalSitePage(title, pageUrl, entries, readerImages)
}

private const val UNIVERSAL_DOM_EXTRACTION_SCRIPT = """
(function() {
  const clean = v => (v || '').replace(/\s+/g, ' ').trim();
  const root = h => (h || '').toLowerCase().replace(/^www\./, '').replace(/^m\./, '');
  const here = root(location.hostname);
  const generic = new Set(['home','homepage','menu','search','buscar','pesquisar','login','sign in','register','next','previous','back','read more','view more','view details','details','more','início','inicio','pesquisa','entrar','registar','ver mais','ver detalhes','ler agora','ler','abrir','contacto','contato','sobre']);
  const excluded = new Set(['category','categories','categoria','categorias','genre','genres','genero','generos','tag','tags','search','buscar','pesquisar','login','logout','register','signup','account','profile','user','users','contact','contato','about','privacy','terms','politica','termos']);
  const sameSite = u => {
    const host = root(u.hostname);
    return host === here || host.endsWith('.' + here) || here.endsWith('.' + host);
  };
  const entries = [];
  const seen = new Set();
  const anchors = Array.from(document.querySelectorAll('a[href]'));
  const meaningful = t => {
    t = clean(t);
    return t.length >= 2 && t.length <= 140 && !generic.has(t.toLowerCase());
  };
  const add = (a, title) => {
    let u;
    try { u = new URL(a.href || a.getAttribute('href') || '', location.href); } catch(e) { return; }
    if (u.protocol !== 'https:' || !sameSite(u)) return;
    const parts = u.pathname.split('/').filter(Boolean);
    if (!parts.length || excluded.has((parts[0] || '').toLowerCase())) return;
    if (u.href.split('#')[0] === location.href.split('#')[0]) return;
    if (!meaningful(title)) return;
    const key = u.href.split('#')[0];
    if (seen.has(key)) return;
    seen.add(key);
    let node = a;
    let image = a.querySelector('img');
    let description = '';
    for (let i = 0; i < 8 && node; i++, node = node.parentElement) {
      if (!image && node.querySelector('img')) image = node.querySelector('img');
      if (!description && node.querySelector('p')) description = clean(node.querySelector('p').innerText || node.querySelector('p').textContent);
      if (i > 0 && node.querySelectorAll('h1,h2,h3,h4,h5,h6').length > 4) break;
    }
    const imageUrl = image ? (image.currentSrc || image.getAttribute('data-src') || image.src || '') : '';
    entries.push({title:title, url:key, image:imageUrl, description:description.slice(0,260)});
  };
  for (const a of anchors) {
    if (a.closest('nav,footer,[role="navigation"]')) continue;
    let title = clean(a.innerText || a.textContent || '');
    if (!meaningful(title)) {
      let node = a;
      for (let i = 0; i < 8 && node; i++, node = node.parentElement) {
        const heading = node.querySelector('h1,h2,h3,h4,h5,h6,[class*="title"],[class*="titulo"],[class*="name"]');
        if (heading && meaningful(heading.innerText || heading.textContent)) {
          title = clean(heading.innerText || heading.textContent);
          break;
        }
      }
    }
    if (!meaningful(title)) {
      const img = a.querySelector('img');
      title = clean((img && img.getAttribute('alt')) || a.getAttribute('title') || a.getAttribute('aria-label') || '');
    }
    add(a, title);
  }
  const path = location.pathname.toLowerCase();
  const readerSelectors = [
    '.reading-content img', '.page-break img', '.wp-manga-chapter-img',
    '.chapter-content img', '.reader-area img', '.chapter-images img',
    '#readerarea img', '.container-chapter-reader img', '#chapter_imgs img',
    '.reading-detail img', '.reader img', '.comic-page img', '.ts-main-image',
    '.wp-manga-reader img', '#manga-reading .page-break img'
  ];
  const explicitReaderImages = [];
  for (const selector of readerSelectors) {
    try {
      for (const img of Array.from(document.querySelectorAll(selector))) {
        const url = img.currentSrc || img.getAttribute('data-src') ||
          img.getAttribute('data-lazy-src') || img.getAttribute('data-original') || img.src || '';
        if (url.startsWith('https://') && !/(logo|avatar|icon|emoji|banner-ad)/i.test(url)) {
          if (!explicitReaderImages.includes(url)) explicitReaderImages.push(url);
        }
      }
    } catch(e) {}
  }
  const readerLike = /^\/r\//.test(location.pathname) || /(chapter|capitulo|capítulo|reading|reader|read|leitura|episode|episodio|episódio|pagina|page)/.test(path)
    || explicitReaderImages.length >= 2;
  const readerImages = explicitReaderImages.slice(0,120);
  if (readerLike) {
    for (const img of Array.from(document.querySelectorAll('img'))) {
      const cls = ((img.className || '') + ' ' + (img.id || '')).toLowerCase();
      const width = Number(img.getAttribute('width') || img.naturalWidth || 0);
      const url = img.currentSrc || img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('data-original') || img.src || '';
      if (url.startsWith('https://') && (width >= 180 || /(page|chapter|reading|reader|manga|scan)/.test(cls)) && !/(logo|avatar|icon|emoji|banner-ad)/i.test(url)) {
        if (!readerImages.includes(url)) readerImages.push(url);
      }
      if (readerImages.length >= 120) break;
    }
  }
  const heading = document.querySelector('h1');
  return JSON.stringify({
    title: clean(heading ? heading.innerText : document.title) || location.hostname,
    entries: entries.slice(0,250),
    readerImages: readerImages
  });
})();
"""

private fun universalReaderHtml(title: String, imageUrls: List<String>): String {
    val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val images = imageUrls.joinToString("\n") { raw ->
        val url = raw.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        "<img src=\"$url\" alt=\"Página do capítulo\" />"
    }
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0;background:#101014;color:#fff;font-family:sans-serif}h2{padding:12px;font-size:18px}img{display:block;width:100%;height:auto;margin:0 auto 8px}</style>
</head><body><h2>$safeTitle</h2>$images</body></html>"""
}
