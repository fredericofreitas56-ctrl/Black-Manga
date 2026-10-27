package com.blackmanga.app

import java.text.Normalizer
import java.net.URI
import java.net.URLDecoder

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun nexusCatalogEntryKey(rawUrl: String): String =
    rawUrl.trim().substringBefore("#").substringBefore("?")
        .replace("https://www.", "https://", ignoreCase = true)
        .replace("http://www.", "https://", ignoreCase = true)
        .replace("http://", "https://", ignoreCase = true)
        .trimEnd('/')
        .lowercase()

private fun nexusCatalogTitleKey(rawTitle: String): String =
    Normalizer.normalize(rawTitle.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

private fun nexusCatalogSlugKey(rawUrl: String): String {
    val path = runCatching { URI(rawUrl.trim().substringBefore("?").substringBefore("#")).path.orEmpty() }
        .getOrDefault("")
    val rawSlug = path.substringAfterLast('/')
    val decoded = runCatching { URLDecoder.decode(rawSlug, "UTF-8") }.getOrDefault(rawSlug)
    return nexusCatalogTitleKey(decoded.removeSuffix(".html").removeSuffix(".htm"))
}

private fun mergeNexusCover(target: NexusEntry, source: NexusEntry): NexusEntry =
    target.copy(
        imageUrl = source.imageUrl?.takeIf { it.isNotBlank() } ?: target.imageUrl,
        description = target.description.ifBlank { source.description },
        kind = target.kind.ifBlank { source.kind },
        status = target.status.ifBlank { source.status },
        rating = target.rating.ifBlank { source.rating },
        chapterCount = target.chapterCount.ifBlank { source.chapterCount },
        genres = (target.genres + source.genres).distinct()
    )

@Composable
internal fun NexusToonsScreen(startUrl: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val imageContext = LocalContext.current
    val paper = Color(0xFFFFF8F5)
    val ink = Color(0xFF292321)
    val muted = Color(0xFF8A7268)
    val accent = Color(0xFF8B4E32)
    val paleCard = Color(0xFFFFEEE6)

    var currentUrl by remember(startUrl) { mutableStateOf(NexusToonsAdapter.normalizeCatalogueStart(startUrl)) }
    var currentCatalogPageUrl by remember(startUrl) { mutableStateOf(NexusToonsAdapter.normalizeCatalogueStart(startUrl)) }
    var history by remember(startUrl) { mutableStateOf(emptyList<String>()) }
    var page by remember { mutableStateOf<NexusPage?>(null) }
    // Keep catalogue cards across page requests; page metadata may change without replacing the grid.
    var accumulatedCatalogEntries by remember(startUrl) { mutableStateOf<List<NexusEntry>>(emptyList()) }
    var resolvedCoverUrls by remember(startUrl) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var rejectedCoverUrls by remember(startUrl) { mutableStateOf<Set<String>>(emptySet()) }
    var coverLookupFailures by remember(startUrl) { mutableStateOf<Set<String>>(emptySet()) }
    var coverLookupsInProgress by remember(startUrl) { mutableStateOf<Set<String>>(emptySet()) }
    // The screen URL stays on the first catalogue page; this cursor advances independently.
    var nextCatalogPageUrl by remember(startUrl) { mutableStateOf<String?>(null) }
    var hasMoreCatalogPages by remember(startUrl) { mutableStateOf(true) }
    var appendingCatalogPage by remember { mutableStateOf(false) }
    var catalogEntryCountBeforeAppend by remember { mutableStateOf(0) }
    var viewMode by remember(startUrl) {
        mutableStateOf(
            when {
                NexusToonsAdapter.isReaderUrl(startUrl) -> "reader"
                NexusToonsAdapter.isCatalogueUrl(startUrl) -> "catalog"
                else -> "detail"
            }
        )
    }
    var loading by remember { mutableStateOf(false) }
    var loadingMoreCatalog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("Todos") }
    var fallbackGenre by remember { mutableStateOf("Todos") }
    var selectedWork by remember { mutableStateOf<NexusEntry?>(null) }
    var selectedChapterUrl by remember { mutableStateOf<String?>(null) }
    var selectedChapterTitle by remember { mutableStateOf("") }
    var favourite by remember { mutableStateOf(false) }
    var readerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerTitle by remember { mutableStateOf("") }
    var needsDomFallback by remember { mutableStateOf(false) }
    var domFallbackLoading by remember { mutableStateOf(false) }
    var domFallbackMessage by remember { mutableStateOf("") }

    fun load(url: String, saveHistory: Boolean = true, appendEntries: Boolean = false, forcedMode: String? = null) {
        val destination = NexusToonsAdapter.normalizeCatalogueStart(url)
        if (!NexusToonsAdapter.supports(destination)) {
            error = "Este endereço não pertence ao Nexus Toons."
            return
        }
        val targetMode = forcedMode ?: when {
            NexusToonsAdapter.isReaderUrl(destination) -> "reader"
            NexusToonsAdapter.isCatalogueUrl(destination) -> "catalog"
            else -> "detail"
        }
        val previousPage = if (appendEntries) page else null
        val sourcePageForAppend = if (appendEntries) currentCatalogPageUrl else destination
        val previousEntries = if (appendEntries) {
            accumulatedCatalogEntries.ifEmpty { previousPage?.entries.orEmpty() }
        } else emptyList()
        val previousEntryUrls = previousEntries.mapTo(linkedSetOf()) { it.url }
        if (saveHistory && !appendEntries && currentUrl != destination) history = history + currentUrl
        scope.launch {
            appendingCatalogPage = appendEntries && targetMode == "catalog"
            catalogEntryCountBeforeAppend = previousEntries.size
            loading = !appendEntries
            loadingMoreCatalog = appendEntries && targetMode == "catalog"
            error = ""
            if (!appendEntries) {
                filter = ""
                typeFilter = "Todos"
                fallbackGenre = "Todos"
                page = null
                accumulatedCatalogEntries = emptyList()
                resolvedCoverUrls = emptyMap()
                rejectedCoverUrls = emptySet()
                coverLookupFailures = emptySet()
                coverLookupsInProgress = emptySet()
                nextCatalogPageUrl = null
                currentCatalogPageUrl = destination
                hasMoreCatalogPages = true
                readerImages = emptyList()
                if (targetMode != "reader") readerTitle = ""
                selectedChapterUrl = null
                selectedChapterTitle = ""
            }
            needsDomFallback = false
            domFallbackLoading = false
            domFallbackMessage = ""
            // Pagination is data-only: don't change currentUrl/viewMode or recreate the hidden WebView.
            if (!appendEntries) {
                currentUrl = destination
                viewMode = targetMode
                if (targetMode == "catalog") selectedWork = null
            }

            try {
                val requestedApiPage = Regex("[?&]blackmanga_api_page=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(destination)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val directPage: NexusPage? = try {
                    when {
                        appendEntries && targetMode == "catalog" ->
                            NexusToonsAdapter.loadNextCataloguePage(
                                currentPageUrl = sourcePageForAppend,
                                preferredNextUrl = destination,
                                existingEntryUrls = previousEntryUrls
                            )
                        targetMode == "catalog" -> {
                            val apiPage = NexusToonsAdapter.loadApiCataloguePage(requestedApiPage ?: 1)
                            if (apiPage != null) {
                                val visualPage = runCatching {
                                    NexusToonsAdapter.load("https://nexustoons.com/biblioteca", timeoutMs = 10000)
                                }.getOrNull()
                                if (visualPage == null) apiPage else {
                                    val visualByUrl = visualPage.entries.associateBy { nexusCatalogEntryKey(it.url) }
                                    val visualBySlug = visualPage.entries.groupBy { nexusCatalogSlugKey(it.url) }
                                    val visualByTitle = visualPage.entries.groupBy { nexusCatalogTitleKey(it.title) }
                                    apiPage.copy(entries = apiPage.entries.map { entry ->
                                        val visual = visualByUrl[nexusCatalogEntryKey(entry.url)]
                                            ?: visualBySlug[nexusCatalogSlugKey(entry.url)]?.firstOrNull()
                                            ?: visualByTitle[nexusCatalogTitleKey(entry.title)]?.firstOrNull()
                                        if (visual == null) entry else mergeNexusCover(entry, visual)
                                    }, genres = visualPage.genres)
                                }
                            } else NexusToonsAdapter.load(destination)
                        }
                        else -> NexusToonsAdapter.load(destination)
                    }
                } catch (_: Exception) {
                    null
                }
                val shouldTryUniversal = directPage == null ||
                    (directPage.entries.isEmpty() && directPage.chapters.isEmpty() && directPage.readerImages.isEmpty())
                val universalPage: UniversalSitePage? = if (shouldTryUniversal) {
                    try {
                        withContext(Dispatchers.IO) { UniversalSiteAdapter.load(destination) }
                    } catch (_: Exception) {
                        null
                    }
                } else null

                if (directPage == null && universalPage == null) {
                    throw IllegalStateException("Não foi possível carregar esta página do Nexus Toons. Verifica a ligação e tenta novamente.")
                }

                val freshEntries = (
                    directPage?.entries.orEmpty() +
                        universalPage?.entries.orEmpty().map { entry ->
                            NexusEntry(entry.title, entry.url, entry.imageUrl, entry.description)
                        }
                    ).distinctBy { it.url }
                val mergedEntries = (previousEntries + freshEntries)
                    .distinctBy { it.url }.take(5000)
                accumulatedCatalogEntries = mergedEntries
                if (targetMode == "catalog") {
                    if (!appendEntries) {
                        currentCatalogPageUrl = directPage?.url ?: universalPage?.url ?: destination
                    } else if (freshEntries.any { it.url !in previousEntryUrls }) {
                        currentCatalogPageUrl = directPage?.url ?: destination
                    }
                }
                val cursorBaseUrl = directPage?.url ?: destination
                val nextCursor = (directPage?.nextPageUrl
                    ?.takeIf { it != cursorBaseUrl && NexusToonsAdapter.supports(it) }
                    ?: NexusToonsAdapter.guessedNextPageUrl(cursorBaseUrl))
                if (targetMode == "catalog") {
                    if (!appendEntries) {
                        nextCatalogPageUrl = nextCursor
                        hasMoreCatalogPages = nextCursor != null
                    } else {
                        val addedCount = freshEntries.count { fresh -> previousEntries.none { it.url == fresh.url } }
                        hasMoreCatalogPages = addedCount > 0 && nextCursor != null
                        nextCatalogPageUrl = nextCursor.takeIf { hasMoreCatalogPages }
                        loadingMoreCatalog = false
                        appendingCatalogPage = false
                        if (addedCount == 0) {
                            domFallbackMessage = "Esta página não trouxe títulos novos. Mantive os títulos que já estavam no catálogo."
                        }
                    }
                }
                val mergedChapters = directPage?.chapters.orEmpty().distinctBy { it.url }.take(600)
                val mergedImages = (directPage?.readerImages.orEmpty() + universalPage?.readerImages.orEmpty())
                    .distinct().take(150)
                val result = NexusPage(
                    title = directPage?.title?.takeIf { it.isNotBlank() }
                        ?: universalPage?.title?.takeIf { it.isNotBlank() }
                        ?: selectedWork?.title?.takeIf { targetMode == "detail" }
                        ?: "Nexus Toons",
                    url = if (appendEntries) previousPage?.url ?: currentUrl
                        else directPage?.url ?: universalPage?.url ?: destination,
                    entries = mergedEntries,
                    readerImages = mergedImages,
                    chapters = mergedChapters,
                    genres = directPage?.genres.orEmpty().distinctBy { it.url }.take(40),
                    nextPageUrl = if (targetMode == "catalog") nextCatalogPageUrl else directPage?.nextPageUrl,
                    coverUrl = directPage?.coverUrl,
                    synopsis = directPage?.synopsis.orEmpty(),
                    author = directPage?.author.orEmpty(),
                    status = directPage?.status.orEmpty(),
                    kind = directPage?.kind.orEmpty(),
                    rating = directPage?.rating.orEmpty(),
                    chapterCount = directPage?.chapterCount.orEmpty()
                )
                page = result
                if (result.readerImages.isNotEmpty()) {
                    readerImages = result.readerImages
                    if (readerTitle.isBlank()) readerTitle = result.title
                }

                val coverUrls = result.entries.mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }
                val coverCounts = coverUrls.groupingBy { it }.eachCount()
                val repeatedCovers = coverCounts.values.any { it >= 3 }
                val poorCoverCoverage = result.entries.size >= 8 &&
                    (coverUrls.size < result.entries.size * 0.65 || repeatedCovers)
                // The API supplies the complete title list, but normally has no cover URLs.
                // On the first catalogue load, scan the real WebView DOM to enrich those titles
                // with covers. The DOM merge below starts from accumulatedCatalogEntries, so it
                // adds artwork without replacing the 500 valid API titles with a partial DOM list.
                // Never run this whole-page scan for "load more"; pagination stays API-only.
                val needsExtraction = when {
                    appendEntries && targetMode == "catalog" -> false
                    targetMode == "reader" -> result.readerImages.isEmpty()
                    targetMode == "detail" -> result.chapters.isEmpty()
                    else -> freshEntries.isEmpty() || result.entries.size < 8 || poorCoverCoverage
                }
                if (appendEntries && targetMode == "catalog" && !needsExtraction) {
                    appendingCatalogPage = false
                    loadingMoreCatalog = false
                }
                if (needsExtraction) {
                    needsDomFallback = true
                    domFallbackLoading = true
                }
            } catch (e: Exception) {
                error = e.localizedMessage ?: "O catálogo não conseguiu carregar esta página."
                if (appendEntries && targetMode == "catalog") {
                    // Keep the visible grid intact if fetching a later page fails.
                    hasMoreCatalogPages = false
                    nextCatalogPageUrl = null
                    appendingCatalogPage = false
                    loadingMoreCatalog = false
                    needsDomFallback = false
                    domFallbackLoading = false
                    domFallbackMessage = "Não foi possível carregar mais títulos. Os títulos anteriores foram mantidos."
                } else {
                    page = NexusPage(
                        title = selectedWork?.title ?: "Nexus Toons",
                        url = destination,
                        entries = previousEntries,
                        readerImages = emptyList(),
                        chapters = emptyList()
                    )
                    accumulatedCatalogEntries = previousEntries
                    needsDomFallback = true
                    domFallbackLoading = true
                }
            } finally {
                loading = false
                if (!domFallbackLoading) loadingMoreCatalog = false
            }
        }
    }

    LaunchedEffect(startUrl) { load(startUrl, false) }

    fun goBack() {
        if (history.isNotEmpty()) {
            val previous = history.last()
            history = history.dropLast(1)
            load(previous, false)
        } else if (viewMode != "catalog") {
            load("https://nexustoons.com/biblioteca", false, forcedMode = "catalog")
        } else {
            onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(if (viewMode == "reader") Color(0xFF101014) else paper)) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (viewMode == "reader") Color(0xFF101014) else Color(0xFFFFF2EB))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { goBack() }) { Text("Voltar") }
            Column(Modifier.weight(1f)) {
                Text(
                    when (viewMode) {
                        "reader" -> readerTitle.ifBlank { "Nexus Toons · Leitor" }
                        "detail" -> selectedWork?.title ?: page?.title ?: "Nexus Toons"
                        else -> "Nexus Toons · Catálogo"
                    },
                    color = if (viewMode == "reader") Color(0xFFB7A0FF) else accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(currentUrl, color = if (viewMode == "reader") Color(0xFFB8B5C5) else muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (loading) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = accent)
            }
        }

        if (error.isNotBlank() && !domFallbackLoading) {
            Text(error, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), color = Color(0xFFB53C32), fontSize = 12.sp)
        }

        when (viewMode) {
            "reader" -> {
                if (readerImages.isNotEmpty()) {
                    Text(
                        readerTitle.ifBlank { page?.title ?: "Capítulo" },
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        readerImages.size.toString() + " páginas",
                        Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        color = Color(0xFFB8B5C5),
                        fontSize = 12.sp
                    )
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient = WebViewClient()
                                setBackgroundColor(android.graphics.Color.rgb(16, 16, 20))
                                loadDataWithBaseURL(
                                    "https://nexustoons.com/",
                                    buildReaderHtml(readerTitle.ifBlank { "Capítulo" }, readerImages),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        },
                        update = { view ->
                            view.loadDataWithBaseURL(
                                "https://nexustoons.com/",
                                buildReaderHtml(readerTitle.ifBlank { "Capítulo" }, readerImages),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    )
                    Button(
                        onClick = { goBack() },
                        modifier = Modifier.fillMaxWidth().padding(10.dp)
                    ) { Text("Voltar aos capítulos") }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("A carregar as páginas do capítulo…", color = ink, fontWeight = FontWeight.Bold)
                        Text(
                            if (domFallbackLoading) "A aguardar o conteúdo do leitor."
                            else domFallbackMessage.ifBlank { "Não foram encontradas imagens de leitura nesta página." },
                            color = muted
                        )
                        Button(onClick = { load(currentUrl, false, forcedMode = "reader") }) { Text("Tentar novamente") }
                    }
                }
            }

            "detail" -> {
                val loaded = page
                if (loaded == null) {
                    Text("A carregar os detalhes da obra…", Modifier.padding(18.dp), color = ink)
                } else {
                    val displayTitle = if (
                        loaded.title.equals("Nexus Toons", true) ||
                        loaded.title.equals("NEXUS TOONS", true) ||
                        loaded.title.contains("Leia Mangás", true)
                    ) selectedWork?.title ?: loaded.title else loaded.title
                    val coverUrl = selectedWork?.imageUrl ?: loaded.coverUrl
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                            if (!coverUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(imageContext)
                                        .data(coverUrl)
                                        .setHeader("Referer", "https://nexustoons.com/")
                                        .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = displayTitle,
                                    modifier = Modifier.size(width = 124.dp, height = 178.dp),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    Modifier.size(width = 124.dp, height = 178.dp)
                                        .background(paleCard, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("NEXUS TOONS", color = accent, fontSize = 10.sp)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(displayTitle, color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                OutlinedButton(onClick = { favourite = !favourite }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (favourite) "♥ Adicionado aos favoritos" else "♡ Adicionar aos favoritos", color = accent)
                                }
                                if (loaded.rating.isNotBlank()) Text("★ " + loaded.rating, color = accent)
                                if (loaded.kind.isNotBlank()) Text(loaded.kind, color = muted)
                                if (loaded.status.isNotBlank()) Text(loaded.status, color = muted)
                                if (loaded.chapterCount.isNotBlank()) Text(loaded.chapterCount + " capítulos", color = muted)
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFFFFE9DE), RoundedCornerShape(10.dp)).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Fonte", color = muted, fontSize = 12.sp)
                                Text("Nexus Toons", color = ink, fontWeight = FontWeight.SemiBold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Autor", color = muted, fontSize = 12.sp)
                                Text(loaded.author.ifBlank { "Não indicado" }, color = ink, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        val synopsis = loaded.synopsis.ifBlank { selectedWork?.description.orEmpty() }
                        if (synopsis.isNotBlank()) {
                            Text("Sinopse", color = accent, fontWeight = FontWeight.Bold)
                            Text(synopsis, color = ink)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Capítulos", color = ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(loaded.chapters.size.toString() + " capítulos encontrados", color = muted, fontSize = 12.sp)
                            }
                            if (selectedChapterUrl != null) {
                                Button(onClick = { load(selectedChapterUrl.orEmpty(), forcedMode = "reader") }) {
                                    Text("Ler")
                                }
                            }
                        }

                        if (loaded.chapters.isEmpty()) {
                            Text(
                                if (domFallbackLoading) "A procurar a lista de capítulos…"
                                else domFallbackMessage.ifBlank { "Não foi possível reconhecer os capítulos desta obra." },
                                color = muted
                            )
                        } else {
                            loaded.chapters.forEachIndexed { index, chapter ->
                                val isSelected = selectedChapterUrl == chapter.url
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedChapterUrl = chapter.url
                                        selectedChapterTitle = chapter.title
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFFFFE5D8) else Color.White
                                    )
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(chapter.title, color = ink, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            if (isSelected) Text("Selecionado", color = accent, fontSize = 11.sp)
                                        }
                                        TextButton(onClick = {
                                            selectedChapterUrl = chapter.url
                                            selectedChapterTitle = chapter.title
                                        }) { Text("Selecionar", color = accent) }
                                        Button(onClick = {
                                            selectedChapterUrl = chapter.url
                                            selectedChapterTitle = chapter.title
                                            readerTitle = displayTitle + " · " + chapter.title
                                            load(chapter.url, forcedMode = "reader")
                                        }) { Text("Ler") }
                                    }
                                }
                            }
                        }
                    }
                    if (selectedChapterUrl != null && loaded.chapters.isNotEmpty()) {
                        Button(
                            onClick = {
                                readerTitle = displayTitle + " · " + selectedChapterTitle
                                load(selectedChapterUrl.orEmpty(), forcedMode = "reader")
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text("Ler capítulo selecionado") }
                    }
                }
            }

            else -> {
                val loaded = page
                if (loaded == null) {
                    Text("A preparar o catálogo do Nexus Toons…", Modifier.padding(18.dp), color = ink)
                } else {
                    Text(
                        if (loaded.title.isBlank() || loaded.title.equals("Nexus Toons", true)) "NEXUS TOONS" else loaded.title,
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                        color = ink,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                        label = { Text("Pesquisar títulos") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ink,
                            unfocusedTextColor = ink,
                            focusedLabelColor = accent,
                            unfocusedLabelColor = muted,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color(0xFFCBB4A8),
                            cursorColor = accent,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        )
                    )

                    if (loaded.genres.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text("Géneros", Modifier.padding(horizontal = 5.dp, vertical = 12.dp), color = accent, fontWeight = FontWeight.Bold)
                            loaded.genres.forEach { genre ->
                                FilterChip(
                                    selected = currentUrl == genre.url,
                                    onClick = { load(genre.url, forcedMode = "catalog") },
                                    label = { Text(genre.title, color = ink) }
                                )
                            }
                        }
                    } else {
                        val commonGenres = listOf(
                            "Todos", "Ação", "Aventura", "Artes marciais", "Comédia",
                            "Drama", "Fantasia", "Mistério", "Romance", "Sistema"
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            commonGenres.forEach { genre ->
                                FilterChip(
                                    selected = fallbackGenre == genre,
                                    onClick = { fallbackGenre = genre },
                                    label = { Text(genre, color = ink) }
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf("Todos", "Manhwa", "Manhua", "Manga", "Novel").forEach { kind ->
                            FilterChip(
                                selected = typeFilter == kind,
                                onClick = { typeFilter = kind },
                                label = { Text(kind, color = ink) }
                            )
                        }
                    }

                    val bySearch = loaded.entries.filter { it.title.contains(filter.trim(), true) }
                    val byGenre = if (loaded.genres.isNotEmpty() || fallbackGenre == "Todos") bySearch else bySearch.filter { entry ->
                        entry.genres.any { it.contains(fallbackGenre, true) } ||
                            entry.title.contains(fallbackGenre, true) ||
                            entry.description.contains(fallbackGenre, true)
                    }
                    val visibleEntries = if (typeFilter == "Todos") byGenre else byGenre.filter {
                        it.kind.equals(typeFilter, ignoreCase = true)
                    }
                    // Não ocultar capas quando dois cartões partilham o mesmo URL.

                    Text(
                        if (filter.isNotBlank() || typeFilter != "Todos" || fallbackGenre != "Todos")
                            "${visibleEntries.size} títulos visíveis · ${loaded.entries.size} carregados"
                        else "${loaded.entries.size} títulos carregados",
                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        color = muted,
                        fontSize = 12.sp
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(visibleEntries, key = { it.url }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedWork = entry
                                    selectedChapterUrl = null
                                    selectedChapterTitle = ""
                                    load(entry.url, forcedMode = "detail")
                                },
                                shape = RoundedCornerShape(5.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val image = sequenceOf(entry.imageUrl, resolvedCoverUrls[entry.url])
                                        .filterNotNull()
                                        .map { it.trim() }
                                        .firstOrNull { it.isNotBlank() && it !in rejectedCoverUrls }
                                    LaunchedEffect(entry.url, image, needsDomFallback, domFallbackLoading) {
                                        // Finish the shared WebView scan first; don't launch
                                        // one HTTP request per card while the scan is still moving.
                                        if (image.isNullOrBlank() &&
                                            !needsDomFallback &&
                                            !domFallbackLoading &&
                                            entry.url !in resolvedCoverUrls &&
                                            entry.url !in coverLookupFailures &&
                                            entry.url !in coverLookupsInProgress
                                        ) {
                                            coverLookupsInProgress = coverLookupsInProgress + entry.url
                                            try {
                                                val cover = NexusToonsAdapter.fetchCoverForEntry(entry.url)
                                                if (!cover.isNullOrBlank() && cover !in rejectedCoverUrls) {
                                                    resolvedCoverUrls = resolvedCoverUrls + (entry.url to cover)
                                                    val updatedEntries = accumulatedCatalogEntries.map { item ->
                                                        if (item.url == entry.url && item.imageUrl.isNullOrBlank()) {
                                                            item.copy(imageUrl = cover)
                                                        } else item
                                                    }
                                                    accumulatedCatalogEntries = updatedEntries
                                                    page = page?.copy(entries = page?.entries.orEmpty().map { item ->
                                                        if (item.url == entry.url && item.imageUrl.isNullOrBlank()) item.copy(imageUrl = cover) else item
                                                    })
                                                } else {
                                                    coverLookupFailures = coverLookupFailures + entry.url
                                                }
                                            } catch (_: Exception) {
                                                coverLookupFailures = coverLookupFailures + entry.url
                                            } finally {
                                                coverLookupsInProgress = coverLookupsInProgress - entry.url
                                            }
                                        }
                                    }
                                    if (!image.isNullOrBlank()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(imageContext)
                                                .data(image)
                                                .setHeader("Referer", "https://nexustoons.com/")
                                                .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = entry.title,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
                                            contentScale = ContentScale.Crop,
                                            onError = {
                                                // API image fields can contain a URL which is not an image.
                                                rejectedCoverUrls = rejectedCoverUrls + image
                                                coverLookupFailures = coverLookupFailures - entry.url
                                                resolvedCoverUrls = resolvedCoverUrls - entry.url
                                                accumulatedCatalogEntries = accumulatedCatalogEntries.map { item ->
                                                    if (item.url == entry.url) item.copy(imageUrl = null) else item
                                                }
                                                page = page?.copy(entries = page?.entries.orEmpty().map { item ->
                                                    if (item.url == entry.url) item.copy(imageUrl = null) else item
                                                })
                                            }
                                        )
                                    } else {
                                        Box(
                                            Modifier.fillMaxWidth().aspectRatio(0.68f)
                                                .background(Color(0xFFFFE9DE), RoundedCornerShape(5.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (entry.url in coverLookupsInProgress || domFallbackLoading || needsDomFallback) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    color = accent,
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        entry.title,
                                        color = ink,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp
                                    )
                                    if (entry.rating.isNotBlank()) {
                                        Text("★ " + entry.rating, color = accent, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        val nextPage = nextCatalogPageUrl
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Button(
                                onClick = {
                                    if (nextPage != null) {
                                        load(nextPage, saveHistory = false, appendEntries = true, forcedMode = "catalog")
                                    } else {
                                        domFallbackMessage = "Esta página não disponibilizou um link para carregar mais títulos."
                                    }
                                },
                                enabled = hasMoreCatalogPages && nextPage != null && !loading && !loadingMoreCatalog,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                if (loadingMoreCatalog) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(Modifier.size(8.dp))
                                        Text("A carregar mais títulos…")
                                    }
                                } else {
                                    Text(
                                        if (hasMoreCatalogPages && nextPage != null) "Carregar mais títulos"
                                        else "Não foram encontrados mais títulos"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (needsDomFallback && domFallbackLoading) {
            Text("A identificar capas e capítulos…", Modifier.padding(6.dp), color = muted, fontSize = 11.sp)
        }
        if (domFallbackMessage.isNotBlank() && !domFallbackLoading &&
            page?.entries.isNullOrEmpty() && page?.chapters.isNullOrEmpty() && readerImages.isEmpty()) {
            Text(domFallbackMessage, Modifier.padding(10.dp), color = muted, fontSize = 12.sp)
            TextButton(onClick = { load(currentUrl, false) }) { Text("Tentar novamente", color = accent) }
        }
        }

        if (needsDomFallback) {
            key(currentUrl) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            visibility = View.INVISIBLE
                            tag = currentUrl
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.blockNetworkImage = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            webViewClient = object : WebViewClient() {
                                @Suppress("DEPRECATION")
                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    domFallbackLoading = false
                                    domFallbackMessage = "Não foi possível ler o conteúdo desta página. Verifica a ligação."
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val web = view ?: return
                                    val finishedUrl = url ?: return
                                    if (!NexusToonsAdapter.supports(finishedUrl)) return
                                    var extractionAttempts = 0
                                    val accumulatedDomEntries = linkedMapOf<String, NexusEntry>()
                                    fun extractDomWhenReady() {
                                        // Infinite-scroll scripts may update the URL with history.replaceState
                                        // while keeping the same page alive. Do not abandon the scan because
                                        // web.url no longer equals the original onPageFinished URL.
                                        val activeWebUrl = web.url ?: finishedUrl
                                        if (!NexusToonsAdapter.supports(activeWebUrl)) {
                                            domFallbackLoading = false
                                            needsDomFallback = false
                                            if (appendingCatalogPage) {
                                                appendingCatalogPage = false
                                                loadingMoreCatalog = false
                                            }
                                            domFallbackMessage = "A extração foi interrompida porque o site saiu do NexusToons."
                                            return
                                        }
                                        extractionAttempts += 1
                                        web.evaluateJavascript(NEXUS_DOM_EXTRACTION_SCRIPT) { raw ->
                                            val payload = try {
                                                JSONTokener(raw).nextValue() as? String
                                            } catch (_: Exception) { null }
                                            val data = try { payload?.let { JSONObject(it) } } catch (_: Exception) { null }
                                            if (data == null) {
                                                if (extractionAttempts < 6) {
                                                    web.postDelayed({ extractDomWhenReady() }, 1200L)
                                                } else {
                                                    domFallbackLoading = false
                                                    needsDomFallback = false
                                                    if (appendingCatalogPage) {
                                                        hasMoreCatalogPages = false
                                                        appendingCatalogPage = false
                                                    }
                                                    domFallbackMessage = "Não foi possível reconhecer o catálogo. Tenta novamente."
                                                }
                                                return@evaluateJavascript
                                            }

                                            fun parseEntryArray(name: String, maxCount: Int): List<NexusEntry> {
                                                val array = data.optJSONArray(name) ?: JSONArray()
                                                return (0 until minOf(array.length(), maxCount)).mapNotNull { index ->
                                                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                                                    val title = item.optString("title", "").trim()
                                                    val entryUrl = item.optString("url", "").trim()
                                                    if (title.isBlank() || entryUrl.isBlank() ||
                                                        !NexusToonsAdapter.supports(entryUrl) ||
                                                        !entryUrl.startsWith("https://")) return@mapNotNull null
                                                    val genreArray = item.optJSONArray("genres") ?: JSONArray()
                                                    val genres = (0 until genreArray.length()).map { genreArray.optString(it) }
                                                    NexusEntry(
                                                        title = title,
                                                        url = entryUrl,
                                                        imageUrl = item.optString("image", "").takeIf { it.startsWith("https://") },
                                                        description = item.optString("description", "").take(500),
                                                        kind = item.optString("kind", ""),
                                                        status = item.optString("status", ""),
                                                        rating = item.optString("rating", ""),
                                                        chapterCount = item.optString("chapterCount", ""),
                                                        genres = genres
                                                    )
                                                }.distinctBy { it.url }
                                            }

                                            val extractedEntries = parseEntryArray("entries", 500)
                                            val extractedChapters = parseEntryArray("chapters", 600)
                                            val genreArray = data.optJSONArray("genres") ?: JSONArray()
                                            val extractedGenres = (0 until minOf(genreArray.length(), 40)).mapNotNull { index ->
                                                val item = genreArray.optJSONObject(index) ?: return@mapNotNull null
                                                val title = item.optString("title", "").trim()
                                                val link = item.optString("url", "").trim()
                                                if (title.isBlank() || !NexusToonsAdapter.supports(link)) null else NexusGenre(title, link)
                                            }.distinctBy { it.url }
                                            val imageArray = data.optJSONArray("readerImages") ?: JSONArray()
                                            val extractedImages = (0 until imageArray.length()).mapNotNull { index ->
                                                imageArray.optString(index).takeIf { it.startsWith("https://") && !it.contains("data:image", true) }
                                            }.distinct().take(150)

                                            // Guardar os resultados de cada tentativa: as imagens lazy-loaded podem
                                            // aparecer em momentos diferentes, depois de forçar o carregamento/scroll.
                                            extractedEntries.forEach { fresh ->
                                                val old = accumulatedDomEntries[fresh.url]
                                                accumulatedDomEntries[fresh.url] = if (old == null) fresh else old.copy(
                                                    imageUrl = fresh.imageUrl?.takeIf { it.isNotBlank() } ?: old.imageUrl,
                                                    title = old.title.ifBlank { fresh.title },
                                                    description = old.description.ifBlank { fresh.description },
                                                    kind = old.kind.ifBlank { fresh.kind },
                                                    status = old.status.ifBlank { fresh.status },
                                                    rating = old.rating.ifBlank { fresh.rating },
                                                    chapterCount = old.chapterCount.ifBlank { fresh.chapterCount },
                                                    genres = (old.genres + fresh.genres).distinct()
                                                )
                                            }
                                            val domEntries = accumulatedDomEntries.values.toList()
                                            val previewByUrl = linkedMapOf<String, NexusEntry>()
                                            accumulatedCatalogEntries.forEach { previewByUrl[nexusCatalogEntryKey(it.url)] = it }
                                            page?.entries.orEmpty().forEach { previewByUrl[nexusCatalogEntryKey(it.url)] = it }
                                            domEntries.forEach { fresh ->
                                                val key = nexusCatalogEntryKey(fresh.url)
                                                val titleKey = nexusCatalogTitleKey(fresh.title)
                                                val slugKey = nexusCatalogSlugKey(fresh.url)
                                                val matchingKey = key.takeIf { previewByUrl.containsKey(it) }
                                                    ?: previewByUrl.entries.firstOrNull { (_, existing) ->
                                                        slugKey.isNotBlank() && slugKey == nexusCatalogSlugKey(existing.url)
                                                    }?.key
                                                    ?: previewByUrl.entries.firstOrNull { (_, existing) ->
                                                        titleKey.length >= 4 && titleKey == nexusCatalogTitleKey(existing.title)
                                                    }?.key
                                                val old = matchingKey?.let { previewByUrl[it] }
                                                if (old == null) previewByUrl[key] = fresh
                                                else previewByUrl[matchingKey!!] = mergeNexusCover(old, fresh)
                                            }
                                            val previewEntries = previewByUrl.values.toList()
                                            val previewCovers = previewEntries.count { !it.imageUrl.isNullOrBlank() }
                                            val needsMoreCovers = viewMode == "catalog" &&
                                                previewEntries.size >= 8 &&
                                                previewCovers < (previewEntries.size * 0.65).toInt()
                                            val hasNoUsefulData = extractedEntries.isEmpty() &&
                                                extractedChapters.isEmpty() && extractedImages.isEmpty()
                                            if ((hasNoUsefulData || needsMoreCovers) && extractionAttempts < 20) {
                                                // A grelha usa lazy-loading: continuar a percorrer a página em vez
                                                // de voltar sempre ao topo após cada tentativa.
                                                web.evaluateJavascript(NEXUS_PREPARE_LAZY_IMAGES_SCRIPT, null)
                                                web.postDelayed({ extractDomWhenReady() }, 1000L)
                                                return@evaluateJavascript
                                            }

                                            val oldPage = page
                                            val mergedEntryMap = linkedMapOf<String, NexusEntry>()
                                            accumulatedCatalogEntries.forEach { mergedEntryMap[nexusCatalogEntryKey(it.url)] = it }
                                            oldPage?.entries.orEmpty().forEach { mergedEntryMap[nexusCatalogEntryKey(it.url)] = it }
                                            domEntries.forEach { fresh ->
                                                val key = nexusCatalogEntryKey(fresh.url)
                                                val titleKey = nexusCatalogTitleKey(fresh.title)
                                                val slugKey = nexusCatalogSlugKey(fresh.url)
                                                val matchingKey = key.takeIf { mergedEntryMap.containsKey(it) }
                                                    ?: mergedEntryMap.entries.firstOrNull { (_, existing) ->
                                                        slugKey.isNotBlank() && slugKey == nexusCatalogSlugKey(existing.url)
                                                    }?.key
                                                    ?: mergedEntryMap.entries.firstOrNull { (_, existing) ->
                                                        titleKey.length >= 4 && titleKey == nexusCatalogTitleKey(existing.title)
                                                    }?.key
                                                val old = matchingKey?.let { mergedEntryMap[it] }
                                                if (old == null) mergedEntryMap[key] = fresh
                                                else mergedEntryMap[matchingKey!!] = mergeNexusCover(old, fresh)
                                            }
                                            val combinedEntries = mergedEntryMap.values.toList().take(5000)
                                            accumulatedCatalogEntries = combinedEntries
                                            if (appendingCatalogPage) {
                                                hasMoreCatalogPages = combinedEntries.size > catalogEntryCountBeforeAppend
                                                appendingCatalogPage = false
                                            }
                                            val combinedChapters = (oldPage?.chapters.orEmpty() + extractedChapters)
                                                .distinctBy { it.url }.take(600)
                                            val combinedImages = (extractedImages + oldPage?.readerImages.orEmpty()).distinct().take(150)
                                            val nextUrl = nextCatalogPageUrl
                                                ?: NexusToonsAdapter.guessedNextPageUrl(currentUrl)
                                                ?: data.optString("nextPageUrl", "")
                                                    .takeIf { it.startsWith("https://") && NexusToonsAdapter.supports(it) }
                                            nextCatalogPageUrl = nextUrl
                                            hasMoreCatalogPages = nextUrl != null
                                            page = NexusPage(
                                                title = data.optString("title", oldPage?.title ?: "Nexus Toons").ifBlank { "Nexus Toons" },
                                                url = oldPage?.url ?: currentUrl,
                                                entries = combinedEntries,
                                                readerImages = combinedImages,
                                                chapters = combinedChapters,
                                                genres = (oldPage?.genres.orEmpty() + extractedGenres).distinctBy { it.url }.take(40),
                                                nextPageUrl = nextUrl ?: oldPage?.nextPageUrl,
                                                coverUrl = data.optString("coverUrl", "").takeIf { it.startsWith("https://") } ?: oldPage?.coverUrl,
                                                synopsis = data.optString("synopsis", "").ifBlank { oldPage?.synopsis.orEmpty() },
                                                author = data.optString("author", "").ifBlank { oldPage?.author.orEmpty() },
                                                status = data.optString("status", "").ifBlank { oldPage?.status.orEmpty() },
                                                kind = data.optString("kind", "").ifBlank { oldPage?.kind.orEmpty() },
                                                rating = data.optString("rating", "").ifBlank { oldPage?.rating.orEmpty() },
                                                chapterCount = data.optString("chapterCount", "").ifBlank { oldPage?.chapterCount.orEmpty() }
                                            )
                                            // A 404 page can contain a logo or an X image.
                                            // Never interpret those assets as chapter pages unless the user
                                            // explicitly opened a reader URL.
                                            if (combinedImages.isNotEmpty() && NexusToonsAdapter.isReaderUrl(currentUrl)) {
                                                readerImages = combinedImages
                                                if (readerTitle.isBlank()) {
                                                    readerTitle = data.optString("title", oldPage?.title ?: "Capítulo").ifBlank { "Capítulo" }
                                                }
                                                viewMode = "reader"
                                            }
                                            error = ""
                                            domFallbackLoading = false
                                            val finalCoverCount = combinedEntries.count { !it.imageUrl.isNullOrBlank() }
                                            val finalCoverCoveragePoor = combinedEntries.size >= 8 &&
                                                finalCoverCount < (combinedEntries.size * 0.65).toInt()
                                            needsDomFallback = when (viewMode) {
                                                "reader" -> combinedImages.isEmpty()
                                                "detail" -> combinedChapters.isEmpty()
                                                else -> combinedEntries.isEmpty() ||
                                                    (finalCoverCoveragePoor && extractionAttempts < 20)
                                            }
                                            domFallbackMessage = when {
                                                needsDomFallback -> data.optString("debug", "Não foram encontrados elementos suficientes.")
                                                viewMode == "catalog" && finalCoverCoveragePoor ->
                                                    data.optString("debug", "DIAGNÓSTICO CAPAS: extração incompleta.")
                                                else -> ""
                                            }
                                        }
                                    }
                                    web.postDelayed({ extractDomWhenReady() }, 1200L)
                                }
                            }
                            loadUrl(currentUrl)
                        }
                    },
                    update = { view ->
                        view.visibility = View.INVISIBLE
                        val taggedUrl = view.tag as? String
                        if (taggedUrl != currentUrl) {
                            view.tag = currentUrl
                            view.loadUrl(currentUrl)
                        }
                    }
                )
            }
        }
    }
}


private const val NEXUS_PREPARE_LAZY_IMAGES_SCRIPT = """
(function() {
  const bad = /(logo|avatar|favicon|emoji|sprite|mascot|placeholder|profile|banner-ad|blank\.(gif|png|jpg)|transparent\.(gif|png))/i;
  const attrs = ['data-src','data-lazy-src','data-original','data-lazyload','data-image','data-cfsrc','data-echo','data-url','data-image-src','data-original-src','data-lazy','data-flickity-lazyload'];
  const resolve = raw => {
    if (!raw) return '';
    const part = String(raw).split(',')[0].trim().split(/\s+/)[0];
    if (!part || part.startsWith('data:image') || /^(about:blank|javascript:|#)$/i.test(part)) return '';
    try {
      const url = new URL(part, location.href);
      return url.protocol === 'https:' && !bad.test(url.href) ? url.href : '';
    } catch(e) { return ''; }
  };
  let changed = 0;
  const imgs = Array.from(document.querySelectorAll('img'));
  for (const img of imgs) {
    img.loading = 'eager';
    const current = img.getAttribute('src') || '';
    const currentBad = !current || current.startsWith('data:image') || bad.test(current);
    let real = '';
    for (const attr of attrs) {
      real = resolve(img.getAttribute(attr));
      if (real) break;
    }
    if (real && currentBad) {
      img.setAttribute('src', real);
      changed++;
    }
    const lazySet = img.getAttribute('data-srcset') || img.getAttribute('data-lazy-srcset');
    if (lazySet) {
      img.setAttribute('srcset', lazySet);
      if (!real && currentBad) {
        const first = resolve(lazySet);
        if (first) { img.setAttribute('src', first); changed++; }
      }
    }
  }
  const maxY = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement.scrollHeight);
  const currentY = window.scrollY || 0;
  const nextY = Math.min(maxY, currentY + Math.max(window.innerHeight || 600, 600));
  // Não regressar ao topo: isso voltava a acionar sempre só as imagens visíveis
  // no início da grelha e deixava quase todas as capas por extrair.
  window.scrollTo(0, nextY);
  return JSON.stringify({images: imgs.length, changed: changed, scrollY: nextY, scrollHeight: maxY});
})();
""";

private const val NEXUS_DOM_EXTRACTION_SCRIPT = """
(function() {
  const rootHost = String(location.hostname || '').toLowerCase().replace(/^www\./, '');
  const hostAllowed = host => {
    const h = String(host || '').toLowerCase().replace(/^www\./, '');
    return h === 'nexustoons.com' || h.endsWith('.nexustoons.com');
  };
  const clean = value => String(value || '').replace(/\s+/g, ' ').trim();
  const generic = /^(ver detalhes|detalhes|ler agora|ler|abrir|ver mais|saiba mais|continuar lendo|read more|view more|view details|home|in[ií]cio|menu|pesquisar|buscar)$/i;
  const banned = new Set(['categoria','categorias','category','categories','genero','generos','genre','genres','tag','tags','search','pesquisa','pesquisar','busca','login','logout','register','registrar','user','users','perfil','profile','conta','account','sobre','contato','contact','politica','termos','ranking','notificacoes','notifications','loja','biblioteca','niveis','conquistas','comunidade','comunidades','cart']);
  const currentPath = (location.pathname || '/').replace(/\/$/, '').toLowerCase() || '/';
  const catalogueRoutes = new Set(['/','/biblioteca','/loja','/catalogo','/explorar','/niveis','/níveis','/conquistas','/comunidade','/comunidades']);
  const isCatalogue = catalogueRoutes.has(currentPath) ||
    /^\/(genero|generos|genre|genres|categoria|categorias|tag)\//.test(currentPath) ||
    /\/(page|pagina)\/\d+$/.test(currentPath) ||
    /(?:^|&)(?:page|paged)=\d+/i.test(location.search.slice(1));
  const isReader = /^\/r\//.test(location.pathname) ||
    /\/(capitulo|chapter|leitura|reader|read|episode|episodio)\//i.test(location.pathname) ||
    /cap[ií]tulo|chapter|leitura|reader|epis[oó]dio/i.test(location.pathname);
  const sameSite = url => url && url.protocol === 'https:' && hostAllowed(url.hostname);
  const urlOf = anchor => {
    try { return new URL(anchor.href || anchor.getAttribute('href') || '', location.href); }
    catch(e) { return null; }
  };
  const validLink = anchor => {
    if (!anchor) return null;
    const url = urlOf(anchor);
    if (!sameSite(url)) return null;
    const path = (url.pathname || '/').replace(/\/$/, '').toLowerCase() || '/';
    if (path === currentPath || url.href.split('#')[0] === location.href.split('#')[0]) return null;
    return url;
  };
  const imageUrl = img => {
    if (!img) return '';
    const classes = ((img.className || '') + ' ' + (img.id || '') + ' ' + (img.getAttribute('alt') || '')).toLowerCase();
    if (/(avatar|profile|user-icon|emoji|favicon|sprite|mascot|placeholder)/i.test(classes)) return '';
    const candidates = [
      img.getAttribute('data-src'), img.getAttribute('data-lazy-src'),
      img.getAttribute('data-original'), img.getAttribute('data-lazyload'),
      img.getAttribute('data-image'), img.getAttribute('data-cfsrc'),
      img.getAttribute('data-echo'), img.getAttribute('data-url'),
      img.getAttribute('data-srcset'), img.getAttribute('data-lazy-srcset'),
      img.getAttribute('data-image-src'), img.getAttribute('data-original-src'),
      img.getAttribute('data-lazy'), img.getAttribute('data-flickity-lazyload'),
      img.getAttribute('srcset'), img.getAttribute('data-src'),
      img.getAttribute('src'), img.currentSrc
    ];
    for (const raw of candidates) {
      if (!raw) continue;
      const tokens = String(raw).split(',').map(part => clean(part).split(/\s+/)[0]).filter(Boolean);
      for (const candidate of tokens) {
        if (!candidate || candidate.startsWith('data:image') || candidate === '#' || candidate.startsWith('javascript:')) continue;
        try {
          const resolved = new URL(candidate, location.href);
          if (resolved.protocol !== 'https:') continue;
          if (/(logo|avatar|favicon|emoji|sprite|mascot|placeholder|profile|banner-ad|blank\.(gif|png|jpg))/i.test(resolved.href)) continue;
          return resolved.href;
        } catch(e) {}
      }
    }
    return '';
  };
  const cardFor = anchor => {
    let node = anchor;
    let backgroundFallback = null;
    for (let depth = 0; depth < 9 && node; depth++, node = node.parentElement) {
      const descendantLinks = node.querySelectorAll ? node.querySelectorAll('a[href]').length : 0;
      const workLinks = descendantLinks + (node.matches && node.matches('a[href]') ? 1 : 0);
      const imageSelector = 'img, picture source, [data-src], [data-lazy-src], [data-original], [data-lazy-srcset], [data-srcset], [data-image], [data-cover], [data-thumb], [data-poster], [data-image-url], [data-thumbnail], [data-lazy-image], [data-background], [data-bg], [data-lazy-background], [style*="background-image"]';
      const images = node.querySelectorAll ? node.querySelectorAll(imageSelector).length : 0;
      const selfHasArtwork = node.matches && node.matches(imageSelector);
      const backgrounds = node.querySelectorAll ? node.querySelectorAll('[style*="background"],[data-background],[data-bg],[data-lazy-background]').length : 0;
      const bgCandidates = node.querySelectorAll ? Array.from(node.querySelectorAll('[class*="cover"],[class*="thumb"],[class*="poster"],[class*="image"],[data-background],[data-bg],[data-lazy-background]')).slice(0, 12) : [];
      let computedArtwork = false;
      try {
        const ownBackground = window.getComputedStyle(node).backgroundImage || '';
        computedArtwork = ownBackground !== 'none' && /url\(/i.test(ownBackground);
      } catch(e) {}
      if (!computedArtwork) {
        for (const candidate of bgCandidates) {
          try {
            const background = window.getComputedStyle(candidate).backgroundImage || '';
            if (background !== 'none' && /url\(/i.test(background)) { computedArtwork = true; break; }
          } catch(e) {}
        }
      }
      const hasArtwork = images > 0 || selfHasArtwork || backgrounds > 0 || computedArtwork;
      // Usar o cartão ancestral mais próximo que contenha a ligação e uma imagem,
      // mesmo que o tema não use classes com "manga", "cover" ou "card".
      if (hasArtwork && workLinks >= 1 && workLinks <= 8) {
        if (images > 0 || selfHasArtwork) return node;
        backgroundFallback = backgroundFallback || node;
      }
    }
    return backgroundFallback || anchor.closest('article,li,.swiper-slide,.page-item-detail,.c-tabs-item__content,.bsx,.utao,.manga-item,.manga-card,.series-item,.book-item,.item,[class*="card"]');
  };
  const chooseCover = (anchor, card) => {
    const candidates = [];
    const addCandidate = (raw, score, label) => {
      if (!raw) return;
      const parts = String(raw).split(',').map(part => clean(part).split(/\s+/)[0]).filter(Boolean);
      for (const part of parts) {
        if (!part || part.startsWith('data:image') || /^(about:blank|javascript:|#)$/i.test(part)) continue;
        try {
          const url = new URL(part, location.href);
          // Alguns cartões publicam imagens com URL http://; o Chrome atualiza
          // esses pedidos para HTTPS em páginas seguras. Fazemos o mesmo aqui.
          if (url.protocol === 'http:') url.protocol = 'https:';
          if (url.protocol !== 'https:') continue;
          if (/(logo|avatar|favicon|emoji|sprite|mascot|placeholder|profile|banner-ad|blank\.(gif|png|jpg)|transparent\.(gif|png))/i.test(url.href)) continue;
          candidates.push({url:url.href, score:score, label:label || ''});
        } catch(e) {}
      }
    };
    const roots = [];
    if (anchor) roots.push(anchor);
    if (card && card !== anchor) roots.push(card);
    const allImgs = [];
    for (const root of roots) {
      if (root.matches && root.matches('img')) allImgs.push(root);
      if (root.querySelectorAll) allImgs.push(...Array.from(root.querySelectorAll('img')));
    }
    for (const img of Array.from(new Set(allImgs))) {
      const classes = ((img.className || '') + ' ' + (img.id || '')).toLowerCase();
      const alt = clean(img.getAttribute('alt'));
      let score = 15;
      if (/(cover|thumb|thumbnail|poster|manga|manhwa|manhua|book|wp-post-image|attachment|image)/.test(classes)) score += 35;
      if (alt.length > 2 && !generic.test(alt)) score += 10;
      if (Number(img.getAttribute('width') || img.naturalWidth || 0) >= 100) score += 8;
      if (Number(img.getAttribute('height') || img.naturalHeight || 0) >= 150) score += 8;
      if (img.closest('a') && anchor && img.closest('a') === anchor) score += 10;
      for (const attr of ['data-cover','data-thumb','data-poster','data-image-url','data-thumbnail','data-lazy-image','data-original-image','data-src','data-lazy-src','data-original','data-lazyload','data-image','data-cfsrc','data-echo','data-url','data-image-src','data-original-src','data-lazy','data-flickity-lazyload','data-srcset','data-lazy-srcset','srcset','src']) {
        const raw = img.getAttribute(attr);
        addCandidate(raw, score + (attr.startsWith('data-') ? 12 : 0), attr);
      }
      addCandidate(img.currentSrc, score + 6, 'currentSrc');
    }

    // Não limitar a extração a classes CSS conhecidas: alguns cartões definem a
    // capa através de data-cover/data-src ou de background em folhas de estilo.
    const dataAttrs = ['data-cover','data-thumb','data-poster','data-image-url','data-thumbnail','data-lazy-image','data-original-image','data-background','data-bg','data-lazy-background','data-bg-src'];
    const lazyAttrs = ['data-src','data-lazy-src','data-original','data-lazyload','data-image','data-cfsrc','data-echo','data-url','data-image-src','data-original-src','data-lazy','data-flickity-lazyload'];
    const visited = new Set();
    for (const root of roots) {
      if (!root || !root.querySelectorAll) continue;
      const nodes = [root, ...Array.from(root.querySelectorAll('*')).slice(0, 180)];
      for (const node of nodes) {
        if (visited.has(node)) continue;
        visited.add(node);
        const classes = ((node.className && String(node.className)) || '') + ' ' + (node.id || '');
        const lowerClasses = classes.toLowerCase();
        const looksLikeArtwork = /(cover|thumb|thumbnail|poster|manga|manhwa|manhua|book|image|media|artwork|capa)/i.test(lowerClasses);
        for (const attr of dataAttrs) {
          const raw = node.getAttribute && node.getAttribute(attr);
          if (raw) addCandidate(raw, 48, attr);
        }
        if (looksLikeArtwork) {
          for (const attr of lazyAttrs) {
            const raw = node.getAttribute && node.getAttribute(attr);
            if (raw) addCandidate(raw, 32, attr);
          }
        }
        if (node.matches && node.matches('picture source')) {
          addCandidate(node.getAttribute('srcset'), 36, 'picture srcset');
          addCandidate(node.getAttribute('data-srcset'), 48, 'picture data-srcset');
          addCandidate(node.getAttribute('data-src'), 48, 'picture data-src');
        }
        let background = '';
        try { background = window.getComputedStyle(node).backgroundImage || ''; } catch(e) {}
        const bgMatch = /url\((['"]?)(.*?)['"]?\)/i.exec(background);
        if (bgMatch) addCandidate(bgMatch[2], looksLikeArtwork ? 60 : 22, 'computed background');
        for (const pseudo of ['::before','::after']) {
          try {
            const pseudoBackground = window.getComputedStyle(node, pseudo).backgroundImage || '';
            const pseudoMatch = /url\((['"]?)(.*?)['"]?\)/i.exec(pseudoBackground);
            if (pseudoMatch) addCandidate(pseudoMatch[2], looksLikeArtwork ? 55 : 18, 'pseudo background');
          } catch(e) {}
        }
      }
    }
    const unique = new Map();
    for (const item of candidates) {
      const old = unique.get(item.url);
      if (!old || item.score > old.score) unique.set(item.url, item);
    }
    const ranked = Array.from(unique.values()).sort((a,b) => b.score-a.score);
    return ranked.length ? ranked[0].url : '';
  };
  const meaningful = value => {
    const text = clean(value);
    return text.length >= 2 && text.length <= 180 && !generic.test(text);
  };
  const entries = [];
  const entryByUrl = new Map();
  const workAnchors = Array.from(document.querySelectorAll('a[href]')).filter(anchor => {
    if (anchor.closest('nav,header,footer,[role="navigation"]')) return false;
    const url = validLink(anchor);
    if (!url) return false;
    const path = url.pathname.toLowerCase();
    const firstSegment = path.split('/').filter(Boolean)[0] || '';
    const card = cardFor(anchor);
    const hasCardImage = !!(card && card.querySelector('img, [style*="background-image"], [data-cover], [data-thumb], [data-poster], [data-image-url], [data-thumbnail], [data-lazy-image], [data-background], [data-bg]'));
    const hasDescriptiveText = meaningful(anchor.innerText || anchor.textContent || anchor.getAttribute('title') || anchor.getAttribute('aria-label'));
    return /\/(manga|obra|series|comic|manhwa|manhua)\//.test(path) ||
      (isCatalogue && !banned.has(firstSegment) && (anchor.querySelector('img') || hasCardImage) && hasDescriptiveText);
  });
  for (const anchor of workAnchors) {
    const url = validLink(anchor);
    if (!url) continue;
    const path = url.pathname.toLowerCase();
    if (isReader && !/\/manga\//.test(path)) continue;
    if (/^\/(r|capitulo|chapter|leitura|reader|read|episode|episodio)\//i.test(path)) continue;
    if (banned.has(path.split('/').filter(Boolean)[0] || '')) continue;
    const key = url.href.split('#')[0];
    if (entryByUrl.has(key)) continue;
    const card = cardFor(anchor);
    const heading = card && card.querySelector('h1,h2,h3,h4,h5,h6,[class*="manga-title"],[class*="book-title"],[class*="series-title"],[class*="title"],[class*="titulo"],[class*="name"]');
    const img = card && card.querySelector('img');
    const ancestorTitle = anchor.closest('article,li,.swiper-slide,[class*="item"],[class*="card"],[class*="manga"],[class*="series"],[class*="book"],[class*="post"]');
    const ancestorHeading = ancestorTitle && ancestorTitle.querySelector('h1,h2,h3,h4,h5,h6,[class*="title"],[class*="name"]');
    const title = [
      heading && (heading.innerText || heading.textContent),
      ancestorHeading && (ancestorHeading.innerText || ancestorHeading.textContent),
      anchor.getAttribute('title'),
      anchor.getAttribute('aria-label'),
      img && img.getAttribute('alt'),
      anchor.innerText || anchor.textContent,
      ancestorTitle && (ancestorTitle.innerText || ancestorTitle.textContent)
    ].map(clean).find(meaningful) || '';
    if (!meaningful(title)) continue;
    const cover = chooseCover(anchor, card);
    const cardText = clean(card && (card.innerText || card.textContent));
    const descNode = card && card.querySelector('p,[class*="description"],[class*="summary"],[class*="synopsis"]');
    const description = clean(descNode && (descNode.innerText || descNode.textContent)).slice(0,500);
    const kind = ((/\b(MANHWA|MANHUA|MANGA|NOVEL)\b/i.exec(cardText) || [,''])[1] || '').toUpperCase();
    const status = ((/\b(Em andamento|Hiato|Concluído|Concluido|Completo)\b/i.exec(cardText) || [,''])[1] || '');
    const rating = ((/(?:^|\s)([0-5]\.\d)(?:\s|$)/.exec(cardText) || [,''])[1] || '');
    const chapterCount = ((/Cap\.?\s*(\d+)|(\d+)\s*caps?/i.exec(cardText) || []).slice(1).find(Boolean) || '');
    const genres = Array.from(card ? card.querySelectorAll('a[href]') : [])
      .filter(a => /\/(genero|generos|genre|genres|categoria|categorias|tag)\//i.test((urlOf(a) || {}).pathname || ''))
      .map(a => clean(a.innerText || a.textContent)).filter(Boolean);
    const item = {title:title,url:key,image:cover,description:description,kind:kind,status:status,rating:rating,chapterCount:chapterCount,genres:genres,source:'work-card'};
    entryByUrl.set(key,item);
    entries.push(item);
  }

  const chapters = [];
  if (!isCatalogue && !isReader) {
    const seenChapters = new Set();
    for (const anchor of Array.from(document.querySelectorAll('a[href]'))) {
      if (anchor.closest('nav,header,footer,[role="navigation"]')) continue;
      const url = validLink(anchor);
      if (!url) continue;
      const path = url.pathname.toLowerCase();
      const label = clean(anchor.innerText || anchor.textContent || anchor.getAttribute('title') || anchor.getAttribute('aria-label'));
      const chapterRoute = /^\/r\//.test(path) || /\/(capitulo|chapter|leitura|reader|read|episode|episodio)\//.test(path) ||
        /^(cap\.?|cap[ií]tulo|chapter|ep[ií]s[oó]dio|episode|p[aá]gina)\s*\d+/i.test(label);
      if (!chapterRoute || !label || label.length > 220) continue;
      const key = url.href.split('#')[0];
      if (seenChapters.has(key)) continue;
      seenChapters.add(key);
      chapters.push({title:label,url:key,image:'',description:'',kind:'',status:'',rating:'',chapterCount:'',genres:[]});
    }
  }

  const genres = [];
  const genreSeen = new Set();
  for (const anchor of Array.from(document.querySelectorAll('a[href]'))) {
    const url = urlOf(anchor);
    if (!sameSite(url)) continue;
    const path = url.pathname.toLowerCase();
    if (!/\/(genero|generos|genre|genres|categoria|categorias|tag)\//.test(path)) continue;
    const title = clean(anchor.innerText || anchor.textContent || anchor.getAttribute('title'));
    if (!title || title.length > 40 || genreSeen.has(url.href)) continue;
    genreSeen.add(url.href);
    genres.push({title:title,url:url.href});
    if (genres.length >= 40) break;
  }

  const nextAnchor = document.querySelector('a[rel="next"],.pagination a.next,.nav-links a.next,a.page-numbers.next,a[aria-label*="Next"],a[aria-label*="Próxima"],.load-more a,a.load-more');
  const nextPageUrl = nextAnchor && urlOf(nextAnchor) && sameSite(urlOf(nextAnchor)) ? urlOf(nextAnchor).href : '';
  const readerImages = [];
  if (isReader) {
    const opaqueReader = /^\/r\//.test(location.pathname);
    const selectors = '.reading-content img,.page-break img,.wp-manga-chapter-img,.chapter-content img,.reader-area img,.chapter-images img,#readerarea img,.container-chapter-reader img,#chapter_imgs img,.reading-detail img,.reader img,.comic-page img,.ts-main-image,[class*="reader"] img,[class*="chapter"] img,[class*="page"] img,img';
    for (const img of Array.from(document.querySelectorAll(selectors))) {
      if (opaqueReader && img.closest('header,nav,footer,aside,[role="navigation"]')) continue;
      const url = imageUrl(img);
      if (!url) continue;
      const classes = ((img.className || '') + ' ' + (img.id || '')).toLowerCase();
      const width = Number(img.naturalWidth || img.getAttribute('width') || 0);
      const height = Number(img.naturalHeight || img.getAttribute('height') || 0);
      if (/(logo|avatar|icon|emoji|mascot|sprite|placeholder|banner)/i.test(classes + ' ' + url)) continue;
      if (opaqueReader || /(chapter|page|reading|reader|comic|scan|manga-page)/.test(classes) || width >= 260 || height >= 360 || /\/uploads\//i.test(url)) {
        if (!readerImages.includes(url)) readerImages.push(url);
      }
      if (readerImages.length >= 150) break;
    }
  }

  const bodyText = clean(document.body && document.body.innerText);
  const metaImage = document.querySelector('meta[property="og:image"]');
  const coverUrl = metaImage && /^https:\/\//i.test(metaImage.content) ? metaImage.content : '';
  const synopsisNode = document.querySelector('.manga-summary,.summary__content,.description-summary,.entry-content,.manga-description,[class*="synopsis"],[class*="description"]');
  const synopsis = clean(synopsisNode && (synopsisNode.innerText || synopsisNode.textContent)).slice(0,5000);
  const authorMatch = /Autor\s*:?\s*([^\n]{2,100})/i.exec(bodyText);
  const status = ((/\b(Em andamento|Hiato|Concluído|Concluido|Completo)\b/i.exec(bodyText) || [,''])[1] || '');
  const kind = ((/\b(Manhwa|Manhua|Manga|Novel)\b/i.exec(bodyText) || [,''])[1] || '');
  const rating = ((/(?:^|\s)([0-5]\.\d)(?:\s|$)/.exec(bodyText) || [,''])[1] || '');
  const chapterCount = ((/Capítulos?\s*\((\d+)\)|(\d+)\s*caps?/i.exec(bodyText) || []).slice(1).find(Boolean) || '');
  const titleNode = document.querySelector('h1');
  const title = clean(titleNode && (titleNode.innerText || titleNode.textContent)) || clean(document.title) || 'Nexus Toons';

  return JSON.stringify({
    title:title,
    entries:isCatalogue ? entries.slice(0,500) : [],
    chapters:chapters.slice(0,600),
    genres:genres,
    readerImages:readerImages,
    nextPageUrl:nextPageUrl,
    coverUrl:coverUrl,
    synopsis:synopsis,
    author:authorMatch ? clean(authorMatch[1]) : '',
    status:status,
    kind:kind,
    rating:rating,
    chapterCount:chapterCount,
    debug:'DIAGNÓSTICO CAPAS: ' + entries.filter(item => item.image).length + '/' + entries.length + ' capas extraídas; ' + document.querySelectorAll('img').length + ' elementos img; ' + document.querySelectorAll('img[data-src],img[data-lazy-src],img[data-original],img[data-srcset],img[data-image],img[data-cfsrc],img[data-lazy]').length + ' imagens com atributos lazy; ' + document.querySelectorAll('[data-cover],[data-thumb],[data-poster],[data-image-url],[data-thumbnail],[data-background],[data-bg]').length + ' elementos com atributos de capa; ' + workAnchors.length + ' candidatos e ' + document.querySelectorAll('a[href]').length + ' links.'
  });
})();
"""
private fun buildReaderHtml(title: String, images: List<String>): String {
    val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val body = images.joinToString("\n") { url ->
        val safe = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        "<img src=\"$safe\" alt=\"Página\" />"
    }
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0;background:#101014;color:#fff;font-family:sans-serif}h2{padding:12px;font-size:18px}img{display:block;width:100%;height:auto;margin:0 auto 8px}</style>
</head><body><h2>$safeTitle</h2>$body</body></html>"""
}
