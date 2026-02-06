package com.horis.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object NetflixSubtitleHelper {
    private const val secondAPIUrl = "https://filmboom.top"

    private data class Media(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf(),
        ) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
            )

            data class Captions(
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null,
            )
        }
    }

    private data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
    )

    suspend fun getSubtitles(
        title: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val searchResponse = app.post(
                "$secondAPIUrl/wefeed-h5-bff/web/subject/search", requestBody = mapOf(
                    "keyword" to title,
                    "page" to "1",
                    "perPage" to "20",
                    "subjectType" to "0",
                ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            ).parsedSafe<Media>()

            val subjectId = searchResponse?.data?.items?.firstOrNull()?.subjectId ?: return

            val playResponse = app.get(
                "$secondAPIUrl/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=${season ?: 0}&ep=${episode ?: 0}"
            ).parsedSafe<Media>()

            val id = playResponse?.data?.streams?.firstOrNull()?.id ?: return
            val format = playResponse?.data?.streams?.firstOrNull()?.format ?: return

            app.get(
                "$secondAPIUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            ).parsedSafe<Media>()?.data?.captions?.forEach { subtitle ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        "[Moviebox] ${subtitle.lanName ?: "Unknown"}",
                        subtitle.url ?: return@forEach
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
