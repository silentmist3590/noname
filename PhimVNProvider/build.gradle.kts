plugins {
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

    // Cú pháp compilerOptions DSL bắt buộc cho Kotlin mới
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }
}

cloudstream {
    // Sửa lại cú pháp gán thuộc tính repoUrl thay vì dùng hàm setRepoUrl
    repoUrl = "https://github.com/silentmist3590/noname/builds/repo.json"
}

dependencies {
    val cloudstreamVersion = "master-SNAPSHOT"
    
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.0")
}
