import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
// Firebase is optional at compile time, never optional for live cloud operation.
// Add the real app/google-services.json to enable FCM. No fabricated credentials.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun setting(name: String, fallback: String): String =
    providers.gradleProperty(name).orElse(local.getProperty(name) ?: fallback).get()
val signingFile = rootProject.file("keystore.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }

android {
    namespace = "com.vibe.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vibe.app"
        minSdk = 24
        targetSdk = 36
        versionCode = setting("VIBE_VERSION_CODE", "3").toInt()
        versionName = setting("VIBE_VERSION_NAME", "2.0.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // The existing endpoint is deliberately unchanged. Override for your deployment.
        buildConfigField("String", "API_BASE_URL", "\"${setting("API_BASE_URL", "https://api.vibeapp.com/")}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${setting("GOOGLE_SERVER_CLIENT_ID", "")}\"")
        buildConfigField("boolean", "FCM_ENABLED", file("google-services.json").exists().toString())
    }
    signingConfigs {
        if (signingFile.exists()) create("upload") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("upload")
        }
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions.unitTests.isReturnDefaultValues = true
    lint { abortOnError = true; checkReleaseBuilds = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefresh)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work)
    implementation(libs.androidx.security)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging)
    implementation(libs.coil)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.auth)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("preflightRelease") {
    group = "verification"
    doLast {
        val errors = mutableListOf<String>()
        if (!file("google-services.json").exists()) errors += "Add app/google-services.json for FCM."
        if (setting("API_BASE_URL", "https://api.vibeapp.com/") == "https://api.vibeapp.com/") errors += "Set API_BASE_URL to the deployed HTTPS REST API."
        if (setting("GOOGLE_SERVER_CLIENT_ID", "").isBlank()) errors += "Set GOOGLE_SERVER_CLIENT_ID to the Firebase/Google web client ID."
        if (!signingFile.exists()) errors += "Configure keystore.properties with your private upload key."
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
tasks.matching { it.name == "bundleRelease" }.configureEach { dependsOn("preflightRelease") }
