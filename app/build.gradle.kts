import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

apply(plugin = "com.google.gms.google-services")

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
        // GOOGLE_SERVER_CLIENT_ID is used by the Google Sign-In flow via Firebase Authentication.
        // No API_BASE_URL is needed: the app talks directly to Firebase Auth and Firestore.
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${setting("GOOGLE_SERVER_CLIENT_ID", "")}\"")
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
    implementation(libs.coil)
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
        if (setting("GOOGLE_SERVER_CLIENT_ID", "").isBlank()) errors += "Set GOOGLE_SERVER_CLIENT_ID to the Firebase/Google web client ID."
        if (!signingFile.exists()) errors += "Configure keystore.properties with your private upload key."
        check(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
tasks.matching { it.name == "bundleRelease" }.configureEach { dependsOn("preflightRelease") }
