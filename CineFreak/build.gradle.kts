version = 4

cloudstream {
    description = "CineFreak catalog provider for CloudStream: homepage, search and detail pages"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama")
    requiresResources = false
    language = "en"
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
