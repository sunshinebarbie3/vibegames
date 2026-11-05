// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// Android Gradle plugin 9.x provides built-in Kotlin support: the
// `org.jetbrains.kotlin.android` plugin is intentionally *not* applied anywhere.
// The Compose compiler plugin and KSP are still required and versioned here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
