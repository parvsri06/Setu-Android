import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The keystore lives outside this folder and
// keystore.properties is gitignored, so neither travels with the project.
// Without them the build falls back to debug signing, which is fine for local
// development and must never be used for an APK given to other people.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")
    ?.let { file(it).exists() } == true

android {
    namespace = "in.setu.relay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "in.setu.relay"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false   // minSdk 26 never needs JAR signing
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    androidResources {
        localeFilters += listOf("en", "hi", "bn", "as", "brx")
    }

    lint {
        // Assam is the first target, so a half-translated Assamese build is a
        // product defect, not a warning. The Diagnostics screen shipped in 1.0.1
        // rendering English under অসমীয়া is exactly what this gate now catches.
        error += setOf("MissingTranslation", "ExtraTranslation")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin/**")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Dependency versions are pinned deliberately: the whole point of the
        // build plan is a reproducible 1.5 MB APK, so "a newer version exists"
        // is noise here, and targetSdk 36 is mandated by CLAUDE.md.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi")
        // The battery-optimisation walkthrough is required by
        // docs/05-platform-constraints.md; OEM battery managers are the single
        // largest risk to this app working at all. Revisit before any Play
        // Store submission — see docs/08-build-plan.md.
        disable += "BatteryLife"
        // The app is not shipped as an App Bundle with language splits.
        disable += "AppBundleLocaleChanges"
        // mipmap-anydpi-v26 is required, not obsolete: <adaptive-icon> is an
        // API 26 element and AAPT2 rejects it in an unqualified anydpi folder.
        disable += "ObsoleteSdkInt"
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
