package com.blackmanga.app

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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

    fun load(url: String) {
        scope.launch {
            loading = true
            error = ""
            try {
                val result = NexusToonsAdapter.load(url)
                currentUrl = url
                page = result
                if (result.readerImages.isNotEmpty()) {
                    readerImages = result.readerImages
                    readerTitle = result.title
                }
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Não foi possível carregar esta página."
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
        if (loading) CircularProgressIndicator()
        if (error.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF402126))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Não foi possível ler o catálogo", color = Color.White)
                    Text(error, color = Color.White, fontSize = 12.sp)
                    Text("O site pode exigir JavaScript, proteção anti-bot ou mudar a estrutura. Nesse caso, o adaptador precisa de ajustes.", color = Color.White, fontSize = 12.sp)
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
                        view.loadDataWithBaseURL("https://nexustoons.com/", buildReaderHtml(readerTitle, readerImages), "text/html", "UTF-8", null)
                    }
                )
                Button(onClick = { readerImages = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text("Voltar ao catálogo") }
            } else {
                Text(loaded.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pesquisar títulos") },
                    singleLine = true
                )
                Text("${loaded.entries.size} links encontrados", color = Color(0xFFB8B5C5), fontSize = 12.sp)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    loaded.entries.filter { it.title.contains(filter, true) }.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22))
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                if (entry.description.isNotBlank()) Text(entry.description, color = Color(0xFFB8B5C5), fontSize = 12.sp)
                                Button(onClick = { load(entry.url) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (entry.url.contains("cap", true) || entry.url.contains("chapter", true) || entry.url.contains("ler", true)) "Abrir capítulo" else "Abrir")
                                }
                            }
                        }
                    }
                }
                Text("Adaptador experimental: os resultados dependem da estrutura pública do site.", color = Color(0xFFB8B5C5), fontSize = 11.sp)
            }
        }
    }
}

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
