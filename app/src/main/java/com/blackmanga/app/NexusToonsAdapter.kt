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
        "app", "download", "downloads", "sobre", "ajuda", "faq", "conta", "perfil",
        "page", "pagina", "pages", "pagination", "next", "previous", "prev"
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

    fun guessedNextPageUrl(rawUrl: String): String? = try {
        val uri = URI(rawUrl.trim())
        if (!supports(rawUrl) || !isCatalogueUrl(rawUrl)) return null
        val path = uri.path.orEmpty().trimEnd('/').ifBlank { "/" }
        val query = uri.rawQuery.orEmpty()
        val queryPage = Regex("([?&](?:paged|page)=)(\\d+)", RegexOption.IGNORE_CASE).find(rawUrl)
        if (queryPage != null) {
            val nextQuery = queryPage.groupValues[1] + (queryPage.groupValues[2].toIntOrNull()?.plus(1) ?: 2)
            return rawUrl.replaceRange(queryPage.range, nextQuery)
        }
        val pagePath = Regex("^(.*?)/(?:page|pagina)/(\\d+)$", RegexOption.IGNORE_CASE).find(path)
        if (pagePath != null) {
            val next = (pagePath.groupValues[2].toIntOrNull() ?: 1) + 1
            val prefix = pagePath.groupValues[1].ifBlank { "" }
            return uri.scheme + "://" + uri.rawAuthority + prefix + "/page/" + next + "/"
        }
        if (path == "/") return uri.scheme + "://" + uri.rawAuthority + "/page/2/"
        if (path.startsWith("/genero/") || path.startsWith("/generos/") ||
            path.startsWith("/genre/") || path.startsWith("/genres/") ||
            path.startsWith("/categoria/") || path.startsWith("/categorias/") ||
            path.startsWith("/tag/")) {
            return uri.scheme + "://" + uri.rawAuthority + path + "/page/2/"
        }
        null
    } catch (_: Exception) { null }

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

        fun resolveImageCandidate(rawValue: String, base: String): String? {
            val raw = rawValue.trim().removeSurrounding("\"").removeSurrounding("'")
            if (raw.isBlank() || raw.startsWith("data:", true) || raw.startsWith("blob:", true)) return null
            val resolved = runCatching { URI(base).resolve(URI(raw)).toString() }.getOrNull() ?: return null
            if (!resolved.startsWith("https://", true)) return null
            val lower = resolved.lowercase()
            if (listOf(
                    "placeholder", "blank.gif", "spacer", "transparent", "data:image",
                    "avatar", "favicon", "emoji", "sprite", "mascot", "/logo", "profile"
                ).any { lower.contains(it) }) return null
            return resolved
        }

        fun absoluteImage(img: Element?): String? {
            if (img == null) return null
            val classes = (img.className() + " " + img.id() + " " + img.attr("alt")).lowercase()
            if (listOf("avatar", "profile", "user-icon", "emoji", "favicon", "sprite", "mascot", "placeholder")
                    .any { classes.contains(it) }) return null

            // Sites often put covers in lazy-load attributes, a picture/source element,
            // or a CSS background instead of the normal img[src].
            val candidates = listOf(
                "data-src", "data-lazy-src", "data-original", "data-original-src",
                "data-lazyload", "data-lazy", "data-url", "data-image", "data-cover",
                "data-thumb", "data-thumbnail", "data-cfsrc", "data-echo",
                "data-ks-lazyload", "data-bg", "data-background",
                "data-srcset", "data-lazy-srcset", "srcset", "src"
            ).flatMap { attr ->
                val value = img.attr(attr).trim()
                if (value.isBlank()) emptyList() else if (attr.contains("srcset")) {
                    value.split(",").mapNotNull { part ->
                        part.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
                    }.reversed()
                } else listOf(value)
            }.distinct()

            return candidates.asSequence()
                .mapNotNull { resolveImageCandidate(it, img.baseUri()) }
                .firstOrNull()
        }

        fun imageFromElement(element: Element?): String? {
            if (element == null) return null
            if (element.normalName() == "img") return absoluteImage(element)
            val classes = (element.className() + " " + element.id() + " " + element.attr("alt")).lowercase()
            if (listOf("avatar", "profile", "emoji", "favicon", "sprite", "mascot", "placeholder")
                    .any { classes.contains(it) }) return null

            val attrs = listOf(
                "data-src", "data-lazy-src", "data-original", "data-original-src", "data-url",
                "data-image", "data-cover", "data-thumb", "data-thumbnail", "data-bg",
                "data-background", "data-srcset", "srcset", "src"
            )
            val attrCandidates = attrs.flatMap { attr ->
                val value = element.attr(attr).trim()
                if (value.isBlank()) emptyList() else if (attr.contains("srcset")) {
                    value.split(",").mapNotNull { part ->
                        part.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
                    }.reversed()
                } else listOf(value)
            }
            val cssCandidates = Regex("""url\((?:['"])?(.*?)(?:['"])?\)""", RegexOption.IGNORE_CASE)
                .findAll(element.attr("style"))
                .map { it.groupValues[1].trim() }
                .toList()
            return (attrCandidates + cssCandidates).asSequence()
                .mapNotNull { resolveImageCandidate(it, element.baseUri()) }
                .firstOrNull()
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
            val selector = "img,source,[style*=background],[data-bg],[data-background],[data-cover],[data-thumb],[data-thumbnail]"
            val candidates = (anchor.select(selector).toList() + card?.select(selector).orEmpty() + listOfNotNull(anchor, card))
                .distinctBy { it.outerHtml() }
            return candidates.mapNotNull { element ->
                val url = imageFromElement(element) ?: return@mapNotNull null
                val classes = (element.className() + " " + element.id() + " " + element.attr("alt")).lowercase()
                var score = 0
                if (classes.contains("cover") || classes.contains("thumb") || classes.contains("poster") ||
                    classes.contains("thumbnail") || classes.contains("featured-image")) score += 70
                if (classes.contains("manga") || classes.contains("comic") || classes.contains("book") ||
                    classes.contains("manhwa") || classes.contains("series")) score += 35
                if (element.attr("alt").isNotBlank()) score += 8
                if ((element.attr("width").toIntOrNull() ?: 0) >= 100) score += 12
                if ((element.attr("height").toIntOrNull() ?: 0) >= 150) score += 12
                if (listOf("logo", "avatar", "icon", "emoji", "profile", "mascot", "banner").any {
                        classes.contains(it) || url.contains(it, true)
                    }) score -= 100
                score to url
            }.maxByOrNull { it.first }?.second
        }

        fun cardFor(anchor: Element): Element? {
            val imageSelector = "img,source,[style*=background],[data-bg],[data-background],[data-cover],[data-thumb],[data-thumbnail]"
            return anchor.parents().take(12).firstOrNull { parent ->
                val classes = (parent.className() + " " + parent.id()).lowercase()
                val looksLikeCard = listOf("manga", "comic", "manhwa", "manhua", "book", "series", "swiper-slide", "post-item", "cover", "thumbnail", "entry-card", "item")
                    .any { classes.contains(it) } || parent.normalName() == "article" || parent.normalName() == "li"
                // NexusToons does not use one URL pattern for every work; count
                // the nearby card links instead of requiring href="/manga/...".
                val cardLinks = parent.select("a[href]").size
                val imageCount = parent.select(imageSelector).size
                looksLikeCard && cardLinks in 1..8 && imageCount in 1..8
            } ?: anchor.parents().take(9).firstOrNull { parent ->
                parent.select("a[href]").size in 1..8 &&
                    parent.select(imageSelector).isNotEmpty() &&
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

        val isCataloguePage = isCatalogueUrl(pageUrl)
        // Catalogue works may use /serie/, /obra/, slugs, or a theme-specific route.
        // Parse same-site anchors and let isContentLink/card checks reject menus and chapters.
        val candidateWorkAnchors = if (isCataloguePage) document.select("a[href]") else emptyList()
        val workEntries = candidateWorkAnchors
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
                    titleForAnchor(anchor),
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

        val nextPageUrl = document.select("a[rel=next],.pagination a.next,.nav-links a.next,a.page-numbers.next,a.load-more,.load-more a")
            .mapNotNull { it.absUrl("href").takeIf { link -> supports(link) && link.startsWith("https://") } }
            .firstOrNull()

        val detailsText = cleaned(document.text())
        val synopsis = document.selectFirst(
            ".manga-summary,.summary__content,.description-summary,.entry-content,.manga-description,[class*=synopsis],[class*=description]"
        )?.let { cleaned(it.text()) }.orEmpty()
        val coverUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.startsWith("https://") }
            ?: imageFromCard(document.selectFirst("a[href*='/manga/']") ?: document.body(), document.body())
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

        val pageImages: List<String> = if (readerLike) {
            val found = mutableListOf<String>()
            val pageSelectors = listOf(
                ".reading-content img", ".chapter-content img", ".chapter-images img",
                ".reader-area img", ".reader img", ".reading-detail img",
                ".wp-manga-chapter-img", ".page-break img", "#readerarea img",
                "#chapter_imgs img", "[class*=reader] img", "[class*=chapter] img",
                "[class*=page] img", "main img", "article img", "img"
            )
            for (selector in pageSelectors) {
                for (img in document.select(selector)) {
                    val classes = (img.className() + " " + img.id() + " " + img.attr("alt")).lowercase()
                    val url = absoluteImage(img).orEmpty()
                    val lowerUrl = url.lowercase()
                    val excluded = listOf("logo", "avatar", "icon", "emoji", "mascot", "sprite", "placeholder", "banner")
                        .any { classes.contains(it) || lowerUrl.contains(it) }
                    val likelyPage = classes.contains("chapter") || classes.contains("page") ||
                        classes.contains("reading") || classes.contains("reader") ||
                        classes.contains("wp-manga") || classes.contains("manga-page") ||
                        (img.attr("width").toIntOrNull() ?: 0) > 250 ||
                        (img.attr("height").toIntOrNull() ?: 0) > 350 ||
                        (opaqueReaderRoute && img.parents().none { parent ->
                            parent.normalName() in setOf("header", "nav", "footer", "aside")
                        })
                    if (!excluded && likelyPage && url.startsWith("https://") && !found.contains(url)) {
                        found.add(url)
                        if (found.size >= 150) break
                    }
                }
                if (found.size >= 150) break
            }
            found
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
