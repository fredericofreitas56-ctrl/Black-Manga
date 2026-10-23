package com.blackmanga.app

import android.view.View
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
import kotlinx.coroutines.launch

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
                val result = NexusToonsAdapter.load(url)
                page = result
                if (result.readerImages.isNotEmpty()) {
                    readerImages = result.readerImages
                    readerTitle = result.title
                }
                if (result.entries.isEmpty() && result.readerImages.isEmpty()) {
                    // Jsoup does not execute page JavaScript. Try the rendered DOM in Android WebView.
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
        page?.let { loaded ->
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
                modifier = Modifier.height(1.dp).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        tag = currentUrl
                        visibility = View.INVISIBLE
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
                                // Nexus Toons renders catalogue cards with client-side scripts. Wait briefly after page load before extracting the DOM.
                                web.postDelayed({
                                    if (web.url != finishedUrl) return@postDelayed
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
                                        domFallbackLoading = false
                                        needsDomFallback = false
                                        domFallbackMessage = "O site não devolveu uma lista de títulos que a aplicação consiga ler. Pode usar JavaScript protegido ou exigir uma sessão."
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
                                    needsDomFallback = false
                                    domFallbackMessage = if (combinedEntries.isEmpty() && combinedImages.isEmpty()) {
                                        data.optString("debug", "O site abriu, mas não forneceu títulos reconhecíveis.") +
                                            " Tenta usar a página inicial do Nexus Toons."
                                    } else ""
                                    }
                                }, 1800)
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
                "A procurar títulos e ligações na página apresentada pelo site…",
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
  const rootDomain = h => {
    h = (h || '').toLowerCase().replace(/^www\./, '');
    return h === 'nexustoons.com' || h.endsWith('.nexustoons.com');
  };
  const generic = /^(ver detalhes|detalhes|ler agora|ler|abrir|ver mais|saiba mais|continuar lendo|read more|view more|view details|home|in[ií]cio|menu|pesquisar|buscar)$/i;
  const banned = new Set(['categoria','categorias','category','categories','genero','generos','genre','genres','tag','tags','search','pesquisa','pesquisar','busca','login','logout','register','registrar','u','user','users','perfil','profile','conta','account','sobre','contato','contact','politica','termos','ranking','notificacoes','notifications','novels','obras','catalogo','explorar']);
  const entries = [];
  const seen = new Set();
  const tidy = s => (s || '').replace(/\s+/g, ' ').trim();
  const urlOf = a => {
    try { return new URL(a.href || a.getAttribute('href') || '', location.href); }
    catch(e) { return null; }
  };
  const allowedLink = a => {
    const u = urlOf(a);
    if (!u || u.protocol !== 'https:' || !rootDomain(u.hostname)) return false;
    const parts = u.pathname.split('/').filter(Boolean);
    if (!parts.length || banned.has(parts[0].toLowerCase())) return false;
    if (u.href.split('#')[0] === location.href.split('#')[0]) return false;
    return true;
  };
  const add = (title, anchor, source) => {
    title = tidy(title);
    const u = anchor ? urlOf(anchor) : null;
    if (!u || !allowedLink(anchor) || title.length < 2 || title.length > 180 || generic.test(title)) return;
    const key = u.href.split('#')[0];
    if (seen.has(key)) return;
    seen.add(key);
    let node = anchor;
    let image = anchor.querySelector('img');
    let description = '';
    for (let i = 0; i < 8 && node; i++, node = node.parentElement) {
      if (!image && node.querySelector('img')) image = node.querySelector('img');
      if (!description && node.querySelector('p')) description = tidy(node.querySelector('p').innerText || node.querySelector('p').textContent);
      if (i > 0 && node.querySelectorAll('h1,h2,h3,h4,h5,h6').length > 3) break;
    }
    const imageUrl = image ? (image.currentSrc || image.src || image.getAttribute('data-src') || '') : '';
    entries.push({title:title, url:key, image:imageUrl, description:description.slice(0,300), source:source || 'link'});
  };
  const allAnchors = Array.from(document.querySelectorAll('a[href]'));
  const sameSiteAnchors = allAnchors.filter(allowedLink);

  // First pass: titles embedded in the link itself or in a nearby catalogue-card heading.
  for (const a of sameSiteAnchors) {
    let title = tidy(a.innerText || a.textContent || '');
    if (title.length > 180 || generic.test(title) || title.length < 2) {
      let node = a;
      for (let i = 0; i < 10 && node; i++, node = node.parentElement) {
        const headings = Array.from(node.querySelectorAll('h1,h2,h3,h4,h5,h6,[class*="title"],[class*="titulo"]'));
        const candidate = headings.map(h => tidy(h.innerText || h.textContent)).find(t => t.length >= 2 && t.length <= 180 && !generic.test(t));
        if (candidate) { title = candidate; break; }
        if (i >= 3 && node.querySelectorAll('a[href]').length > 12) break;
      }
    }
    if (title.length >= 2 && title.length <= 180 && !generic.test(title)) add(title, a, 'anchor');
  }

  // Second pass: some layouts place the title heading beside the "VER DETALHES" link.
  const headingNodes = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6,[class*="manga-title"],[class*="book-title"],[class*="series-title"],[class*="titulo"]'));
  for (const h of headingNodes) {
    const title = tidy(h.innerText || h.textContent);
    if (title.length < 2 || title.length > 180 || generic.test(title)) continue;
    let best = null;
    let node = h;
    for (let depth = 0; depth < 10 && node; depth++, node = node.parentElement) {
      const links = Array.from(node.querySelectorAll('a[href]')).filter(allowedLink);
      if (links.length) {
        const details = links.find(a => /^(ver detalhes|detalhes|view details)$/i.test(tidy(a.innerText || a.textContent)));
        const linkedHeading = h.closest('a[href]');
        best = linkedHeading && allowedLink(linkedHeading) ? linkedHeading : (details || links[0]);
        if (best) break;
      }
      if (depth >= 2 && node.querySelectorAll('h1,h2,h3,h4,h5,h6').length > 3) break;
    }
    if (best) add(title, best, 'heading');
  }

  const path = location.pathname.toLowerCase();
  const isReader = /(cap|chapter|leitura|ler|read|episodio|episode)/.test(path);
  let readerImages = [];
  if (isReader) {
    readerImages = Array.from(document.querySelectorAll('img')).map(img => {
      const cls = ((img.className || '') + ' ' + (img.id || '')).toLowerCase();
      const w = Number(img.getAttribute('width') || img.naturalWidth || 0);
      return {url:img.currentSrc || img.src || img.getAttribute('data-src') || '', cls:cls, w:w};
    }).filter(x => x.url.startsWith('https://') &&
      (/(chapter|page|reading|wp-manga|manga-page)/.test(x.cls) || x.w > 250))
      .map(x => x.url).filter((u,i,a) => a.indexOf(u) === i).slice(0,100);
  }
  const h1 = document.querySelector('h1');
  return JSON.stringify({
    title: tidy(h1 ? h1.innerText : document.title) || 'Nexus Toons',
    entries: entries.slice(0,300),
    readerImages: readerImages,
    debug: 'Análise da página: ' + allAnchors.length + ' links, ' + sameSiteAnchors.length + ' links internos e ' + headingNodes.length + ' títulos de cabeçalho encontrados.'
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
