version = 7

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
    implementation("com.github.darkryh:Cloudflare-Bypass:0.0.5")
}
