package com.blackmanga.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI

internal data class NexusEntry(
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val description: String = ""
)

internal data class NexusPage(
    val title: String,
    val url: String,
    val entries: List<NexusEntry>,
    val readerImages: List<String>
)

internal object NexusToonsAdapter {
    private const val HOST = "nexustoons.com"

    fun supports(rawUrl: String): Boolean = try {
        URI(rawUrl).host?.lowercase()?.let { it == HOST || it.endsWith(".$HOST") } == true
    } catch (_: Exception) {
        false
    }

    suspend fun load(rawUrl: String): NexusPage = withContext(Dispatchers.IO) {
        val uri = URI(rawUrl.trim())
        require(uri.scheme.equals("https", true) && supports(rawUrl)) {
            "Usa uma URL HTTPS válida do Nexus Toons."
        }
        val document = Jsoup.connect(uri.toASCIIString())
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
            .timeout(15000)
            .followRedirects(true)
            .get()

        val base = document.baseUri()
        val images = document.select("img")
            .mapNotNull { img ->
                val candidate = img.absUrl("data-src").ifBlank {
                    img.absUrl("data-lazy-src").ifBlank { img.absUrl("src") }
                }
                candidate.takeIf { it.startsWith("https://") && !it.contains("logo", true) }
            }
            .distinct()
            .filterNot { it.contains("avatar", true) || it.contains("icon", true) }

        val readerLike = uri.path.orEmpty().contains("cap", true) ||
            uri.path.orEmpty().contains("chapter", true) ||
            uri.path.orEmpty().contains("ler", true) ||
            uri.path.orEmpty().contains("leitura", true)

        val pageImages = if (readerLike) {
            document.select("img")
                .filter { img ->
                    val cls = (img.className() + " " + img.id()).lowercase()
                    val src = img.absUrl("data-src").ifBlank { img.absUrl("src") }
                    src.startsWith("https://") &&
                        (cls.contains("chapter") || cls.contains("page") || cls.contains("reading") ||
                            cls.contains("wp-manga") || img.attr("width").toIntOrNull()?.let { it > 250 } == true)
                }
                .mapNotNull { img ->
                    img.absUrl("data-src").ifBlank {
                        img.absUrl("data-lazy-src").ifBlank { img.absUrl("src") }
                    }.takeIf { it.startsWith("https://") }
                }
                .distinct()
        } else emptyList()

        val anchors = document.select("a[href]")
            .mapNotNull { a ->
                val link = a.absUrl("href").substringBefore("#")
                val label = a.text().trim().replace(Regex("\\s+"), " ")
                    .ifBlank { a.selectFirst("img")?.attr("alt").orEmpty().trim() }
                if (label.length < 2 || link.isBlank() || !link.startsWith("https://")) null
                else {
                    val path = try { URI(link).path.orEmpty() } catch (_: Exception) { "" }
                    val looksLikeContent = path.contains("/manga", true) ||
                        path.contains("/manhwa", true) || path.contains("/manhua", true) ||
                        path.contains("/obra", true) || path.contains("/comic", true) ||
                        path.contains("/cap", true) || path.contains("/chapter", true) ||
                        path.contains("/ler", true) || path.contains("/leitura", true) ||
                        path.contains("/biblioteca", true)
                    if (!looksLikeContent || link.trimEnd('/') == rawUrl.trimEnd('/')) null
                    else {
                        val img = a.selectFirst("img")
                        val imgUrl = img?.let {
                            it.absUrl("data-src").ifBlank { it.absUrl("data-lazy-src").ifBlank { it.absUrl("src") } }
                        }?.takeIf { it.startsWith("https://") }
                        val desc = a.attr("title").ifBlank { img?.attr("alt").orEmpty() }
                        NexusEntry(label.take(180), link, imgUrl, desc.take(300))
                    }
                }
            }
            .distinctBy { it.url }
            .take(100)

        NexusPage(
            title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                ?: document.title().ifBlank { "Nexus Toons" },
            url = base,
            entries = anchors,
            readerImages = pageImages.ifEmpty { if (readerLike) images.take(80) else emptyList() }
        )
    }
}
