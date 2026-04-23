plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.kanade.tachiyomi.extension.stubs"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
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
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("androidx.preference:preference-ktx:1.2.0")
    // Required for HttpSource stub method signatures (Request, Response, OkHttpClient).
    // compileOnly here — the stub module is itself compileOnly in :app, so OkHttp is
    // not double-bundled; the app's own implementation dependency supplies it at runtime.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
}
