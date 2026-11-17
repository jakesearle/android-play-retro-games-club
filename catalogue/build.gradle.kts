plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.retrolauncher.catalogue"
    compileSdk = 36

    defaultConfig {
        // 26 covers Android 13 (API 33) devices such as the Retroid Pocket 5 with lots of headroom.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // ./gradlew :catalogue:testDebugUnitTest -PcatalogueLive=true checks every entry of the live catalogue.
            it.systemProperty("catalogue.live", project.findProperty("catalogueLive") ?: "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
