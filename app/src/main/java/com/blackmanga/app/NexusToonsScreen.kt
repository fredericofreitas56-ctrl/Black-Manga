package com.blackmanga.app

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NexusToonsScreen(startUrl: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var page by remember { mutableStateOf<NexusPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var readerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerTitle by remember { mutableStateOf("") }
    var needsDomFallback by remember { mutableStateOf(false) }
    var domFallbackLoading by remember { mutableStateOf(false) }
    var domFallbackMessage by remember { mutableStateOf("") }

    fun load(url: String) {
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
            currentUrl = url
            try {
                val directResult = try {
                    Result.success(NexusToonsAdapter.load(url))
                } catch (exception: Exception) {
                    Result.failure(exception)
                }
                val directPage = directResult.getOrNull()
                val shouldTryUniversal = directPage == null ||
                    (directPage.entries.isEmpty() && directPage.readerImages.isEmpty())
                val universalPage = if (shouldTryUniversal) {
                    try {
                        Result.success(withContext(Dispatchers.IO) { UniversalSiteAdapter.load(url) }).getOrNull()
                    } catch (_: Exception) {
                        null
                    }
                } else null

                if (directPage == null && universalPage == null) {
                    throw directResult.exceptionOrNull() ?: IllegalStateException("O Nexus Toons não devolveu conteúdo reconhecível.")
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
                    url = directPage?.url ?: universalPage?.url ?: url,
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
                // The site might serve content only to a browser/WebView.
                error = e.localizedMessage ?: "O leitor direto não conseguiu extrair os títulos."
                needsDomFallback = true
                domFallbackLoading = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(startUrl) { load(startUrl) }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("Voltar") }
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
                            else domFallbackMessage.ifBlank { "Não foram encontrados títulos nesta página. Experimenta atualizar ou abrir a página inicial da fonte." }
                        } else {
                            "Nenhum título corresponde a «${filter.trim()}»."
                        },
                        color = Color(0xFFB8B5C5),
                        fontSize = 13.sp
                    )
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleEntries.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22))
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                if (entry.description.isNotBlank()) {
                                    Text(entry.description, color = Color(0xFFB8B5C5), fontSize = 12.sp)
                                }
                                Button(onClick = { load(entry.url) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (entry.title.contains(Regex("cap[.íi]?t?u?l?o?\\s*\\d+", RegexOption.IGNORE_CASE)) ||
                                            entry.url.contains("cap", true) ||
                                            entry.url.contains("chapter", true) ||
                                            entry.url.contains("ler", true)) "Abrir capítulo" else "Abrir obra"
                                    )
                                }
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
            // WebView is kept visually hidden. It executes page JavaScript, then returns only
            // the title/link list to the native catalogue screen.
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                factory = { context ->
                    WebView(context).apply {
                        tag = currentUrl
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                val web = view ?: return
                                val finishedUrl = url ?: return
                                if (!NexusToonsAdapter.supports(finishedUrl)) return
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
                                            domFallbackMessage = "Não foi possível ler a estrutura desta página. O site continua visível abaixo; podes navegar nele sem ficar numa tela vazia."
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
                                        data.optString("debug", "O site abriu, mas não forneceu títulos reconhecíveis.") +
                                            " A página permanece visível para navegação; a extração nativa pode precisar de mais seletores."
                                    } else ""
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
                    val taggedUrl = view.tag as? String
                    if (taggedUrl != currentUrl) {
                        view.tag = currentUrl
                        view.loadUrl(currentUrl)
                    }
                }
            )
            Text(
                if (domFallbackLoading) {
                    "A carregar o site e a tentar reconhecer títulos, capas e capítulos…"
                } else {
                    "O reconhecimento automático não encontrou elementos suficientes. O site fica aberto abaixo para navegação."
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
    const h = String(host || '').toLowerCase().replace(/^www\\./, '');
    return h === 'nexustoons.com' || h.endsWith('.nexustoons.com');
  };
  const generic = /^(ver detalhes|detalhes|ler agora|ler|abrir|ver mais|saiba mais|continuar lendo|read more|view more|view details|home|in[ií]cio|menu|pesquisar|buscar)$/i;
  // Do not exclude /obras or /novels: a source can use these as valid title routes.
  const banned = new Set(['categoria','categorias','category','categories','genero','generos','genre','genres','tag','tags','search','pesquisa','pesquisar','busca','login','logout','register','registrar','u','user','users','perfil','profile','conta','account','sobre','contato','contact','politica','termos','ranking','notificacoes','notifications']);
  const entries = [];
  const seen = new Set();
  const tidy = s => String(s || '').replace(/\\s+/g, ' ').trim();
  const urlOf = a => {
    try { return new URL(a.href || a.getAttribute('href') || '', location.href); }
    catch(e) { return null; }
  };
  const allowedLink = a => {
    const u = urlOf(a);
    if (!u || u.protocol !== 'https:' || !hostAllowed(u.hostname)) return false;
    const parts = u.pathname.split('/').filter(Boolean);
    if (!parts.length || banned.has(parts[0].toLowerCase())) return false;
    if (u.href.split('#')[0] === location.href.split('#')[0]) return false;
    return true;
  };
  const imageOf = img => {
    if (!img) return '';
    const candidates = [
      img.getAttribute('data-src'), img.getAttribute('data-lazy-src'),
      img.getAttribute('data-original'), img.getAttribute('data-url'),
      img.getAttribute('data-lazyload'), img.currentSrc, img.src,
      (img.getAttribute('srcset') || '').split(',').pop().trim().split(/\\s+/)[0]
    ];
    for (const candidate of candidates) {
      if (!candidate || candidate.startsWith('data:image')) continue;
      try {
        const u = new URL(candidate, location.href);
        if (u.protocol === 'https:' && hostAllowed(u.hostname) === false || u.protocol === 'https:') return u.href;
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
  const chapterLabel = /^(cap\\.?|cap[ií]tulo|chapter|ep[ií]s[oó]dio|episode|p[aá]gina)\\s*\\d*/i;
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
  const isReader = /(cap[ií]tulo|capitulo|chapter|leitura|reader|\\/ler(?:\\/|$)|\\/read(?:\\/|$)|epis[oó]dio|episode|p[aá]gina|page)/.test(path);
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
    }).filter(x => x.url && (/(chapter|page|reading|reader|comic|scan|manga-page)/.test(x.cls) || x.w >= 260 || x.h >= 360))
      .map(x => x.url).filter((u,i,a) => a.indexOf(u) === i).slice(0,120);
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
