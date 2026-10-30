// Phiên bản plugin — tăng số này mỗi lần cập nhật để CloudStream nhận bản mới
version = 8

cloudstream {
    description = "Phim & TV Series từ moviesapi.to (TMDB / IMDb ID)"
    authors = listOf("silentmist3590")

    /**
     * Status int:
     * 0: Down | 1: Ok | 2: Slow | 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=moviesapi.to&sz=%size%"
}
