plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.cinehd"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
    }
}

cloudstream {
    // Tên hiển thị và mô tả của extension
    setRepo(provider { "https://github.com/YourUsername/Cloudstream-CineHD" })
    description = "CineHD (cinehd.vc / cinehd.dev) - Stream Movies & TV Series with multiple servers (VidRock GCM, Nxsha Nitro, VidLove M3U8)."
    authors = listOf("CineHD")
    status = com.lagradost.cloudstream3.gradle.CloudstreamExtension.Status.Down
}

dependencies {
    val cloudstreamVersion = "pre-release" // hoặc phiên bản build hiện tại
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
