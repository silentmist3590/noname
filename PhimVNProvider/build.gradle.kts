version = 5

cloudstream {
    description = "Plugin PhimVN"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "vi"
}
dependencies {
    // ... các dependency hiện có
    implementation("com.github.darkryh:Cloudflare-Bypass:1.0.0")
}
