import com.lagradost.cloudstream3.gradle.CloudstreamExtension

plugins {
    val kotlinVersion = "1.9.22"
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
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
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}

cloudstream {
    // Nhập URL GitHub Repository của bạn tại đây
    setRepoUrl("https://github.com/silentmist3590/noname/builds/repo.json)
}

dependencies {
    val cloudstreamVersion = "master-SNAPSHOT"
    
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.0")
}
