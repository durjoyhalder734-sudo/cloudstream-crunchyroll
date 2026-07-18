package com.crunchyroll

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

class CrunchyrollProvider : MainAPI() {
    override var name = "Crunchyroll"
    override var mainUrl = "https://www.crunchyroll.com"
    override var lang = "en"
    override var hasMainPage = true
    override var hasSearch = true
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var hasQuickSearch = false
    override var icon = "https://www.crunchyroll.com/i/favicon.ico"

    companion object {
        private const val API_URL = "https://api.crunchyroll.com"
        private const val BETA_API_URL = "https://beta-api.crunchyroll.com"
        private const val CLIENT_ID = "cr_web"
        private const val CLIENT_SECRET = "a%2FEX...[YOUR_SECRET]" // আপনার অরিজিনাল ফাইলে যা আছে তাই থাকবে
        
        var accessToken: String? = null
    }

    private suspend fun getAnonymousToken(): String {
        val response = app.post(
            "$BETA_API_URL/auth/v1/token",
            headers = mapOf(
                "Authorization" to "Basic Y3Jfd2ViOg==",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            data = mapOf("grant_type" to "client_credentials")
        ).parsedSafe<TokenResponse>()
        return response?.access_token ?: ""
    }

    private suspend fun ensureToken(): String {
        if (accessToken != null) {
            return accessToken!!
        }
        accessToken = getAnonymousToken()
        return accessToken!!
    }

    data class TokenResponse(
        val access_token: String?
    )
}
