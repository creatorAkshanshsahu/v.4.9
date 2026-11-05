plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// A stable signing key is what lets an in-app update install over the previous build. The key itself is
// a sideload key (this app is distributed as an APK), not a Play Store upload key; the password is a
// build property so a fork can supply its own. Without the file the build falls back to the local debug
// key, which still produces an installable APK but cannot update an existing installation in place.
val updateKeystore = rootProject.file("keystore/aktv-update.p12")
val updateStorePassword = providers.gradleProperty("aktvUpdateStorePassword").orNull
    ?: System.getenv("AKTV_UPDATE_STORE_PASSWORD")
    ?: "aktv-update-key"
android {
    namespace = "com.aktv.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.akshansh.aktv"; minSdk = 28; targetSdk = 33; versionCode = 76; versionName = "4.9.59"
        resValue("string", "build_run_id", System.getenv("GITHUB_RUN_ID")?.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: "local")
    }
    signingConfigs {
        create("update") {
            if (updateKeystore.exists()) {
                storeFile = updateKeystore
                storePassword = updateStorePassword
                keyAlias = "aktv"
                keyPassword = updateStorePassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key for every CI build, so "Update" in the app replaces the installed copy.
            signingConfig = if (updateKeystore.exists()) signingConfigs.getByName("update") else signingConfigs.getByName("debug")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("aktv.ui.previewDir", project.layout.buildDirectory.dir("reports/ui-preview").get().asFile.absolutePath)
            it.systemProperty("aktv.live.tg", project.findProperty("liveTg")?.toString() ?: "false")
            it.systemProperty("aktv.live.frost", project.findProperty("liveFrost")?.toString() ?: "false")
            it.systemProperty("aktv.live.jio", project.findProperty("liveJio")?.toString() ?: "false")
        }
    }
    flavorDimensions += "abi"
    productFlavors { create("arm32") { dimension = "abi" }; create("arm64") { dimension = "abi" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.palette:palette:1.0.0")
    "arm32Implementation"("org.mozilla.geckoview:geckoview-armeabi-v7a:123.0.20240213221259")
    "arm64Implementation"("org.mozilla.geckoview:geckoview-arm64-v8a:123.0.20240213221259")
}
