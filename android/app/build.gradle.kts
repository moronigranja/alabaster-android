plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.moroni.alabasterdawn"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.moroni.alabasterdawn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")

    /* Unit tests only: a real org.json, because android.jar stubs it during local tests. */
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
