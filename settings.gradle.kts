import org.gradle.api.initialization.resolve.RepositoriesMode

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

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google() // 👈 ĐÃ THÊM DÒNG NÀY
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
