package com.phimvn

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PhimVNProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "PhimVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/films/phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "$mainUrl/api/films/danh-sach/phim-bo" to "Phim Bộ",
        "$mainUrl/api/films/danh-sach/phim-le" to "Phim Lẻ",
        "$mainUrl/api/films/danh-sach/hoat-hinh" to "Hoạt Hình"
    )

    private fun getOriginFromUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}?page=$page"
        val res = app.get(url).parsedSafe<NguonCPageResponse>()
        val list = res?.items?.mapNotNull { toSearchResponse(it) } ?: emptyList()
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val res = app.get(url).parsedSafe<NguonCPageResponse>()
        return res?.items?.mapNotNull { toSearchResponse(it) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url).parsedSafe<NguonCDetailResponse>()
        val movie = res?.movie ?: throw ErrorLoadingException("Không thể tải thông tin phim")

        val episodes = movie.episodes?.flatMap { server ->
            server.items?.map { ep ->
                Episode(
                    data = ep.embedUrl ?: "",
                    name = "Tập ${ep.name}",
                    episode = ep.name?.toIntOrNull()
                )
            } ?: emptyList()
        } ?: emptyList()

        return if (episodes.size <= 1) {
            newMovieLoadResponse(movie.name ?: "", url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = movie.posterUrl ?: movie.thumbUrl
                this.plot = movie.description
            }
        } else {
            newTvSeriesLoadResponse(movie.name ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = movie.posterUrl ?: movie.thumbUrl
                this.plot = movie.description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        handler: PlaylistUtils,
        videoCallback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val embedUrl = data
        val embedOrigin = getOriginFromUrl(embedUrl)

        // 1. Tải trang Embed HTML
        val embedDoc = Jsoup.parse(
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Referer" to mainUrl
                )
            ).text
        )

        // 2. Lấy endpoint API từ thẻ <script id="stream-bootstrap">
        val bootstrapScript = embedDoc.selectFirst("script#stream-bootstrap")
        val bootstrapApi = bootstrapScript?.attr("src") 
            ?: bootstrapScript?.data() 
            ?: bootstrapScript?.text() 
            ?: return false

        val fullBootstrapApi = if (bootstrapApi.startsWith("http")) bootstrapApi else "$embedOrigin$bootstrapApi"

        // 3. POST JSON tới API với Origin là gốc embed và Referer là link embed
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val postBody = "{}".toRequestBody(jsonMediaType)

        val bootstrapResponse = app.post(
            fullBootstrapApi,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Origin" to embedOrigin,
                "Referer" to embedUrl,
                "Content-Type" to "application/json"
            ),
            requestBody = postBody
        )

        if (bootstrapResponse.code == 403) {
            return false // Lỗi wrong_origin hoặc Cloudflare HTTP/1.1 block
        }

        val encryptedBytes = bootstrapResponse.body.bytes()
        if (encryptedBytes.isEmpty()) return false

        // 4. Giải mã AES-GCM
        // Key = SHA-256("stream-bootstrap-v1\n" + api)
        val keySeed = "stream-bootstrap-v1\n$bootstrapApi"
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha256.digest(keySeed.toByteArray(Charsets.UTF_8))
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // Giả định 12 bytes đầu là IV (Standard AES-GCM IV)
        val iv = encryptedBytes.copyOfRange(0, 12)
        val cipherText = encryptedBytes.copyOfRange(12, encryptedBytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedSignedPlaylistUrl = String(cipher.doFinal(cipherText), Charsets.UTF_8)

        // 5. Tải Playlist đã ký và giải mã bằng HMAC-SHA256 nếu cần
        val playlistResponse = app.get(
            decryptedSignedPlaylistUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Origin" to embedOrigin,
                "Referer" to embedUrl
            )
        )

        var finalM3u8Url = decryptedSignedPlaylistUrl

        // Nếu playlist trả về nội dung mã hóa HMAC-SHA256
        if (!playlistResponse.text.startsWith("#EXTM3U")) {
            val videoHash = embedUrl.substringAfterLast("/").substringBefore("?")
            val hmac = Mac.getInstance("HmacSHA256")
            val hmacKey = SecretKeySpec(videoHash.toByteArray(Charsets.UTF_8), "HmacSHA256")
            hmac.init(hmacKey)
            
            // Giải mã Playlist
            val decryptedPlaylist = String(hmac.doFinal(playlistResponse.body.bytes()), Charsets.UTF_8)
            
            // Đưa vào ExoPlayer dạng M3U8 trực tiếp
            M3u8Helper.generateM3u8(
                name = this.name,
                m3u8Url = decryptedSignedPlaylistUrl,
                referer = embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Origin" to embedOrigin,
                    "Referer" to embedUrl
                )
            ).forEach(videoCallback)
            return true
        }

        // 6. Trả luồng m3u8 chuẩn cho ExoPlayer
        M3u8Helper.generateM3u8(
            name = this.name,
            m3u8Url = finalM3u8Url,
            referer = embedUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Origin" to embedOrigin,
                "Referer" to embedUrl
            )
        ).forEach(videoCallback)

        return true
    }

    private fun toSearchResponse(item: NguonCItem): SearchResponse? {
        val title = item.name ?: return null
        val slug = item.slug ?: return null
        val detailUrl = "$mainUrl/api/films/detail/$slug"

        return newMovieSearchResponse(title, detailUrl, TvType.Movie) {
            this.posterUrl = item.posterUrl ?: item.thumbUrl
        }
    }

    data class NguonCPageResponse(@JsonProperty("items") val items: List<NguonCItem>? = null)
    data class NguonCItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null
    )
    data class NguonCDetailResponse(@JsonProperty("movie") val movie: NguonCMovieDetail? = null)
    data class NguonCMovieDetail(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("episodes") val episodes: List<NguonCServer>? = null
    )
    data class NguonCServer(@JsonProperty("server_name") val serverName: String? = null, @JsonProperty("items") val items: List<NguonCEpisode>? = null)
    data class NguonCEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("embed") val embedUrl: String? = null,
        @JsonProperty("m3u8") val m3u8Url: String? = null
    )
}
