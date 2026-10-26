package com.blackmanga.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    private val genericLabels = setOf(
        "ver detalhes", "detalhes", "ler agora", "ler", "abrir", "ver mais",
        "saiba mais", "continuar lendo", "read more", "view more", "view details",
        "home", "início", "inicio", "menu", "pesquisar", "buscar"
    )

    private val excludedFirstPathSegments = setOf(
        "categoria", "categorias", "category", "categories", "genero", "generos",
        "genre", "genres", "tag", "tags", "search", "pesquisa", "pesquisar",
        "busca", "login", "logout", "register", "registrar", "u", "user", "users",
        "perfil", "profile", "conta", "account", "sobre", "contato", "contact",
        "politica", "termos", "ranking", "notificacoes", "notifications",
        "novels", "obras", "catalogo", "explorar", "loja", "biblioteca",
        "niveis", "níveis", "conquistas", "comunidade", "comunidades", "cart"
    )

    private val catalogueRoutes = setOf(
        "/", "/biblioteca", "/loja", "/catalogo", "/explorar", "/niveis",
        "/níveis", "/conquistas", "/comunidade", "/comunidades"
    )

    fun supports(rawUrl: String): Boolean = try {
        URI(rawUrl.trim()).host?.lowercase()?.let { it == HOST || it.endsWith(".$HOST") } == true
    } catch (_: Exception) {
        false
    }

    fun normalizeCatalogueStart(rawUrl: String): String = try {
        val uri = URI(rawUrl.trim())
        val route = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }.lowercase()
        if (supports(rawUrl) && route in catalogueRoutes) "https://nexustoons.com/" else rawUrl
    } catch (_: Exception) {
        rawUrl
    }

    suspend fun load(rawUrl: String): NexusPage = withContext(Dispatchers.IO) {
        val uri = URI(rawUrl.trim())
        require(uri.scheme.equals("https", true) && supports(rawUrl)) {
            "Usa uma URL HTTPS válida do Nexus Toons."
        }

        val document = Jsoup.connect(uri.toASCIIString())
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
            .timeout(20000)
            .followRedirects(true)
            .get()

        val baseUri = URI(document.baseUri())
        val baseHost = baseUri.host.orEmpty().lowercase()
        val pageUrl = document.baseUri().ifBlank { uri.toASCIIString() }

        fun absoluteImage(img: Element?): String? {
            if (img == null) return null
            val candidate = sequenceOf("data-src", "data-lazy-src", "data-original", "src")
                .map { img.absUrl(it) }
                .firstOrNull { it.startsWith("https://") && !it.contains("data:image", true) }
            return candidate?.takeIf {
                !it.contains("logo", true) && !it.contains("avatar", true) &&
                    !it.contains("icon", true) && !it.contains("emoji", true)
            }
        }

        fun cleaned(value: String?): String =
            value.orEmpty().replace(Regex("\\s+"), " ").trim()

        fun meaningfulHeading(parent: Element): String? =
            parent.selectFirst("h1,h2,h3,h4,h5,h6,[class*=title],[class*=titulo]")
                ?.let { cleaned(it.text()) }
                ?.takeIf { it.length >= 2 && !isGeneric(it) }

        fun isContentLink(link: String, label: String): Boolean {
            val target = try { URI(link) } catch (_: Exception) { return false }
            val host = target.host.orEmpty().lowercase()
            if (host != baseHost && host != HOST && !host.endsWith(".$HOST")) return false
            if (!target.scheme.equals("https", true)) return false

            val segments = target.path.orEmpty().split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return false
            val first = segments.first().lowercase()
            if (first in excludedFirstPathSegments) return false

            val sourcePath = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }.lowercase()
            val isCataloguePage = sourcePath in catalogueRoutes
            // On the catalogue/home page, only manga detail URLs are works. This prevents
            // menu items such as "Biblioteca", "Loja" and "Conquistas" becoming fake titles.
            if (isCataloguePage && first != "manga") return false
            // On a manga detail page, exclude other work pages but allow nested chapter routes.
            if (sourcePath.startsWith("/manga/") && first == "manga" && segments.size <= 2) return false

            if (segments.size == 1 && first in setOf(
                    "manga", "manhwa", "manhua", "novel", "comic", "obra", "capitulo"
                )) return false
            if (isGeneric(label)) return false
            return label.length >= 2
        }

        fun titleForAnchor(anchor: Element): String {
            val anchorText = cleaned(anchor.text())
            val titleAttr = cleaned(anchor.attr("title")).ifBlank { cleaned(anchor.attr("aria-label")) }
            val imageTitle = cleaned(anchor.selectFirst("img")?.attr("alt"))
            if (anchorText.length >= 2 && anchorText.length <= 180 && !isGeneric(anchorText)) {
                return anchorText
            }

            // Em vários cartões o link diz apenas "VER DETALHES", enquanto o título está no cabeçalho.
            anchor.parents().take(8).forEach { parent ->
                val heading = meaningfulHeading(parent)
                if (!heading.isNullOrBlank()) return heading.take(180)
            }
            return sequenceOf(titleAttr, imageTitle, anchorText)
                .map { cleaned(it) }
                .firstOrNull { it.length >= 2 && !isGeneric(it) }
                ?.take(180)
                .orEmpty()
        }

        val currentNoQuery = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val anchors = document.select("a[href]")
            .mapNotNull { anchor ->
                val link = anchor.absUrl("href").substringBefore("#").trim()
                if (link.isBlank()) return@mapNotNull null
                val target = try { URI(link) } catch (_: Exception) { return@mapNotNull null }
                val resolvedPath = target.path.orEmpty().trimEnd('/').ifBlank { "/" }
                if (resolvedPath == currentNoQuery && target.rawQuery.isNullOrBlank()) return@mapNotNull null

                // Navigation/header links must never be exposed as catalogue works.
                if (anchor.parents().any { parent ->
                        parent.normalName() in setOf("nav", "header", "footer") ||
                            parent.attr("role").equals("navigation", true)
                    }) return@mapNotNull null

                val title = titleForAnchor(anchor)
                if (!isContentLink(link, title)) return@mapNotNull null

                val card = anchor.parents().take(8).firstOrNull { parent ->
                    parent.selectFirst("h1,h2,h3,h4,h5,h6,[class*=title],[class*=titulo]") != null
                } ?: anchor.parent()
                val image = absoluteImage(anchor.selectFirst("img") ?: card?.selectFirst("img"))
                val desc = cleaned(
                    anchor.attr("title")
                        .ifBlank { anchor.attr("aria-label") }
                        .ifBlank { card?.selectFirst("p")?.text().orEmpty() }
                ).take(300)

                NexusEntry(title, link, image, desc)
            }
            .distinctBy { it.url }
            .take(300)

        val pathLower = uri.path.orEmpty().lowercase()
        val opaqueReaderRoute = pathLower.startsWith("/r/")
        val readerLike = opaqueReaderRoute || pathLower.contains("cap") || pathLower.contains("chapter") ||
            pathLower.contains("leitura") || pathLower.contains("ler") ||
            pathLower.contains("read") || pathLower.contains("episodio") ||
            pathLower.contains("episode")

        val pageImages = if (readerLike) {
            document.select("img")
                .filter { img ->
                    val classes = (img.className() + " " + img.id()).lowercase()
                    absoluteImage(img) != null && (
                        classes.contains("chapter") || classes.contains("page") ||
                            classes.contains("reading") || classes.contains("wp-manga") ||
                            classes.contains("manga-page") ||
                            img.attr("width").toIntOrNull()?.let { it > 250 } == true ||
                            (opaqueReaderRoute && img.parents().none { parent ->
                                parent.normalName() in setOf("header", "nav", "footer", "aside")
                            })
                        )
                }
                .mapNotNull { absoluteImage(it) }
                .distinct()
                .take(100)
        } else emptyList()

        val allImages = document.select("img").mapNotNull { absoluteImage(it) }.distinct()
        NexusPage(
            title = document.selectFirst("h1")?.text()?.let { cleaned(it) }?.takeIf { it.isNotBlank() }
                ?: document.title().let { cleaned(it) }.ifBlank { "Nexus Toons" },
            url = pageUrl,
            entries = anchors,
            readerImages = pageImages.ifEmpty { if (readerLike) allImages.take(100) else emptyList() }
        )
    }

    private fun isGeneric(value: String): Boolean {
        val normalized = value.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        return normalized in genericLabels
    }
}
