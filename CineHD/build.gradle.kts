plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.lagradost.cinehd" // hoặc "lagradost.cinehd" tùy theo package bạn chọn

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

cloudstream {
    // 1. Nhập trực tiếp String, KHÔNG bọc trong provider { }
    setRepo("https://github.com/silentmist3590/noname")
    description = "CineHD - Stream Movies & Series from cinehd.vc & cinehd.dev (VidRock GCM, Nxsha Nitro, VidLove)"
    authors = listOf("CineHD")
    
    // 2. Dùng số nguyên Int: 1 = Hoạt động tốt (OK)
    status = 1
}

dependencies {
    val cloudstreamVersion = "pre-release"
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
