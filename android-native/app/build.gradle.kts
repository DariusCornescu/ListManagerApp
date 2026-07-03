import org.gradle.kotlin.dsl.implementation
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    kotlin("plugin.serialization") version "2.0.0"
}

android {
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val secretsProps = Properties().apply {
        val f = project.file("secrets.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun prop(key: String): String? = secretsProps.getProperty(key) ?: localProps.getProperty(key)
    fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    // Configure at build-time (NOT committed). Set one of:
    // - android-native/app/secrets.properties  (recommended)
    // - android-native/local.properties
    // Keys:
    //   LISTMANAGER_BASE_URL=http://192.168.x.x:8000/
    //   LISTMANAGER_BASE_URL=http://10.0.2.2:8000/   (emulator)
    //   LISTMANAGER_BASE_URL=https://listmanager-api.onrender.com/
    val baseUrl = ensureTrailingSlash(
        prop("LISTMANAGER_BASE_URL") ?: "https://listmanager-api.onrender.com/"
    )

    namespace = "com.darius.listmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.darius.listmanager.v2"
        minSdk = 34
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Expose base URL to app code without committing IPs
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // extra dependencies
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")


    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)


    // Room dependencies
    implementation("androidx.room:room-runtime:2.8.1")
    implementation("androidx.room:room-ktx:2.8.1")
    ksp("androidx.room:room-compiler:2.8.1")

    // On-device sentence embeddings (ONNX Runtime + HF tokenizer)
    implementation("io.gitlab.shubham0204:sentence-embeddings:0.0.6")

    // Coroutines (if not already added)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Lifecycle (for ViewModels later)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // Security for encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

}