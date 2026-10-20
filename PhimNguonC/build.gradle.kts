version = 26

cloudstream {
    description = "Xem phim từ nguồn phim.nguonc.com trên CloudStream"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama")
    requiresResources = false
    language = "vi"
    iconUrl = "https://phim.nguonc.com/favicon.ico"
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
