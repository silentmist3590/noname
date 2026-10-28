version = 2

cloudstream {
    description = "Phim thuộc phạm vi công cộng từ Internet Archive (archive.org)"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie")
    requiresResources = false
    language = "en"
    iconUrl = "https://archive.org/favicon.ico"
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
