include(":PhimNguonC")
include(":InternetArchive")
include(":VsMov")
include(":Kkphim")
include(":NguonTV")
include(":PhimVNProvider")
include(":NgonTV")
include(":CineFreak")
include(":Movies321")
include(":CineHD")
include(":Phim321")
include(":CineJoy")
include(":MovieAPI")
include(":Hicine")
include(":Moviehub")
rootProject.name = "CloudstreamPlugins"

// settings.gradle.kts
pluginManagement {
    repositories {
        // ... các repo khác
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // Dòng quan trọng
    }
}
