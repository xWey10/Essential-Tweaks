// SPDX-License-Identifier: GPL-3.0-only
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "moe.xWey10.tweaks"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "moe.xWey10.tweaks"
        minSdk = 36
        targetSdk = 36
        versionCode = 23
        versionName = "0.3.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        warningsAsErrors = true
        // Deliberately pinned to the API 36 toolchain; update notices are not correctness checks.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
