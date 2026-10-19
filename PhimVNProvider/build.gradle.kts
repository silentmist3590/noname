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

    kotlinOptions {
        // Cập nhật cấu hình compilerOptions theo khuyến nghị của Kotlin mới
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                listOf(
                    "-opt-in=kotlin.RequiresOptIn"
                )
            )
        }
    }
}

cloudstream {
    // Đã sửa lại cú pháp thiếu dấu ngoặc kép ở cuối chuỗi
    setRepoUrl("https://github.com/silentmist3590/noname/builds/repo.json")
}

dependencies {
    val cloudstreamVersion = "master-SNAPSHOT"
    
    implementation("com.github.recloudstream:cloudstream:$cloudstreamVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.0")
}
