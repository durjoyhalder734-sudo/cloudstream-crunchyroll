package com.crunchyroll

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

class CrunchyrollProvider : MainAPI() {

    override var name = "Crunchyroll"
    override var mainUrl = "https://www.crunchyroll.com"
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val hasQuickSearch = false

    companion object {
        private const val API_URL = "https://api.crunchyroll.com"
        private const val BETA_API_URL = "https://beta-api.crunchyroll.com"
        private const val CLIENT_ID = "cr_web"
        private const val CLIENT_SECRET = "a%2FE%2FpnSe%2FGYUt5hS47GBzAfPaoh0fq2sHg%3D%3D"

        // Crunchyroll uses a token-based API. Users must be logged in.
        var accessToken: String? = null
        var tokenExpiry: Long = 0L
        var refreshToken: String? = null
        var accountAuthHeader: String? = null
    }

    // ─── Auth ────────────────────────────────────────────────────────────────

    private suspend fun getAnonymousToken(): String? {
        val response = app.post(
            "$BETA_API_URL/auth/v1/token",
            headers = mapOf(
                "Authorization" to "Basic Y3Jfd2ViOg==",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            data = mapOf("grant_type" to "client_id"),
        ).parsedSafe<TokenResponse>()
        return response?.access_token
    }

    private suspend fun ensureToken(): String {
        val now = System.currentTimeMillis() / 1000
        if (accessToken != null && tokenExpiry > now + 60) {
            return accessToken!!
        }
        // Attempt refresh if we have a refresh token
        if (refreshToken != null) {
            val resp = app.post(
                "$BETA_API_URL/auth/v1/token",
                headers = mapOf(
                    "Authorization" to "Basic Y3Jfd2ViOg==",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                data = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken!!,
                ),
            ).parsedSafe<TokenResponse>()
            if (resp?.access_token != null) {
                accessToken = resp.access_token
                tokenExpiry = now + (resp.expires_in ?: 300)
                refreshToken = resp.refresh_token ?: refreshToken
                return accessToken!!
            }
        }
        // Fall back to anonymous
        val token = getAnonymousToken() ?: throw ErrorLoadingException("Could not obtain Crunchyroll token")
        accessToken = token
        tokenExpiry = now + 300
        return token
    }

    private suspend fun authHeaders(): Map<String, String> {
        val token = ensureToken()
        return mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
        )
    }

    // ─── Login support ───────────────────────────────────────────────────────

    override suspend fun login(username: String, password: String): Boolean {
        val resp = app.post(
            "$BETA_API_URL/auth/v1/token",
            headers = mapOf(
                "Authorization" to "Basic Y3Jfd2ViOg==",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            data = mapOf(
                "grant_type" to "password",
                "username" to username,
                "password" to password,
                "scope" to "offline_access",
            ),
        ).parsedSafe<TokenResponse>() ?: return false
        if (resp.access_token == null) return false
        val now = System.currentTimeMillis() / 1000
        accessToken = resp.access_token
        tokenExpiry = now + (resp.expires_in ?: 300)
        refreshToken = resp.refresh_token
        return true
    }

    // ─── Main Page ───────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "crunchyroll_newlyadded" to "Recently Added",
        "crunchyroll_simulcast" to "Simulcast",
        "crunchyroll_popular" to "Popular",
        "crunchyroll_updated" to "Updated",
        "crunchyroll_featured" to "Featured",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sortParam = when (request.name) {
            "Recently Added" -> "newly_added"
            "Simulcast" -> "simulcast"
            "Updated" -> "updated"
            "Featured" -> "featured"
            else -> "popularity"
        }
        val locale = "en-US"
        val pageSize = 36
        val pageStart = (page - 1) * pageSize

        val url = "$BETA_API_URL/content/v2/discover/browse" +
            "?sort_by=$sortParam&type=series&locale=$locale" +
            "&n=$pageSize&start=$pageStart"

        val data = app.get(url, headers = authHeaders())
            .parsedSafe<BrowseResponse>()
            ?: return newHomePageResponse(request.name, emptyList())

        val items = data.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items, hasNextPage = items.size == pageSize)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$BETA_API_URL/content/v2/discover/search?q=${encode(query)}&n=30&type=series&locale=en-US"
        val data = app.get(url, headers = authHeaders()).parsedSafe<SearchResult>()
        return data?.data?.firstOrNull()?.items?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    // ─── Series / Movie load ─────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        // url is the series/movie ID stored in the data field
        val id = url.substringAfterLast("/")
        val seriesUrl = "$BETA_API_URL/content/v2/cms/series/$id?locale=en-US"
        val seasonsUrl = "$BETA_API_URL/content/v2/cms/series/$id/seasons?locale=en-US"

        val headers = authHeaders()
        val seriesResp = app.get(seriesUrl, headers = headers).parsedSafe<SeriesResponse>()
        val seasonsResp = app.get(seasonsUrl, headers = headers).parsedSafe<SeasonsResponse>()

        val series = seriesResp?.data?.firstOrNull() ?: return null
        val seasons = seasonsResp?.data ?: emptyList()

        val episodes = seasons.flatMap { season ->
            loadEpisodes(season.id)
        }

        val isMovie = series.series_launch_year != null && seasons.size == 1 &&
            (seasons.firstOrNull()?.episode_count ?: 0) == 1

        return if (isMovie) {
            newMovieLoadResponse(
                name = series.title,
                url = url,
                type = TvType.AnimeMovie,
                dataUrl = episodes.firstOrNull()?.data?.toString() ?: "",
            ) {
                this.posterUrl = series.images?.poster_tall?.firstOrNull()?.lastOrNull()?.source
                this.backgroundPosterUrl = series.images?.poster_wide?.firstOrNull()?.lastOrNull()?.source
                this.plot = series.description
                this.tags = series.keywords
                this.rating = null
                this.year = series.series_launch_year?.toIntOrNull()
            }
        } else {
            newAnimeLoadResponse(
                name = series.title,
                url = url,
                type = TvType.Anime,
            ) {
                this.posterUrl = series.images?.poster_tall?.firstOrNull()?.lastOrNull()?.source
                this.backgroundPosterUrl = series.images?.poster_wide?.firstOrNull()?.lastOrNull()?.source
                this.plot = series.description
                this.tags = series.keywords
                this.year = series.series_launch_year?.toIntOrNull()

                // Group episodes by season
                seasons.forEachIndexed { idx, season ->
                    val eps = episodes.filter { it.season_id == season.id }
                    addEpisodes(
                        if (idx == 0) DubStatus.Subbed else DubStatus.Dubbed,
                        eps.map { ep ->
                            newEpisode(ep.data ?: "") {
                                this.name = ep.title
                                this.episode = ep.episode_number?.toIntOrNull()
                                this.season = ep.season_number
                                this.posterUrl = ep.images?.thumbnail?.firstOrNull()?.lastOrNull()?.source
                                this.description = ep.description
                                this.runTime = ep.duration_ms?.div(60000)?.toInt()
                            }
                        }
                    )
                }
            }
        }
    }

    private suspend fun loadEpisodes(seasonId: String): List<EpisodeData> {
        val url = "$BETA_API_URL/content/v2/cms/seasons/$seasonId/episodes?locale=en-US"
        val resp = app.get(url, headers = authHeaders()).parsedSafe<EpisodesResponse>()
        return resp?.data?.map { it.copy(season_id = seasonId) } ?: emptyList()
    }

    // ─── Load links (streams) ────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeId = data
        val streamsUrl = "$BETA_API_URL/cms/v2{POLICY_PLACEHOLDER}/videos/$episodeId/streams?locale=en-US"

        // Get CMS access for streams
        val cmsTokenUrl = "$BETA_API_URL/index/v2"
        val headers = authHeaders()
        val cmsResp = app.get(cmsTokenUrl, headers = headers).parsedSafe<CmsTokenResponse>()
        val cms = cmsResp?.cms_web ?: return false

        val realStreamsUrl = "$BETA_API_URL/cms/v2${cms.bucket}/videos/$episodeId/streams" +
            "?Policy=${cms.policy}&Signature=${cms.signature}&Key-Pair-Id=${cms.key_pair_id}&locale=en-US"

        val streamsResp = app.get(
            realStreamsUrl,
            headers = mapOf("Authorization" to "Bearer ${ensureToken()}"),
        ).parsedSafe<StreamsResponse>() ?: return false

        // Add subtitles
        streamsResp.subtitles?.forEach { (lang, subtitle) ->
            subtitleCallback(
                SubtitleFile(
                    lang = subtitle.locale ?: lang,
                    url = subtitle.url ?: return@forEach,
                )
            )
        }

        // Add HLS streams (adaptive, prefer hardsub-less)
        val streams = streamsResp.streams?.adaptive_hls ?: streamsResp.streams?.vo_adaptive_hls ?: return false
        streams.forEach { (tag, stream) ->
            val url2 = stream.url ?: return@forEach
            val isHardSub = stream.hardsub_locale?.isNotEmpty() == true
            callback(
                ExtractorLink(
                    source = name,
                    name = if (isHardSub) "$name [HardSub ${stream.hardsub_locale}]" else "$name",
                    url = url2,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true,
                )
            )
        }

        return true
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun MediaItem.toSearchResult(): AnimeSearchResponse? {
        val id = this.id ?: return null
        val title = this.title ?: return null
        return newAnimeSearchResponse(
            name = title,
            url = "$mainUrl/series/$id",
            type = TvType.Anime,
        ) {
            this.posterUrl = images?.poster_tall?.firstOrNull()?.lastOrNull()?.source
                ?: images?.poster_wide?.firstOrNull()?.lastOrNull()?.source
        }
    }

    // ─── Data classes ─────────────────────────────────────────────────────────

    data class TokenResponse(
        val access_token: String?,
        val refresh_token: String?,
        val expires_in: Long?,
        val token_type: String?,
    )

    data class BrowseResponse(val data: List<MediaItem>?)
    data class SearchResult(val data: List<SearchCategory>?)
    data class SearchCategory(val `type`: String?, val items: List<MediaItem>?)

    data class MediaItem(
        val id: String?,
        val title: String?,
        val description: String?,
        val images: ImageSet?,
        val keywords: List<String>?,
        val series_launch_year: String?,
        val episode_count: Int?,
        val season_count: Int?,
    )

    data class ImageSet(
        val poster_tall: List<List<ImageSource>>?,
        val poster_wide: List<List<ImageSource>>?,
        val thumbnail: List<List<ImageSource>>?,
    )

    data class ImageSource(val source: String?, val width: Int?, val height: Int?)

    data class SeriesResponse(val data: List<MediaItem>?)

    data class SeasonsResponse(val data: List<SeasonData>?)
    data class SeasonData(
        val id: String,
        val title: String?,
        val season_number: Int?,
        val episode_count: Int?,
        val is_dubbed: Boolean?,
        val audio_locale: String?,
    )

    data class EpisodesResponse(val data: List<EpisodeData>?)
    data class EpisodeData(
        val id: String,
        val title: String?,
        val description: String?,
        val episode_number: String?,
        val season_number: Int?,
        val season_id: String,
        val duration_ms: Long?,
        val images: ImageSet?,
    ) {
        // data field holds the episode id for loadLinks
        val data: String get() = id
    }

    data class CmsTokenResponse(val cms_web: CmsData?)
    data class CmsData(
        val bucket: String?,
        val policy: String?,
        val signature: String?,
        val key_pair_id: String?,
    )

    data class StreamsResponse(
        val subtitles: Map<String, SubtitleData>?,
        val streams: StreamData?,
    )

    data class SubtitleData(val locale: String?, val url: String?, val format: String?)
    data class StreamData(
        val adaptive_hls: Map<String, HlsStream>?,
        val vo_adaptive_hls: Map<String, HlsStream>?,
    )

    data class HlsStream(
        val url: String?,
        val hardsub_locale: String?,
        val audio_locale: String?,
    )
}
