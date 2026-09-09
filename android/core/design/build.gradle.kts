plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "me.river.remoter.core.design"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    buildFeatures { compose = true }
    testFixtures { enable = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Robolectric reaches into FileDescriptor internals, which JDK 21 closes by default.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            }
        }
    }
}

roborazzi {
    // Goldens live in the repo so verify compares against the reviewed images,
    // not whatever the last local run happened to produce.
    outputDir.set(file("src/test/snapshots"))
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api(libs.compose.ui.text)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui.tooling.preview)
    api(libs.kotlinx.collections.immutable)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)

    testFixturesImplementation(platform(libs.compose.bom))
    testFixturesImplementation(libs.compose.ui)
    testFixturesImplementation(libs.compose.foundation)
    testFixturesImplementation(libs.compose.ui.test.junit4)
    testFixturesImplementation(libs.roborazzi)
    testFixturesImplementation(libs.roborazzi.compose)
    testFixturesImplementation(libs.robolectric)
    testFixturesImplementation(libs.junit)
}
