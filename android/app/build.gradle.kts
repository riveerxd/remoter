import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.river.remoter"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.river.remoter"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The e2e build type's own key, generated locally into android/e2e-keystore
    // (gitignored). It is never the release key, and production refuses it:
    // attestation pins the release certificate digest.
    val e2eProps = Properties().apply {
        rootProject.file("e2e-keystore/e2e.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        create("e2e") {
            storeFile = rootProject.file("e2e-keystore/" + (e2eProps.getProperty("storeFile") ?: "e2e.jks"))
            storePassword = e2eProps.getProperty("storePassword")
            keyAlias = e2eProps.getProperty("keyAlias")
            keyPassword = e2eProps.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // left unsigned here, the release key never lives in this repo
        }
        // fixture backend with an instant fake finger: the profile generator and
        // benchmarks can't get past a fingerprint prompt
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        // Real crypto and network against the staging laptop, with a signing key
        // that needs no finger so UI tests can drive every flow (KeySpecs.policyFor).
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".e2e"
            signingConfig = signingConfigs.getByName("e2e")
            matchingFallbacks += listOf("debug")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED") }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    // AGP only builds host tests for the test build type unless told to; the
    // release and e2e policy tests must run against their own source sets.
    beforeVariants { b ->
        if (b.buildType == "release" || b.buildType == "e2e") {
            (b as com.android.build.api.variant.HasHostTestsBuilder)
                .hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
        }
    }
    onVariants { v ->
        // Fixture builds never talk to a laptop; release and e2e carry the real backend.
        val bt = v.buildType.orEmpty().lowercase()
        val fixtures = bt == "debug" || bt.endsWith("benchmark") && !bt.endsWith("release")
        v.sources.kotlin?.addStaticSourceDirectory(file(if (fixtures) "src/fixtures/kotlin" else "src/real/kotlin").path)
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:crypto"))
    implementation(project(":core:net"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:session"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    "benchmarkImplementation"(project(":core:testing"))
    debugImplementation(project(":core:testing"))
    // root JVM tests use the fixture backend in every variant; test classpath only
    testImplementation(project(":core:testing"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)


    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":core:testing"))
}

// The e2e relaxation must never reach a release build. Its source set holds the only copy of
// SigPolicy.MARKER; this scans the compiled classes of both types and fails if release has it,
// or if e2e lost it (which would mean the scan itself no longer proves anything).
val checkReleaseHasNoE2e by tasks.registering {
    val marker = "remoter-e2e-relaxed-signing".toByteArray()
    val release = files(tasks.named("compileReleaseKotlin").map { it.outputs.files })
    val e2e = files(tasks.named("compileE2eKotlin").map { it.outputs.files })
    inputs.files(release, e2e)
    doLast {
        fun hits(fc: FileCollection) = fc.asFileTree.files.filter { it.extension == "class" }.filter { f ->
            val b = f.readBytes()
            (0..b.size - marker.size).any { i -> marker.indices.all { b[i + it] == marker[it] } }
        }.map { it.name }
        val r = hits(release)
        check(release.asFileTree.files.any { it.extension == "class" }) { "no release classes scanned" }
        check(r.isEmpty()) { "e2e code in release classes: $r" }
        check(hits(e2e).isNotEmpty()) { "marker missing from e2e classes, the check proves nothing" }
        logger.lifecycle("release classes: no e2e marker. e2e classes: marker present.")
    }
}
tasks.matching { it.name == "check" || it.name == "assembleRelease" }.configureEach { dependsOn(checkReleaseHasNoE2e) }
// The shared app tests need the fixture backend, which only debug has; release and e2e
// host runs are for their own policy tests.
tasks.withType<Test>().matching { it.name == "testReleaseUnitTest" || it.name == "testE2eUnitTest" }.configureEach {
    filter.includeTestsMatching("me.river.remoter.SigPolicy*")
}
