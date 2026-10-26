package com.blackmanga.app

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
internal fun NexusToonsScreen(startUrl: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var currentUrl by remember(startUrl) { mutableStateOf(NexusToonsAdapter.normalizeCatalogueStart(startUrl)) }
    var history by remember(startUrl) { mutableStateOf(emptyList<String>()) }
    var page by remember { mutableStateOf<NexusPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var readerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerTitle by remember { mutableStateOf("") }
    var needsDomFallback by remember { mutableStateOf(false) }
    var domFallbackLoading by remember { mutableStateOf(false) }
    var domFallbackMessage by remember { mutableStateOf("") }

    fun load(url: String, saveHistory: Boolean = true) {
        val destination = NexusToonsAdapter.normalizeCatalogueStart(url)
        if (!NexusToonsAdapter.supports(destination)) {
            error = "Este endereço não pertence ao Nexus Toons."
            return
        }
        if (saveHistory && currentUrl != destination) history = history + currentUrl
        scope.launch {
            loading = true
            error = ""
            filter = ""
            page = null
            readerImages = emptyList()
            readerTitle = ""
            needsDomFallback = false
            domFallbackLoading = false
            domFallbackMessage = ""
            currentUrl = destination
            try {
                val directPage: NexusPage? = try {
                    NexusToonsAdapter.load(destination)
                } catch (_: Exception) {
                    null
                }
                val shouldTryUniversal = directPage == null ||
                    (directPage.entries.isEmpty() && directPage.readerImages.isEmpty())
                val universalPage: UniversalSitePage? = if (shouldTryUniversal) {
                    try {
                        withContext(Dispatchers.IO) { UniversalSiteAdapter.load(destination) }
                    } catch (_: Exception) {
                        null
                    }
                } else null

                if (directPage == null && universalPage == null) {
                    throw IllegalStateException("Não foi possível carregar o catálogo Nexus Toons. Verifica a ligação ou tenta abrir o site diretamente.")
                }

                val mergedEntries = (
                    directPage?.entries.orEmpty() +
                        universalPage?.entries.orEmpty().map { entry ->
                            NexusEntry(entry.title, entry.url, entry.imageUrl, entry.description)
                        }
                    ).distinctBy { it.url }.take(300)
                val mergedImages = (directPage?.readerImages.orEmpty() + universalPage?.readerImages.orEmpty())
                    .distinct().take(120)
                val result = NexusPage(
                    title = directPage?.title?.takeIf { it.isNotBlank() }
                        ?: universalPage?.title?.takeIf { it.isNotBlank() }
                        ?: "Nexus Toons",
                    url = directPage?.url ?: universalPage?.url ?: destination,
                    entries = mergedEntries,
                    readerImages = mergedImages
                )
                page = result
                if (result.readerImages.isNotEmpty()) {
                    readerImages = result.readerImages
                    readerTitle = result.title
                }
                if (result.entries.isEmpty() && result.readerImages.isEmpty()) {
                    // Keep the visible WebView as a fallback rather than leaving a blank screen.
                    needsDomFallback = true
                    domFallbackLoading = true
                }
            } catch (e: Exception) {
                // If both HTML adapters fail, still render the actual site instead of leaving a blank catalogue.
                error = e.localizedMessage ?: "O leitor direto não conseguiu extrair os títulos."
                page = NexusPage(
                    title = "Nexus Toons",
                    url = destination,
                    entries = emptyList(),
                    readerImages = emptyList()
                )
                needsDomFallback = true
                domFallbackLoading = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(startUrl) { load(startUrl, false) }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (readerImages.isNotEmpty()) {
                    readerImages = emptyList()
                } else if (history.isNotEmpty()) {
                    val previous = history.last()
                    history = history.dropLast(1)
                    load(previous, false)
                } else {
                    onBack()
                }
            }) { Text("Voltar") }
            Column(Modifier.weight(1f)) {
                Text("Nexus Toons · Catálogo", color = Color(0xFFB7A0FF))
                Text(currentUrl, color = Color(0xFFB8B5C5), fontSize = 10.sp, maxLines = 1)
            }
        }
        if (loading || domFallbackLoading) CircularProgressIndicator()
        if (error.isNotBlank() && !domFallbackLoading) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF402126))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Não foi possível extrair os títulos diretamente", color = Color.White)
                    Text(error, color = Color.White, fontSize = 12.sp)
                    Button(onClick = { load(currentUrl) }) { Text("Tentar novamente") }
                }
            }
        }
        page?.takeIf { it.entries.isNotEmpty() || readerImages.isNotEmpty() || !needsDomFallback }?.let { loaded ->
            if (readerImages.isNotEmpty()) {
                Text(readerTitle, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("${readerImages.size} imagens de página encontradas", color = Color(0xFFB8B5C5))
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(
                                "https://nexustoons.com/",
                                buildReaderHtml(readerTitle, readerImages),
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    update = { view ->
                        view.loadDataWithBaseURL(
                            "https://nexustoons.com/",
                            buildReaderHtml(readerTitle, readerImages),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                )
                Button(onClick = { readerImages = emptyList() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Voltar ao catálogo")
                }
            } else {
                Text(loaded.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pesquisar títulos") },
                    singleLine = true
                )
                Text("${loaded.entries.size} títulos/capítulos encontrados", color = Color(0xFFB8B5C5), fontSize = 12.sp)
                val visibleEntries = loaded.entries.filter { it.title.contains(filter.trim(), true) }
                if (visibleEntries.isEmpty()) {
                    Text(
                        if (loaded.entries.isEmpty()) {
                            if (domFallbackLoading) "A procurar títulos no conteúdo apresentado pelo site…"
                            else domFallbackMessage.ifBlank { "Não foram encontrados mangás nesta página. Usa a página inicial do catálogo ou tenta atualizar." }
                        } else {
                            "Nenhum título corresponde a «${filter.trim()}»."
                        },
                        color = Color(0xFFB8B5C5),
                        fontSize = 13.sp
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 78.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(visibleEntries, key = { it.url }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { load(entry.url) },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                        ) {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (!entry.imageUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = entry.imageUrl,
                                        contentDescription = entry.title,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(0.68f)
                                            .background(Color(0xFF302A3B), RoundedCornerShape(6.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("NEXUS", color = Color(0xFFB7A0FF), fontSize = 9.sp)
                                    }
                                }
                                Text(
                                    entry.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
                Text(
                    "Os títulos são extraídos do conteúdo público do site. A disponibilidade depende da estrutura que o Nexus Toons apresenta.",
                    color = Color(0xFFB8B5C5),
                    fontSize = 11.sp
                )
            }
        }

        if (needsDomFallback) {
            // Keep a hidden WebView only as a JavaScript DOM reader. The website itself is never
            // displayed inside the catalogue; successful extraction populates the native grid above.
            AndroidView(
                modifier = Modifier.size(1.dp),
                factory = { context ->
                    WebView(context).apply {
                        visibility = View.INVISIBLE
                        tag = currentUrl
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.blockNetworkImage = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Nexus Toons renders its catalogue with client-side JavaScript. Use a current
                        // Android Chrome user agent so the site does not serve an empty/unsupported view.
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        webViewClient = object : WebViewClient() {
                            @Suppress("DEPRECATION")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                domFallbackLoading = false
                                domFallbackMessage = "O navegador não conseguiu carregar esta página: " +
                                    (description ?: "erro de ligação") + ". Verifica a ligação ou tenta novamente."
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val web = view ?: return
                                val finishedUrl = url ?: return
                                if (!NexusToonsAdapter.supports(finishedUrl)) return
                                // Wait for the JavaScript catalogue to render before inspecting the DOM.
                                // The page can populate its catalogue asynchronously; retry DOM extraction before giving up.
                                var extractionAttempts = 0
                                fun extractDomWhenReady() {
                                    if (web.url != finishedUrl) return
                                    extractionAttempts += 1
                                    web.evaluateJavascript(NEXUS_DOM_EXTRACTION_SCRIPT) { raw ->
                                    val payload = try {
                                        JSONTokener(raw).nextValue() as? String
                                    } catch (_: Exception) {
                                        null
                                    }
                                    val data = try {
                                        payload?.let { JSONObject(it) }
                                    } catch (_: Exception) {
                                        null
                                    }
                                    if (data == null) {
                                        if (extractionAttempts < 6) {
                                            web.postDelayed({ extractDomWhenReady() }, 1200L)
                                        } else {
                                            domFallbackLoading = false
                                            needsDomFallback = false
                                            domFallbackMessage = "Não foi possível reconhecer os títulos automaticamente. Tenta novamente ou verifica a ligação."
                                        }
                                        return@evaluateJavascript
                                    }

                                    val entryArray = data.optJSONArray("entries") ?: JSONArray()
                                    val extracted = (0 until entryArray.length()).mapNotNull { index ->
                                        val item = entryArray.optJSONObject(index) ?: return@mapNotNull null
                                        val title = item.optString("title", "").trim()
                                        val entryUrl = item.optString("url", "").trim()
                                        if (title.isBlank() || entryUrl.isBlank() ||
                                            !NexusToonsAdapter.supports(entryUrl) ||
                                            !entryUrl.startsWith("https://")) return@mapNotNull null
                                        NexusEntry(
                                            title = title,
                                            url = entryUrl,
                                            imageUrl = item.optString("image", "").takeIf { it.startsWith("https://") },
                                            description = item.optString("description", "").take(300)
                                        )
                                    }.distinctBy { it.url }.take(300)

                                    val imageArray = data.optJSONArray("readerImages") ?: JSONArray()
                                    val extractedImages = (0 until imageArray.length()).mapNotNull { index ->
                                        imageArray.optString(index).takeIf {
                                            it.startsWith("https://") && !it.contains("data:image", true)
                                        }
                                    }.distinct().take(100)

                                    if (extracted.isEmpty() && extractedImages.isEmpty() && extractionAttempts < 6) {
                                        web.postDelayed({ extractDomWhenReady() }, 1200L)
                                        return@evaluateJavascript
                                    }

                                    val currentPage = page
                                    val fallbackTitle = data.optString("title", "Nexus Toons")
                                        .ifBlank { "Nexus Toons" }
                                    val combinedEntries = if (extracted.isNotEmpty()) extracted else currentPage?.entries.orEmpty()
                                    val combinedImages = if (extractedImages.isNotEmpty()) extractedImages
                                        else currentPage?.readerImages.orEmpty()
                                    page = NexusPage(
                                        title = fallbackTitle,
                                        url = currentPage?.url ?: currentUrl,
                                        entries = combinedEntries,
                                        readerImages = combinedImages
                                    )
                                    if (combinedImages.isNotEmpty()) {
                                        readerImages = combinedImages
                                        readerTitle = fallbackTitle
                                    }
                                    error = ""
                                    domFallbackLoading = false
                                    needsDomFallback = combinedEntries.isEmpty() && combinedImages.isEmpty()
                                    domFallbackMessage = if (combinedEntries.isEmpty() && combinedImages.isEmpty()) {
                                        data.optString("debug", "O catálogo não forneceu títulos reconhecíveis.") +
                                            " O catálogo nativo não encontrou obras nesta página; tenta novamente."
                                    } else ""
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
            Text(
                if (domFallbackLoading) {
                    "A preparar o catálogo interno e a procurar títulos, capas e capítulos…"
                } else {
                    "Não foi possível reconhecer títulos nesta página. O site original permanece oculto."
                },
                color = Color(0xFFB8B5C5),
                fontSize = 11.sp
            )
        }
        if (domFallbackMessage.isNotBlank() && page?.entries.isNullOrEmpty() && !domFallbackLoading) {
            Text(domFallbackMessage, color = Color(0xFFB8B5C5), fontSize = 12.sp)
            Button(onClick = { load(currentUrl) }) { Text("Tentar novamente") }
        }
    }
}

private const val NEXUS_DOM_EXTRACTION_SCRIPT = """
(function() {
  const hostAllowed = host => {
    const h = String(host || '').toLowerCase().replace(/^www\./, '');
    return h === 'nexustoons.com' || h.endsWith('.nexustoons.com');
  };
  const generic = /^(ver detalhes|detalhes|ler agora|ler|abrir|ver mais|saiba mais|continuar lendo|read more|view more|view details|home|in[ií]cio|menu|pesquisar|buscar)$/i;
  // Do not exclude /obras or /novels: a source can use these as valid title routes.
  const banned = new Set(['categoria','categorias','category','categories','genero','generos','genre','genres','tag','tags','search','pesquisa','pesquisar','busca','login','logout','register','registrar','u','user','users','perfil','profile','conta','account','sobre','contato','contact','politica','termos','ranking','notificacoes','notifications','loja','biblioteca','niveis','conquistas','comunidade','comunidades']);
  const catalogueRoutes = new Set(['/','/biblioteca','/loja','/catalogo','/explorar','/niveis','/níveis','/conquistas','/comunidade','/comunidades']);
  const currentPath = (location.pathname || '/').replace(/\/$/, '').toLowerCase() || '/';
  const entries = [];
  const seen = new Set();
  const tidy = s => String(s || '').replace(/\s+/g, ' ').trim();
  const urlOf = a => {
    try { return new URL(a.href || a.getAttribute('href') || '', location.href); }
    catch(e) { return null; }
  };
  const allowedLink = a => {
    if (a.closest('nav,header,footer,[role="navigation"]')) return false;
    const u = urlOf(a);
    if (!u || u.protocol !== 'https:' || !hostAllowed(u.hostname)) return false;
    const parts = u.pathname.split('/').filter(Boolean);
    if (!parts.length || banned.has(parts[0].toLowerCase())) return false;
    const targetPath = (u.pathname || '/').replace(/\/$/, '').toLowerCase() || '/';
    // Work routes may use /obra/, /series/, /manga/ or custom slugs. Exclude navigation
    // sections above, but don't require a particular first URL segment on the home page.
    // On a work-detail page, skip links to other works but keep chapter paths and /r/ routes.
    if (currentPath.startsWith('/manga/') && parts[0].toLowerCase() === 'manga' && parts.length <= 2) return false;
    if (targetPath === currentPath || u.href.split('#')[0] === location.href.split('#')[0]) return false;
    return true;
  };
  const imageOf = img => {
    if (!img) return '';
    const candidates = [
      img.getAttribute('data-src'), img.getAttribute('data-lazy-src'),
      img.getAttribute('data-original'), img.getAttribute('data-url'),
      img.getAttribute('data-lazyload'), img.currentSrc, img.src,
      (img.getAttribute('srcset') || '').split(',').pop().trim().split(/\s+/)[0]
    ];
    for (const candidate of candidates) {
      if (!candidate || candidate.startsWith('data:image')) continue;
      try {
        const u = new URL(candidate, location.href);
        if (u.protocol === 'https:') return u.href;
      } catch(e) {}
    }
    return '';
  };
  const add = (title, anchor, source, cardOverride) => {
    title = tidy(title);
    if (!anchor || !allowedLink(anchor) || title.length < 2 || title.length > 180 || generic.test(title)) return;
    const u = urlOf(anchor);
    if (!u) return;
    const key = u.href.split('#')[0];
    if (seen.has(key)) return;
    seen.add(key);
    let node = cardOverride || anchor;
    let image = imageOf(anchor.querySelector('img'));
    let description = '';
    for (let i = 0; i < 7 && node; i++, node = node.parentElement) {
      if (!image) image = imageOf(node.querySelector('img'));
      if (!description && node.querySelector('p,[class*=description],[class*=summary]')) {
        const p = node.querySelector('p,[class*=description],[class*=summary]');
        description = tidy(p.innerText || p.textContent);
      }
      if (i > 0 && node.querySelectorAll('h1,h2,h3,h4,h5,h6').length > 4) break;
    }
    entries.push({title:title, url:key, image:image, description:description.slice(0,300), source:source || 'link'});
  };
  const chapterLabel = /^(cap\.?|cap[ií]tulo|chapter|ep[ií]s[oó]dio|episode|p[aá]gina)\s*\d*/i;
  const cardSelector = [
    'article', 'li', '[class*=manga-card]', '[class*=obra-card]', '[class*=work-card]',
    '[class*=series-card]', '[class*=comic-card]', '[class*=manga-item]', '[class*=obra-item]',
    '[class*=series-item]', '[class*=book-item]', '[data-testid*=manga]', '[data-testid*=obra]'
  ].join(',');
  const cards = Array.from(document.querySelectorAll(cardSelector));
  for (const card of cards) {
    const heading = card.querySelector('h1,h2,h3,h4,h5,h6,[class*=title],[class*=titulo],[class*=name],[data-testid*=title]');
    const image = card.querySelector('img');
    const title = tidy(heading && (heading.innerText || heading.textContent)) ||
      tidy(image && image.getAttribute('alt')) ||
      tidy(card.querySelector('a[title]') && card.querySelector('a[title]').getAttribute('title'));
    if (title.length < 2 || title.length > 180 || generic.test(title)) continue;
    const links = Array.from(card.querySelectorAll('a[href]')).filter(allowedLink);
    if (!links.length) continue;
    const linkedHeading = heading && heading.closest('a[href]');
    const best = linkedHeading && allowedLink(linkedHeading) ? linkedHeading :
      (links.find(a => /^(ver detalhes|detalhes|view details)$/i.test(tidy(a.innerText || a.textContent))) ||
       links.find(a => !chapterLabel.test(tidy(a.innerText || a.textContent))) || links[0]);
    add(title, best, 'nexus-card', card);
  }

  const allAnchors = Array.from(document.querySelectorAll('a[href]'));
  const sameSiteAnchors = allAnchors.filter(allowedLink);
  for (const a of sameSiteAnchors) {
    let title = tidy(a.innerText || a.textContent || '');
    if (catalogueRoutes.has(currentPath) && chapterLabel.test(title)) continue;
    if (title.length > 180 || generic.test(title) || title.length < 2) {
      let node = a;
      for (let i = 0; i < 8 && node; i++, node = node.parentElement) {
        const headings = Array.from(node.querySelectorAll('h1,h2,h3,h4,h5,h6,[class*=title],[class*=titulo],[class*=name]'));
        const candidate = headings.map(h => tidy(h.innerText || h.textContent))
          .find(t => t.length >= 2 && t.length <= 180 && !generic.test(t));
        if (candidate) { title = candidate; break; }
        if (i >= 3 && node.querySelectorAll('a[href]').length > 15) break;
      }
    }
    if (title.length >= 2 && title.length <= 180 && !generic.test(title)) add(title, a, 'anchor');
  }

  const headingNodes = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6,[class*=manga-title],[class*=book-title],[class*=series-title],[class*=titulo],[class*=name]'));
  for (const h of headingNodes) {
    const title = tidy(h.innerText || h.textContent);
    if (title.length < 2 || title.length > 180 || generic.test(title)) continue;
    let best = null;
    const direct = h.closest('a[href]');
    if (direct && allowedLink(direct)) best = direct;
    let node = h;
    for (let depth = 0; !best && depth < 6 && node; depth++, node = node.parentElement) {
      const links = Array.from(node.querySelectorAll('a[href]')).filter(allowedLink);
      if (links.length) {
        best = links.find(a => /^(ver detalhes|detalhes|view details)$/i.test(tidy(a.innerText || a.textContent))) ||
          links.find(a => !chapterLabel.test(tidy(a.innerText || a.textContent))) || links[0];
      }
      if (depth >= 2 && node.querySelectorAll('h1,h2,h3,h4,h5,h6').length > 4) break;
    }
    if (best) add(title, best, 'heading', h.parentElement);
  }

  const path = (location.pathname + location.search).toLowerCase();
  const isReader = /^\/r\//.test(location.pathname) || /(cap[ií]tulo|capitulo|chapter|leitura|reader|\/ler(?:\/|$)|\/read(?:\/|$)|epis[oó]dio|episode|p[aá]gina|page)/.test(path);
  let readerImages = [];
  if (isReader) {
    const imageNodes = Array.from(document.querySelectorAll(
      '.reading-content img, .page-break img, .wp-manga-chapter-img, .chapter-content img, ' +
      '.reader-area img, .chapter-images img, #readerarea img, .container-chapter-reader img, ' +
      '#chapter_imgs img, .reading-detail img, .reader img, .comic-page img, .ts-main-image, ' +
      '[class*=reader] img, [class*=chapter] img, [class*=page] img, img'
    ));
    readerImages = imageNodes.map(img => {
      const cls = ((img.className || '') + ' ' + (img.id || '')).toLowerCase();
      return {url:imageOf(img), cls:cls, w:Number(img.naturalWidth || img.getAttribute('width') || 0),
        h:Number(img.naturalHeight || img.getAttribute('height') || 0)};
    }).filter(x => x.url && (
      /(chapter|page|reading|reader|comic|scan|manga-page)/.test(x.cls) ||
      x.w >= 260 || x.h >= 360 ||
      /(chapter|capitulo|manga-page|wp-content\/uploads|\/uploads\/)/i.test(x.url)
    )).map(x => x.url).filter((u,i,a) => a.indexOf(u) === i).slice(0,120);
  }
  const h1 = document.querySelector('h1');
  const title = tidy(h1 && (h1.innerText || h1.textContent)) || tidy(document.title) || 'Nexus Toons';
  return JSON.stringify({
    title:title,
    entries:entries.slice(0,300),
    readerImages:readerImages,
    debug:'DOM: ' + allAnchors.length + ' links; ' + sameSiteAnchors.length + ' links internos; ' + cards.length + ' cartões; ' + headingNodes.length + ' cabeçalhos.'
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
