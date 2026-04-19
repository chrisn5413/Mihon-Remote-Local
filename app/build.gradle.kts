plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "eu.kanade.tachiyomi.extension"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.cloud"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.4.1"

        val clientId = findProperty("driveClientId")?.toString() ?: ""
        val clientSecret = findProperty("driveClientSecret")?.toString() ?: ""
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"$clientSecret\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
    }
}

dependencies {
    // Provided by Mihon at runtime — stubs are in src/main/kotlin for the tachiyomi/injekt APIs.
    // RxJava and OkHttp are on Maven Central so kept as real compileOnly deps.
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")

    // Bundled into the extension APK
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
