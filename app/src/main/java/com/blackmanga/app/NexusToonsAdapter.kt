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
    val description: String = "",
    val kind: String = "",
    val status: String = "",
    val rating: String = "",
    val chapterCount: String = "",
    val genres: List<String> = emptyList()
)

internal data class NexusGenre(
    val title: String,
    val url: String
)

internal data class NexusPage(
    val title: String,
    val url: String,
    val entries: List<NexusEntry>,
    val readerImages: List<String>,
    val chapters: List<NexusEntry> = emptyList(),
    val genres: List<NexusGenre> = emptyList(),
    val nextPageUrl: String? = null,
    val coverUrl: String? = null,
    val synopsis: String = "",
    val author: String = "",
    val status: String = "",
    val kind: String = "",
    val rating: String = "",
    val chapterCount: String = ""
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
        "niveis", "níveis", "conquistas", "comunidade", "comunidades", "cart",
        "app", "download", "downloads", "sobre", "ajuda", "faq", "conta", "perfil"
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

    fun isReaderUrl(rawUrl: String): Boolean = try {
        val path = URI(rawUrl.trim()).path.orEmpty().lowercase()
        path.startsWith("/r/") || listOf(
            "/capitulo/", "/chapter/", "/leitura/", "/read/", "/episode/", "/episodio/"
        ).any { path.contains(it) }
    } catch (_: Exception) { false }

    fun isCatalogueUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl.trim())
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }.lowercase()
        supports(rawUrl) && (
            path in catalogueRoutes ||
            path.startsWith("/genero/") || path.startsWith("/generos/") ||
            path.startsWith("/genre/") || path.startsWith("/genres/") ||
            path.startsWith("/categoria/") || path.startsWith("/categorias/") ||
            path.startsWith("/tag/") ||
            Regex("/page/\\d+").containsMatchIn(path) ||
            Regex("/pagina/\\d+").containsMatchIn(path) ||
            uri.rawQuery.orEmpty().contains("paged=") ||
            uri.rawQuery.orEmpty().contains("page=")
        )
    } catch (_: Exception) { false }

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
            val classes = (img.className() + " " + img.id() + " " + img.attr("alt")).lowercase()
            if (listOf("avatar", "profile", "user-icon", "emoji", "favicon", "sprite", "mascot", "placeholder")
                    .any { classes.contains(it) }) return null
            val candidate = sequenceOf("data-src", "data-lazy-src", "data-original", "data-lazyload", "data-url", "src", "srcset", "data-srcset", "data-lazy-srcset")
                .map { attr -> 
                    val raw = img.attr(attr).substringBefore(",").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                    if (raw.isBlank()) "" else try { URI(raw).let { if (it.isAbsolute) raw else img.absUrl(attr) } } catch (_: Exception) { img.absUrl(attr) }
                }
                .firstOrNull { it.startsWith("https://") && !it.contains("data:image", true) }
            return candidate?.takeIf {
                val lower = it.lowercase()
                !listOf("logo", "avatar", "favicon", "emoji", "sprite", "mascot", "placeholder", "profile")
                    .any { marker -> lower.contains(marker) }
            }
        }

        fun cleaned(value: String?): String =
            value.orEmpty().replace(Regex("\\s+"), " ").trim()

        fun meaningfulHeading(parent: Element): String? =
            parent.selectFirst("h1,h2,h3,h4,h5,h6,[class*=manga-title],[class*=book-title],[class*=series-title],[class*=title],[class*=titulo],[class*=name]")
                ?.let { cleaned(it.text()) }
                ?.takeIf { it.length >= 2 && !isGeneric(it) }

        fun isChapterLink(target: URI, label: String): Boolean {
            val path = target.path.orEmpty().lowercase()
            return path.startsWith("/r/") || path.contains("/capitulo/") || path.contains("/chapter/") ||
                path.contains("/leitura/") || path.contains("/read/") ||
                Regex("^(cap\\.?|cap[ií]tulo|chapter|ep[ií]s[oó]dio|episode|p[aá]gina)\\s*\\d+", RegexOption.IGNORE_CASE)
                    .containsMatchIn(label.trim())
        }

        fun imageFromCard(anchor: Element, card: Element?): String? {
            val candidates = (anchor.select("img").toList() + card?.select("img").orEmpty())
                .distinctBy { it.outerHtml() }
            return candidates.mapNotNull { img ->
                val url = absoluteImage(img) ?: return@mapNotNull null
                val classes = (img.className() + " " + img.id()).lowercase()
                var score = 0
                if (classes.contains("cover") || classes.contains("thumb") || classes.contains("poster")) score += 50
                if (classes.contains("manga") || classes.contains("comic") || classes.contains("book")) score += 35
                if (img.attr("alt").isNotBlank()) score += 8
                if ((img.attr("width").toIntOrNull() ?: 0) >= 100) score += 12
                if ((img.attr("height").toIntOrNull() ?: 0) >= 150) score += 12
                if (listOf("logo", "avatar", "icon", "emoji", "profile", "mascot").any { classes.contains(it) || url.contains(it, true) }) score -= 100
                score to url
            }.maxByOrNull { it.first }?.second
        }

        fun cardFor(anchor: Element): Element? {
            return anchor.parents().take(9).firstOrNull { parent ->
                val classes = (parent.className() + " " + parent.id()).lowercase()
                val looksLikeCard = listOf("manga", "comic", "manhwa", "manhua", "book", "series", "swiper-slide", "post-item", "cover", "thumbnail", "entry-card")
                    .any { classes.contains(it) } || parent.normalName() == "article" || parent.normalName() == "li"
                val workLinks = parent.select("a[href*=/manga/]").size
                val imageCount = parent.select("img").size
                looksLikeCard && workLinks in 1..3 && imageCount in 1..6
            } ?: anchor.parents().take(6).firstOrNull { parent ->
                parent.select("a[href*=/manga/]").size == 1 && parent.select("img").isNotEmpty() &&
                    meaningfulHeading(parent) != null
            }
        }

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
            val isCataloguePage = isCatalogueUrl(pageUrl)
            // The site's work URLs are not guaranteed to use /manga/. Keep all same-site
            // work routes on catalogue pages, while excluding menus and chapter links.
            if (isCataloguePage && Regex(
                    "^(cap\\.?|cap[ií]tulo|chapter|ep[ií]s[oó]dio|episode|p[aá]gina)\\s*\\d+",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(label.trim())) return false
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
        val sourcePath = currentNoQuery.lowercase()
        val isCataloguePage = sourcePath in catalogueRoutes
        val workEntries = document.select("a[href*=/manga/]")
            .mapNotNull { anchor ->
                val link = anchor.absUrl("href").substringBefore("#").trim()
                if (link.isBlank()) return@mapNotNull null
                if (anchor.parents().any { parent ->
                        parent.normalName() in setOf("nav", "header", "footer") ||
                            parent.attr("role").equals("navigation", true)
                    }) return@mapNotNull null
                val target = try { URI(link) } catch (_: Exception) { return@mapNotNull null }
                if (!isContentLink(link, titleForAnchor(anchor))) return@mapNotNull null
                if (isChapterLink(target, cleaned(anchor.text()))) return@mapNotNull null

                val card = cardFor(anchor)
                val heading = card?.let { meaningfulHeading(it) }
                val title = sequenceOf(
                    heading.orEmpty(),
                    cleaned(anchor.attr("title")),
                    cleaned(anchor.attr("aria-label")),
                    cleaned(anchor.selectFirst("img")?.attr("alt")),
                    cleaned(anchor.text())
                ).map { cleaned(it) }
                    .firstOrNull { it.length in 2..180 && !isGeneric(it) }
                    ?: return@mapNotNull null
                val image = imageFromCard(anchor, card)
                val desc = cleaned(card?.selectFirst("p,[class*=description],[class*=summary]")?.text()).take(300)
                val cardText = cleaned(card?.text())
                val kind = Regex("\\b(MANHWA|MANHUA|MANGA|NOVEL)\\b", RegexOption.IGNORE_CASE)
                    .find(cardText)?.value.orEmpty()
                val status = Regex("\\b(Em andamento|Hiato|Concluído|Concluido|Completo)\\b", RegexOption.IGNORE_CASE)
                    .find(cardText)?.value.orEmpty()
                val rating = Regex("(?<!\\d)([0-5]\\.\\d)(?!\\d)").find(cardText)?.groupValues?.getOrNull(1).orEmpty()
                val chapters = Regex("Cap\\.?\\s*(\\d+)|([0-9]+)\\s*caps?", RegexOption.IGNORE_CASE)
                    .find(cardText)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }.orEmpty()
                val cardGenres = card?.select("a[href*=/genero/],a[href*=/generos/],a[href*=/genre/],a[href*=/genres/]")
                    ?.map { cleaned(it.text()) }?.filter { it.isNotBlank() }?.distinct().orEmpty()
                NexusEntry(title, link, image, desc, kind, status, rating, chapters, cardGenres)
            }
            .distinctBy { it.url }
            .take(300)

        val chapterEntries = if (!isCataloguePage) {
            document.select("a[href]")
                .mapNotNull { anchor ->
                    val link = anchor.absUrl("href").substringBefore("#").trim()
                    if (link.isBlank()) return@mapNotNull null
                    val target = try { URI(link) } catch (_: Exception) { return@mapNotNull null }
                    val title = sequenceOf(
                        cleaned(anchor.text()),
                        cleaned(anchor.attr("title")),
                        cleaned(anchor.attr("aria-label"))
                    ).map { cleaned(it) }.firstOrNull { it.isNotBlank() } ?: ""
                    if (!NexusToonsAdapter.supports(link) || !link.startsWith("https://") ||
                        !isChapterLink(target, title)) return@mapNotNull null
                    if (anchor.parents().any { it.normalName() in setOf("nav", "header", "footer") }) return@mapNotNull null
                    val cleanTitle = title.ifBlank {
                        target.path.orEmpty().split('/').filter { it.isNotBlank() }.lastOrNull().orEmpty()
                    }.replace(Regex("\\s+"), " ").trim()
                    if (cleanTitle.isBlank()) return@mapNotNull null
                    NexusEntry(cleanTitle, link)
                }
                .distinctBy { it.url }
                .take(500)
        } else emptyList()

        val genreLinks = document.select("a[href]")
            .mapNotNull { anchor ->
                val text = cleaned(anchor.text())
                if (text.isBlank() || text.length > 40) return@mapNotNull null
                val link = anchor.absUrl("href").substringBefore("#").trim()
                val target = runCatching { URI(link) }.getOrNull() ?: return@mapNotNull null
                if (!supports(link) || !link.startsWith("https://")) return@mapNotNull null
                val route = target.path.orEmpty().lowercase()
                if (!listOf("/genero/", "/generos/", "/genre/", "/genres/", "/categoria/", "/categorias/", "/tag/")
                        .any { route.contains(it) }) return@mapNotNull null
                NexusGenre(text, link)
            }
            .distinctBy { it.url }
            .take(30)

        val nextPageUrl = document.select("a[rel=next],.pagination a.next,.nav-links a.next,a.page-numbers.next,a[aria-label*=Next],a[aria-label*=Próxima],a.load-more,.load-more a")
            .mapNotNull { it.absUrl("href").takeIf { link -> supports(link) && link.startsWith("https://") } }
            .firstOrNull()

        val detailsText = cleaned(document.text())
        val synopsis = document.selectFirst(
            ".manga-summary,.summary__content,.description-summary,.entry-content,.manga-description,[class*=synopsis],[class*=description]"
        )?.let { cleaned(it.text()) }.orEmpty()
        val coverUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("https://") }
            ?: imageFromCard(document.selectFirst("a[href*=/manga/]") ?: document.body(), document.body())
        val author = document.select("li,div,p,span").firstOrNull { cleaned(it.text()).startsWith("Autor", true) }
            ?.let { cleaned(it.text()).removePrefix("Autor").removePrefix(":").trim() }.orEmpty()
        val status = Regex("\\b(Em andamento|Hiato|Concluído|Concluido|Completo)\\b", RegexOption.IGNORE_CASE)
            .find(detailsText)?.value.orEmpty()
        val kind = Regex("\\b(Manhwa|Manhua|Manga|Novel)\\b", RegexOption.IGNORE_CASE)
            .find(detailsText)?.value.orEmpty()
        val rating = Regex("(?<!\\d)([0-5]\\.\\d)(?!\\d)").find(detailsText)?.groupValues?.getOrNull(1).orEmpty()
        val chapterCount = Regex("Capítulos?\\s*\\((\\d+)\\)|([0-9]+)\\s*caps?", RegexOption.IGNORE_CASE)
            .find(detailsText)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }.orEmpty()

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
        val pageTitle = document.selectFirst("h1")?.text()?.let { cleaned(it) }?.takeIf { it.isNotBlank() }
            ?: document.title().let { cleaned(it) }.ifBlank { "Nexus Toons" }
        NexusPage(
            title = pageTitle,
            url = pageUrl,
            entries = if (isCataloguePage) workEntries else emptyList(),
            readerImages = pageImages.ifEmpty { if (readerLike) allImages.take(100) else emptyList() },
            chapters = chapterEntries,
            genres = genreLinks,
            nextPageUrl = nextPageUrl,
            coverUrl = coverUrl,
            synopsis = synopsis,
            author = author,
            status = status,
            kind = kind,
            rating = rating,
            chapterCount = chapterCount
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
