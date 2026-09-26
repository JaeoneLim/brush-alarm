plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jaewon.brushalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jaewon.brushalarm"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.1"
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystorePath = System.getenv("BRUSH_ALARM_KEYSTORE_PATH")
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("BRUSH_ALARM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BRUSH_ALARM_KEY_ALIAS")
                keyPassword = System.getenv("BRUSH_ALARM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:face-mesh-detection:16.0.0-beta3")

    testImplementation("junit:junit:4.13.2")
}

val verifyNoNetworkPermissions by tasks.registering {
    group = "verification"
    description = "Fails if debug or release merged manifests grant network permissions."
    dependsOn("processDebugMainManifest", "processReleaseMainManifest")

    doLast {
        listOf("debug", "release").forEach { variant ->
            val taskName = "process${variant.replaceFirstChar(Char::uppercaseChar)}MainManifest"
            val manifest = layout.buildDirectory.file(
                "intermediates/merged_manifest/$variant/$taskName/AndroidManifest.xml",
            ).get().asFile
            check(manifest.isFile) { "Missing merged $variant manifest: $manifest" }
            val contents = manifest.readText()
            listOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
            ).forEach { permission ->
                check(permission !in contents) {
                    "$permission is present in the merged $variant manifest"
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoNetworkPermissions)
}
