version = 4

cloudstream {
    description = "Xem phim từ API công khai của vsmov.com"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama")
    requiresResources = false
    language = "vi"
    iconUrl = "https://vsmov.com/VSmov.png"
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
