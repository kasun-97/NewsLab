import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes from an untracked keystore.properties at the repo root
// (storeFile, storePassword, keyPassword, keyAlias). Without it, assembleRelease
// still works and produces an unsigned APK, so clones and CI build as before.
val keystoreProperties = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.kasun.newslab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kasun.newslab"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        // Release code, installable locally: same R8 output and non-debuggable, but
        // signed with the debug key so it can be measured on a device. Judging
        // smoothness from a debug build is misleading — Compose keeps composition
        // tracking on and skips R8 there, so it janks in ways the shipped app won't.
        create("profiling") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // The exported Room schemas, read as assets by MigrationTestHelper in unit tests.
    // Debug, not test: AGP doesn't merge the test source set's assets for local unit
    // tests, and Robolectric reads only the debug variant's merged assets. Release and
    // profiling builds don't carry them.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
}

// Room writes one JSON schema per database version here. Commit each one: the
// migration test validates the upgraded database against them.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.jsoup)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
