package com.botol

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

class Astro : MainAPI() {
    override var mainUrl = "https://astro.com.my"
    override var name = "Astro"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = listOf(
            newAnimeSearchResponse("300 IQIYI", "300.astro", TvType.Live) {
                this.posterUrl = "http://linear-poster.astro.com.my/prod/logo/IQIYI_2022.png"
            }
        )
        return newHomePageResponse("Live TV", items, true)
    }

    override suspend fun load(url: String): LoadResponse {
        val title = "300 IQIYI"
        val poster = "http://linear-poster.astro.com.my/prod/logo/IQIYI_2022.png"
        
        val episodes = listOf(
            newEpisode("300.astro") {
                this.name = "Live Stream"
                this.posterUrl = poster
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.Live, episodes) {
            this.posterUrl = poster
            this.plot = "Astro Live Channel 300 IQIYI (ClearKey Demo)"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data == "300.astro") {
            val streamUrl = "https://linearjitp-playback.astro.com.my/dash-wv/linear/1006/default_primary.mpd"
            val kid = "7ef7e913ce85a1131b27036069169a10"
            val key = "77d98ed71db7524c27875a09a975f9e6"
            val ua = "Mozilla/5.0 (Linux; Android 13; UltraBox Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/136.0.7103.61 Mobile Safari/537.36"

            callback.invoke(
                newDrmExtractorLink(
                    name,
                    "Astro Stream",
                    streamUrl,
                    ExtractorLinkType.DASH,
                    UUID.randomUUID()
                ) {
                    this.kid = kid
                    this.key = key
                    this.headers = mapOf("User-Agent" to ua)
                }
            )
            return true
        }
        return false
    }
}
