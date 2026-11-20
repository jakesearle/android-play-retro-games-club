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

/**
 * Copies the licence and third-party notices into the app's assets, so Settings › About can
 * show them: the libchdr port's BSD licence requires its notice to ship with the app.
 */
abstract class NoticesTask : DefaultTask() {
    @get:InputFiles abstract val notices: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        notices.files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

val notices = tasks.register<NoticesTask>("bundleNotices") {
    notices.from(rootProject.file("LICENSE"), rootProject.file("THIRD-PARTY-NOTICES.md"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(notices, NoticesTask::outputDir) }
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
    implementation(libs.commons.compress) // reading .7z ROMs
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
