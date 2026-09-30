plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.moronigranja.maliprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.moronigranja.maliprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /* The cases are the game's own shader text, expanded the engine's way - generated from a local
     * install by `make-cases.py` into `cases/` (git-ignored: they are game files) and packed into the
     * **test** APK, so the app APK carries nothing of the game. */
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("cases"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
