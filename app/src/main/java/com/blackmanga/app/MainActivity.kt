package com.blackmanga.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BlackMangaTheme { BlackMangaApp() } }
    }
}

private val backgroundColor = Color(0xFF101014)
private val surfaceColor = Color(0xFF1A1A22)
private val accentColor = Color(0xFFB7A0FF)
private val mutedColor = Color(0xFFB8B5C5)

@Composable
private fun BlackMangaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accentColor,
            onPrimary = Color(0xFF201044),
            background = backgroundColor,
            onBackground = Color(0xFFF4F1FF),
            surface = surfaceColor,
            onSurface = Color(0xFFF4F1FF),
            secondary = Color(0xFF7CDCCB)
        ),
        content = content
    )
}

@Composable
private fun BlackMangaApp() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var activeWebUrl by remember { mutableStateOf<String?>(null) }
    var translationInput by remember { mutableStateOf("") }
    val savedSources = remember {
        mutableStateListOf<String>().apply {
            val saved = context.getSharedPreferences("black_manga", Context.MODE_PRIVATE)
                .getStringSet("saved_sources", emptySet()).orEmpty()
            addAll(saved.sorted())
        }
    }

    if (activeWebUrl != null) {
        WebReaderScreen(
            url = activeWebUrl.orEmpty(),
            onBack = { activeWebUrl = null },
            onExtractText = { text ->
                translationInput = text
                activeWebUrl = null
                selectedTab = 3
            }
        )
        return
    }

    Scaffold(
        containerColor = backgroundColor,
        bottomBar = {
            NavigationBar(containerColor = surfaceColor) {
                val labels = listOf("Início", "Biblioteca", "Sites", "Traduzir")
                val symbols = listOf("⌂", "▤", "◎", "文")
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(symbols[index], fontSize = 20.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(surfaceColor).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("BLACK MANGA", fontSize = 20.sp, fontWeight = FontWeight.Black, color = accentColor)
                    Text("Leia. Descubra. Traduza.", fontSize = 12.sp, color = mutedColor)
                }
                Text("BM", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            when (selectedTab) {
                0 -> HomeScreen(onGoToSites = { selectedTab = 2 }, onGoToTranslate = { selectedTab = 3 })
                1 -> LibraryScreen()
                2 -> SourcesScreen(sources = savedSources, onOpenUrl = { activeWebUrl = it })
                else -> TranslationScreen(initialText = translationInput)
            }
        }
    }
}

@Composable
private fun HomeScreen(onGoToSites: () -> Unit, onGoToTranslate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF28213D))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("O teu próximo capítulo começa aqui.", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Adiciona sites que tens autorização para usar, abre-os no leitor e traduz texto para português.", color = mutedColor)
                Button(onClick = onGoToSites) { Text("Adicionar um site") }
            }
        }
        Text("FUNÇÕES PRINCIPAIS", fontWeight = FontWeight.Bold, color = accentColor)
        FeatureCard("Sites personalizados", "Insere uma URL HTTPS, testa se responde e guarda as tuas fontes.")
        FeatureCard("Leitor web integrado", "Abre o site dentro do Black Manga e extrai o texto visível para tradução.")
        FeatureCard("Tradução no dispositivo", "Depois de baixar o modelo de idioma, a tradução de texto pode funcionar sem internet.")
        FeatureCard("OCR de páginas", "Seleciona uma imagem para reconhecer texto japonês ou latino e traduzir o resultado.")
        Button(onClick = onGoToTranslate, modifier = Modifier.fillMaxWidth()) { Text("Abrir tradução") }
        Text(
            "A verificação de uma URL só confirma a resposta do site. Catálogos e capítulos precisam de uma integração autorizada compatível.",
            color = mutedColor,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun FeatureCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = surfaceColor)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = mutedColor, fontSize = 14.sp)
        }
    }
}

@Composable
private fun LibraryScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("A tua biblioteca", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "A biblioteca de obras será preenchida quando ligares um catálogo com integração autorizada. Nesta versão inicial, já podes guardar fontes, abrir sites e traduzir texto.",
            color = mutedColor
        )
        FeatureCard("Próxima etapa", "Adicionar adaptadores de catálogo autorizados e histórico de leitura.")
    }
}

private data class SiteCheck(val reachable: Boolean, val message: String)

private fun checkWebsite(rawUrl: String): SiteCheck {
    val parsed = try { URI(rawUrl.trim()) } catch (_: Exception) {
        return SiteCheck(false, "Endereço inválido. Cola uma URL completa, por exemplo https://exemplo.org")
    }
    if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank()) {
        return SiteCheck(false, "Por segurança, usa um endereço HTTPS completo.")
    }
    if (parsed.userInfo != null) return SiteCheck(false, "Não coloques utilizador ou palavra-passe dentro do endereço.")

    var connection: HttpsURLConnection? = null
    return try {
        connection = URL(parsed.toASCIIString()).openConnection() as HttpsURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "BlackManga/0.1 (user-initiated compatibility check)")
        val code = connection.responseCode
        val type = connection.contentType ?: "tipo de conteúdo desconhecido"
        if (code in 200..299) {
            SiteCheck(true, "O site respondeu (HTTP " + code + ", " + type + "). Isto não confirma que tenha um catálogo de mangá compatível.")
        } else {
            SiteCheck(false, "O site respondeu com HTTP " + code + ". Pode exigir acesso próprio ou não permitir esta verificação.")
        }
    } catch (exception: Exception) {
        SiteCheck(false, "Não foi possível verificar a URL: " + (exception.localizedMessage ?: "erro de ligação"))
    } finally {
        connection?.disconnect()
    }
}

@Composable
private fun SourcesScreen(sources: List<String>, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var checkResult by remember { mutableStateOf<SiteCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmsPermission by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Fontes e sites", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Insere o endereço de um site. O teste verifica a ligação HTTPS; não verifica licenças nem cria automaticamente um adaptador de catálogo.",
            color = mutedColor
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; checkResult = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Endereço do site") },
            placeholder = { Text("https://exemplo.org") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmsPermission, onCheckedChange = { confirmsPermission = it })
            Text("Confirmo que tenho autorização para utilizar esta fonte.", fontSize = 13.sp, color = mutedColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        checking = true
                        checkResult = withContext(Dispatchers.IO) { checkWebsite(url) }
                        checking = false
                    }
                },
                enabled = !checking && url.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                if (checking) CircularProgressIndicator() else Text("Verificar URL")
            }
            Button(
                onClick = {
                    val checked = checkResult
                    if (checked?.reachable == true && confirmsPermission) {
                        val normalized = url.trim().removeSuffix("/")
                        if (normalized !in sources) {
                            @Suppress("UNCHECKED_CAST")
                            (sources as? MutableList<String>)?.add(normalized)
                            context.getSharedPreferences("black_manga", Context.MODE_PRIVATE)
                                .edit().putStringSet("saved_sources", sources.toSet()).apply()
                        }
                        url = normalized
                        checkResult = SiteCheck(true, "Fonte guardada. A importação automática depende de um adaptador compatível.")
                    }
                },
                enabled = checkResult?.reachable == true && confirmsPermission,
                modifier = Modifier.weight(1f)
            ) { Text("Guardar fonte") }
        }
        checkResult?.let { result ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (result.reachable) Color(0xFF18372D) else Color(0xFF402126)
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text(result.message, modifier = Modifier.padding(14.dp), color = Color.White) }
        }
        Divider(color = Color(0xFF373640))
        Text("FONTES GUARDADAS (" + sources.size + ")", fontWeight = FontWeight.Bold, color = accentColor)
        if (sources.isEmpty()) {
            Text("Ainda não guardaste fontes.", color = mutedColor)
        } else {
            sources.forEach { savedUrl ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = surfaceColor)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(savedUrl, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenUrl(savedUrl) }, modifier = Modifier.weight(1f)) { Text("Abrir no leitor") }
                            Button(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(savedUrl))) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Abrir externo") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WebReaderScreen(url: String, onBack: () -> Unit, onExtractText: (String) -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("A carregar o site…") }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(surfaceColor).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onBack) { Text("Voltar") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Leitor web", fontWeight = FontWeight.Bold)
                Text(url, fontSize = 10.sp, color = mutedColor, maxLines = 1)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val view = webViewRef
                    if (view == null) {
                        status = "O leitor ainda está a iniciar."
                    } else {
                        status = "A extrair o texto visível…"
                        view.evaluateJavascript(
                            "(function(){return document.body ? document.body.innerText.slice(0,12000) : '';})()"
                        ) { raw ->
                            val extracted = try { JSONTokener(raw).nextValue() as? String ?: "" } catch (_: Exception) { "" }
                            if (extracted.isBlank()) {
                                status = "Não foi encontrado texto. A página pode estar vazia ou bloquear a leitura."
                            } else {
                                status = "Texto extraído. A abrir a tradução…"
                                onExtractText(extracted.take(12000))
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Extrair texto da página para traduzir") }
        }
        Text(status, color = mutedColor, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    webViewRef = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            status = "Página carregada. Podes extrair o texto visível."
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { webViewRef = it }
        )
    }
}

@Composable
private fun TranslationScreen(initialText: String) {
    val context = LocalContext.current
    var sourceText by remember(initialText) { mutableStateOf(initialText) }
    var resultText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("O modelo pode funcionar offline depois de ser baixado.") }
    var sourceIsJapanese by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }

    fun translateCurrentText(text: String) {
        if (text.isBlank()) {
            status = "Escreve ou extrai algum texto primeiro."
            return
        }
        working = true
        resultText = ""
        status = "A preparar o modelo de tradução…"
        val sourceLanguage = if (sourceIsJapanese) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE)
            .build()
        val translator = Translation.getClient(options)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                status = "A traduzir para português…"
                translator.translate(text.take(12000))
                    .addOnSuccessListener { translated ->
                        resultText = translated
                        status = "Tradução concluída. O modelo pode ser usado offline enquanto estiver instalado."
                        working = false
                        translator.close()
                    }
                    .addOnFailureListener { error ->
                        status = "Falha na tradução: " + (error.localizedMessage ?: "erro desconhecido")
                        working = false
                        translator.close()
                    }
            }
            .addOnFailureListener { error ->
                status = "Não foi possível obter o modelo. Liga à internet na primeira utilização e tenta novamente. " +
                    (error.localizedMessage ?: "")
                working = false
                translator.close()
            }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { imageUri ->
        if (imageUri != null) {
            status = "A reconhecer o texto da imagem…"
            try {
                val inputImage = InputImage.fromFilePath(context, imageUri)
                val recognizer = if (sourceIsJapanese) {
                    TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                } else {
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val recognized = visionText.text.trim()
                        recognizer.close()
                        if (recognized.isBlank()) {
                            status = "Não foi possível reconhecer texto nesta imagem."
                        } else {
                            sourceText = recognized.take(12000)
                            translateCurrentText(recognized)
                        }
                    }
                    .addOnFailureListener { error ->
                        recognizer.close()
                        status = "Falha no OCR: " + (error.localizedMessage ?: "erro desconhecido")
                    }
            } catch (error: Exception) {
                status = "Não foi possível abrir a imagem: " + (error.localizedMessage ?: "erro")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Tradutor integrado", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Traduz texto copiado dos sites ou reconhecido nas imagens. O resultado aparece abaixo da imagem; ainda não substitui as letras diretamente na arte.",
            color = mutedColor
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sourceIsJapanese, onClick = { sourceIsJapanese = true }, label = { Text("Origem: japonês") })
            FilterChip(selected = !sourceIsJapanese, onClick = { sourceIsJapanese = false }, label = { Text("Origem: inglês") })
        }
        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Texto original") },
            placeholder = { Text("Cola aqui o texto do site ou do mangá…") },
            minLines = 5,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { translateCurrentText(sourceText) },
                enabled = !working && sourceText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                if (working) CircularProgressIndicator() else Text("Traduzir")
            }
            Button(
                onClick = { imagePicker.launch("image/*") },
                enabled = !working,
                modifier = Modifier.weight(1f)
            ) { Text("Escolher imagem") }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = surfaceColor)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRADUÇÃO", color = accentColor, fontWeight = FontWeight.Bold)
                Text(if (resultText.isBlank()) "O texto traduzido aparecerá aqui." else resultText)
            }
        }
        Text(status, color = mutedColor, fontSize = 12.sp)
        Text(
            "OCR e tradução são processados no dispositivo após instalar os modelos. O ML Kit oferece Português, mas não seleciona um dialeto separado pt-BR.",
            color = mutedColor,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
    }
}
