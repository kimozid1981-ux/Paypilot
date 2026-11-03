// All plugins are declared once here so every module shares one Kotlin plugin classloader.
// Local builds without Google Maven use build-core.gradle.kts instead (-PcoreOnly=true).
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
}
