plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.river.remoter.core.testing"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The fixture backend serves the same files the contract tests check, so the
// debug app shows what the daemon really sends.
androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addStaticSourceDirectory(rootProject.file("../daemon/remoter-proto/fixtures").path)
    }
}

dependencies {
    api(project(":core:net"))
    api(project(":core:crypto"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
    testImplementation(libs.junit)
}
