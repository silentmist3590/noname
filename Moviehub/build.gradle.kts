// Tăng số này mỗi lần cập nhật để CloudStream nhận bản mới
version = 1

cloudstream {
    description = "MoviesHubHD (movieshubhd.com): danh mục TMDB + stream trực tiếp từ mạng player VidNest (Hindi/English, đa âm thanh, phụ đề)"
    authors = listOf("silentmist3590")

    /**
     * Status int:
     * 0: Down | 1: Ok | 2: Slow | 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=movieshubhd.com&sz=%size%"
}
