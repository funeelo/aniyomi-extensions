package eu.kanade.tachiyomi.multisrc.dopeflix

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.videostrextractor.VideoStrExtractor
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.SourcesResponse
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoData
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.LazyMutable
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.getPreferencesLazy
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.hours

abstract class DopeFlix(
    override val name: String,
    override val lang: String,
    private val megaCloudApi: String,
    private val domainList: List<String>,
    private val defaultDomain: String = domainList.first(),
    override val supportsLatest: Boolean = true,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    private val preferences by getPreferencesLazy {
        clearOldHosts()
    }

    private var docHeaders by LazyMutable {
        newHeaders()
    }

    override var baseUrl by LazyMutable { preferences.domainUrl }

    private val cacheControl by lazy { CacheControl.Builder().maxAge(6.hours).build() }

    private fun newHeaders(): Headers {
        return headers.newBuilder().apply {
            add(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            add("Host", baseUrl.toHttpUrl().host)
            add("Referer", "$baseUrl/")
        }.build()
    }

    // ============================== Popular ===============================

    protected open val filmSelector by lazy { "div.flw-item" }

    protected open fun entrySelector(type: String) =
        "section.block_area:has(h2.cat-heading:contains($type)) $filmSelector"

    override fun popularAnimeSelector() = entrySelector("Movies")

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return client.newCall(popularAnimeRequest(page))
            .awaitSuccess().use { response ->
                popularAnimeParse(response).let { animePage ->
                    if (page == 1) {
                        AnimesPage(
                            animes = animePage.animes,
                            hasNextPage = true,
                        )
                    } else {
                        animePage
                    }
                }
            }
    }

    override fun popularAnimeRequest(page: Int): Request {
        return when (page) {
            1 -> GET("$baseUrl/home/")
            else -> GET("$baseUrl/movie?page=${page - 1}")
        }
    }

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.attr("title")
        }
        thumbnail_url = element.selectFirst("img")!!.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[title=next]"

    // =============================== Latest ===============================

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return client.newCall(latestUpdatesRequest(page))
            .awaitSuccess().use { response ->
                latestUpdatesParse(response).let { animePage ->
                    if (page == 1) {
                        AnimesPage(
                            animes = animePage.animes,
                            hasNextPage = true,
                        )
                    } else {
                        animePage
                    }
                }
            }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return when (page) {
            1 -> GET("$baseUrl/home/")
            else -> GET("$baseUrl/tv-show?page=${page - 1}")
        }
    }

    override fun latestUpdatesSelector() = entrySelector("TV Shows")

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DopeFlixFilters.getSearchParameters(filters)

        val endpoint = if (query.isEmpty()) "filter" else "search"

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(endpoint)
            if (query.isNotBlank()) {
                addPathSegment(query.replace(" ", "-"))
            } else {
                addQueryParameter("type", params.type)
                addQueryParameter("quality", params.quality)
                addQueryParameter("release_year", params.releaseYear)
                addIfNotBlank("genre", params.genres)
                addIfNotBlank("country", params.countries)
            }
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = filmSelector

    override fun relatedAnimeListSelector() = filmSelector

    // ============================== Filters ===============================

    override fun getFilterList() = DopeFlixFilters.FILTER_LIST

    // =========================== Anime Details ============================

    protected open val detailInfoSelector = "div.detail_page-infor"

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst(detailInfoSelector)!!.run {
            thumbnail_url = selectFirst("div.film-poster img")!!.attr("src")
            author = getInfo(tag = "Production:", isList = true)
            genre = getInfo("Genre:", isList = true)

            val url = document.location()
            val type = if (url.contains("/tv/")) {
                status = SAnime.ONGOING
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                "TV Shows"
            } else {
                status = SAnime.COMPLETED
                update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                "Movies"
            }

            description = buildString {
                selectFirst(".description")
                    ?.let { append("${it.text().removePrefix("Overview:").trim()}\n\n") }
                append("**Type:** $type")
                getInfo("Country:", true, isList = true)?.let(::append)
                getInfo("Casts:", true, isList = true)?.let(::append)
                getInfo("Released:", true)?.let(::append)
                getInfo("Duration:", true)?.let(::append)

                getIMDBRating()?.let { append("\n**IMDB:** $it") }
                document.getTrailer()?.let { append("\n\n$it") }
                document.getCover()?.let { append("\n\n![Cover]($it)") }
            }
        }
    }

    protected open fun Document.getTrailer(): String? {
        return selectFirst("#iframe-trailer")?.let {
            val trailerUrl = it.attr("data-src")
            "[Trailer]($trailerUrl)"
        }
    }

    protected open fun Element.getIMDBRating(): Float? {
        return selectFirst("span:contains(IMDB)")?.text()
            ?.substringAfter("IMDB:")?.trim()?.toFloatOrNull()
    }

    protected open val coverUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    protected open val coverSelector by lazy { "div.cover_follow" }

    protected open fun Document.getCover(): String? {
        return selectFirst(coverSelector)?.let {
            val style = it.attr("style")
            coverUrlRegex.find(style)?.groupValues?.get(1)
        }
    }

    private fun Element.getInfo(
        tag: String,
        includeTag: Boolean = false,
        isList: Boolean = false,
    ): String? {
        val value = if (isList) {
            select("div.row-line:contains($tag) > a").eachText().joinToString().ifBlank { null }
        } else {
            selectFirst("div.row-line:contains($tag)")
                ?.ownText()?.ifBlank { null }
        }
        return if (includeTag && value != null) "\n**$tag** $value" else value
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = ".eps-item"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfterLast("-")
        if (anime.url.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    setUrlWithoutDomain("/ajax/episode/list/$id")
                    episode_number = 1F
                },
            )
        }

        val seasonRequest = GET("$baseUrl/ajax/season/list/$id", apiHeaders(baseUrl + anime.url))
        return client.newCall(seasonRequest).awaitSuccess().use { response ->
            response.asJsoup().select(".ss-item")
                .parallelFlatMap(::seasonFromElement).reversed()
        }
    }

    private suspend fun seasonFromElement(element: Element): List<SEpisode> = client.runCatching {
        val season = element.elementSiblingIndex() + 1
        val seasonId = element.attr("data-id")
        newCall(GET("$baseUrl/ajax/season/episodes/$seasonId", apiHeaders()))
            .awaitSuccess().use { response ->
                response.asJsoup().select(episodeListSelector()).map {
                    it.attr("data-season", "%02d".format(season)).let(::episodeFromElement)
                }
            }
    }.getOrElse {
        emptyList()
    }

    protected open val episodeRegex by lazy { """Episode (\d+)""".toRegex() }
    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        if (element.hasClass("link-item")) {
            val linkId = element.attr("data-linkid")
                .ifEmpty { element.attr("data-id") }
            setUrlWithoutDomain("/ajax/episode/sources/$linkId")
        } else {
            setUrlWithoutDomain("/ajax/episode/servers/" + element.attr("data-id"))
        }

        val ssNum = element.attr("data-season").ifEmpty { "0" }
        val title = element.attr("title").ifEmpty { element.text() }
        val epNum = episodeRegex.find(title)?.run { "%04d".format(groupValues[1].toInt()) } ?: "0"

        episode_number = "$ssNum.$epNum".toFloat()
        name = when {
            episode_number != 0.0F -> "S$ssNum E$epNum: ${title.substringAfter(": ")}"
            else -> title
        }
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = "a.link-item"

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl${episode.url}", apiHeaders())
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        client.newCall(videoListRequest(episode)).awaitSuccess().use { response ->

            val episodeReferer = response.request.header("referer")!!
            val hosterSelection = preferences.hostToggle

            val serversDoc = response.asJsoup()

            val embedLinks = serversDoc.select(videoListSelector()).parallelMapNotNull {
                val id = it.attr("data-linkid")
                    .ifEmpty { it.attr("data-id") }
                val name = it.select("span").text()

                if (hosterSelection.contains(name, true).not()) return@parallelMapNotNull null

                val link = client.newCall(
                    GET("$baseUrl/ajax/episode/sources/$id", apiHeaders(episodeReferer)),
                ).await().parseAs<SourcesResponse>().link ?: ""

                VideoData(link, name)
            }

            return embedLinks.parallelCatchingFlatMap(::extractVideo)
                .map { video ->
                    Video(
                        url = video.url,
                        quality = video.quality,
                        videoUrl = video.videoUrl,
                        headers = video.headers,
                        subtitleTracks = subLangOrder(video.subtitleTracks),
                        audioTracks = subLangOrder(video.audioTracks),
                    )
                }
        }
    }

    private var videoStrExtractor by LazyMutable { VideoStrExtractor(client, headers, megaCloudApi) }

    private fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "UpCloud", "MegaCloud", "Vidcloud", "AKCloud" -> videoStrExtractor.getVideosFromUrl(server.link, server.name)
            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

//    private fun getVideosFromServer(video: VideoDto, name: String): List<Video> {
//        val masterUrl = video.sources.first().file
//        val subs = video.tracks
//            ?.filter { it.kind == "captions" }
//            ?.mapNotNull { Track(it.file, it.label) }
//            ?.let(::subLangOrder)
//            ?: emptyList<Track>()
//        if (masterUrl.contains("playlist.m3u8")) {
//            return playlistUtils.extractFromHls(
//                masterUrl,
//                videoNameGen = { "$name - $it" },
//                subtitleList = subs,
//            )
//        }
//
//        return listOf(
//            Video(masterUrl, "$name - Default", masterUrl, subtitleTracks = subs),
//        )
//    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.prefQuality
        val server = preferences.prefServer
        val qualitiesList = PREF_QUALITY_LIST.reversed()

        return sortedByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
            .sortedWith(
                compareByDescending<Video> { it.quality.contains(quality) }
                    .thenByDescending { it.quality.contains(server, true) },
//                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            )
    }

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.prefSubtitle
        return tracks.sortedWith(
            compareByDescending { it.lang.contains(language) },
        )
    }

    // ============================== Settings ==============================

    private var SharedPreferences.domainUrl
        by LazyMutable { preferences.getString(PREF_DOMAIN_KEY, "https://$defaultDomain")!! }

    private var SharedPreferences.prefQuality
        by LazyMutable { preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!! }

    private var SharedPreferences.prefSubtitle
        by LazyMutable { preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!! }

    private var SharedPreferences.prefServer
        by LazyMutable { preferences.getString(PREF_SERVER_KEY, hosterNames.first())!! }

    private var SharedPreferences.hostToggle
        by LazyMutable { preferences.getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!! }

    private fun SharedPreferences.clearOldHosts(): SharedPreferences {
        val hostToggle = getStringSet(PREF_HOSTER_KEY, hosterNames.toSet()) ?: return this
        if (hostToggle.all { hosterNames.contains(it) }) {
            return this
        }

        edit()
            .remove(PREF_HOSTER_KEY)
            .putStringSet(PREF_HOSTER_KEY, hosterNames.toSet())
            .remove(PREF_SERVER_KEY)
            .putString(PREF_SERVER_KEY, hosterNames.first())
            .apply()
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = PREF_DOMAIN_TITLE,
            entries = domainList,
            entryValues = domainList.map { "https://$it" },
            default = "https://$defaultDomain",
            summary = "%s",
        ) {
            baseUrl = it
            preferences.domainUrl = it
            docHeaders = newHeaders()
            videoStrExtractor = VideoStrExtractor(client, headers, megaCloudApi)
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_LIST,
            entryValues = PREF_QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefQuality = it
        }

        screen.addListPreference(
            key = PREF_SUB_KEY,
            title = PREF_SUB_TITLE,
            entries = PREF_SUB_LANGUAGES,
            entryValues = PREF_SUB_LANGUAGES,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefSubtitle = it
        }

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.first(),
            summary = "%s",
        ) {
            preferences.prefServer = it
        }

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.toSet(),
        ) {
            preferences.hostToggle = it
        }
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean {
        return any { it.equals(s, ignoreCase) }
    }

    private fun apiHeaders(referer: String = "$baseUrl/"): Headers = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Host", baseUrl.toHttpUrl().host)
        add("Referer", referer)
        add("X-Requested-With", "XMLHttpRequest")
    }.build()

    companion object {
        private val hosterNames = listOf(
            "UpCloud",
            "MegaCloud",
            "Vidcloud",
            "AKCloud",
        )

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_LIST = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_LIST.first()

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private const val PREF_SUB_DEFAULT = "English"
        private val PREF_SUB_LANGUAGES = listOf(
            "Arabic", "English", "French", "German", "Hungarian",
            "Italian", "Japanese", "Portuguese", "Romanian", "Russian",
            "Spanish",
        )

        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_HOSTER_KEY = "hoster_selection"
    }
}
