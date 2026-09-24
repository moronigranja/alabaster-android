plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the keystore lives OUTSIDE the repo (~/.android/alabasterdawn-release.jks)
// and is wired through the gitignored android/keystore.properties. Clones (and CI) without that
// file just get the unsigned release build - signing is a local, manual publish step
// (tools/release.sh), never a repository secret.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties: Map<String, String> =
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile
            .readLines()
            .filter { it.contains('=') && !it.trimStart().startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    } else {
        emptyMap()
    }

// A distributed APK is a distributed copy of this project, so it carries the notices it is
// distributed under (MIT + the game/third-party attribution): assets/LICENSE and
// assets/NOTICE.md. Both come from the repo root - the single source of truth, one level above
// this Gradle root - rather than a second copy under app/src/main/assets that could drift.
val repoRoot = rootProject.file("..")
val noticeAssetsDir = layout.buildDirectory.dir("generated/noticeAssets")
val copyNoticeAssets =
    tasks.register<Copy>("copyNoticeAssets") {
        from(repoRoot.resolve("LICENSE"))
        from(repoRoot.resolve("NOTICE.md"))
        into(noticeAssetsDir)
    }

android {
    namespace = "io.github.moronigranja.alabasterdawn"
    compileSdk = 36

    // assets/LICENSE + assets/NOTICE.md (above), merged into every variant. The Android
    // SourceSet API rejects Provider instances, so the dir is resolved to a File here.
    sourceSets.getByName("main").assets.srcDir(noticeAssetsDir.get().asFile)

    defaultConfig {
        applicationId = "io.github.moronigranja.alabasterdawn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // Present only when keystore.properties exists (local publishing machines); other
        // clones just get the unsigned release build.
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getValue("storeFile"))
                storePassword = keystoreProperties.getValue("storePassword")
                keyAlias = keystoreProperties.getValue("keyAlias")
                keyPassword = keystoreProperties.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Unminified for 0.1.x: this app's own code is tiny, and the WebView
            // @JavascriptInterface surface is not worth shrinker risk for the bytes saved.
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// The assets srcDir above is a plain path, so the merge tasks need an explicit edge to the
// copy: preBuild covers the build as a whole, and the merge task itself (whose only input is
// that directory) is pinned by name so no variant can package before the notices land.
tasks.named("preBuild") { dependsOn(copyNoticeAssets) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyNoticeAssets) }

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")

    /* Unit tests only: a real org.json, because android.jar stubs it during local tests. */
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
