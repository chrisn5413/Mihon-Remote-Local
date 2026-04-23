import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Load local.properties (not picked up by findProperty automatically — that only reads
// gradle.properties and -P CLI flags). Fall back to gradle.properties for CI usage.
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}
fun localOrGradle(key: String) =
    localProps.getProperty(key) ?: findProperty(key)?.toString() ?: ""

android {
    namespace = "eu.kanade.tachiyomi.extension"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.remotelibrary"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.4.1"

        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${localOrGradle("driveClientId")}\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"${localOrGradle("driveClientSecret")}\"")
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
    // Tachiyomi/injekt stubs — compile-only, not bundled in APK
    compileOnly(project(":stubs"))
    // Provided by Mihon at runtime in the source classloader — compile-only there.
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("androidx.preference:preference-ktx:1.2.0")

    // OkHttp is compile-only: the extension extends HttpSource, whose abstract method
    // signatures reference okhttp3.Request / okhttp3.Response / okhttp3.OkHttpClient.
    // We must compile against those types, but we must NOT bundle OkHttp in the DEX.
    // Mihon uses ChildFirstPathClassLoader; bundling a second copy would cause two distinct
    // okhttp3.Request class objects, and the JVM verifier would reject RemoteLibrarySource
    // with a LinkageError at class-load time.
    // At runtime in Mihon's process, okhttp3.* resolves from Mihon's classloader —
    // identical to what HttpSource was compiled against — so no conflict occurs.
    // All actual HTTP work in this extension uses java.net.HttpURLConnection instead.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    // Bundled into the extension APK
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.android.gms:play-services-auth:21.0.0")
}
