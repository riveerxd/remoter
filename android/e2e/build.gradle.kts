plugins {
    alias(libs.plugins.android.test)
}

// The device e2e suite. A separate test APK that drives the app's e2e variant
// through UI Automator, so nothing of it is compiled into the app itself.
android {
    namespace = "me.river.remoter.e2e.suite"
    compileSdk = 37
    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    // Runs in its own process and drives the app from outside, so the two never share
    // a classpath and the suite needs neither the app's signing key nor its internals.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("e2e") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Only the e2e variant exists here; a debug variant would target the fixture app.
androidComponents {
    beforeVariants { it.enable = it.buildType == "e2e" }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
