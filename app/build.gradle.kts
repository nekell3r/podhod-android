import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseProperties = rootProject.file(".local/signing/release.properties")
val releaseSigning = Properties().apply { if (releaseProperties.exists()) releaseProperties.inputStream().use { load(it) } }
val apiURL = local.getProperty("podhod.apiUrl", "https://api.example.com").trimEnd('/')
val robolectricSDK by configurations.creating
val prepareRobolectricSDK by tasks.registering(Copy::class) {
    from(robolectricSDK)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
require(URI(apiURL).let { it.scheme == "https" && !it.host.isNullOrEmpty() && it.userInfo == null && it.query == null && it.fragment == null })
android {
    namespace = "dev.podhod.training"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.podhod.training"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_URL", "\"$apiURL\"")
        manifestPlaceholders["appAuthRedirectScheme"] = "dev.podhod.training"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.srcDir("../catalog")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    signingConfigs {
        if (releaseProperties.exists()) create("localRelease") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            if (releaseProperties.exists()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.dependsOn(prepareRobolectricSDK)
                it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
                it.systemProperty("robolectric.offline", "true")
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    robolectricSDK("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
