plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.retrolauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.retrolauncher"
        minSdk = 26 // Android 8.0; Retroid Pocket 5 runs Android 13 (API 33)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            // ./gradlew :app:testDebugUnitTest -PclubPage=/path/to/saved.html checks the parser on a real page.
            it.systemProperty("club.page", project.findProperty("clubPage") ?: "")
            // -PdiscFile=/path/to/game.chd checks the disc reader and PS1 hash on a real image.
            it.systemProperty("disc.file", project.findProperty("discFile") ?: "")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":catalogue"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.xz)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
