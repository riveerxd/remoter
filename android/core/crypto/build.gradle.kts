plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "me.river.remoter.core.crypto"
    compileSdk = 37
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // On the emulator, skip what only the S25 can answer; the phone run passes -Ps25=true.
        if (!project.hasProperty("s25")) testInstrumentationRunnerArguments["notAnnotation"] = "me.river.remoter.core.crypto.RequiresS25"
    }
    testOptions {
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:net"))
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.biometric)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
