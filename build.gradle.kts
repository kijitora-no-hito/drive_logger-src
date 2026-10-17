// Top-level build file. Plugins are declared here and applied in module build files.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Maps API キーを local.properties から Manifest プレースホルダへ流し込む。
    alias(libs.plugins.secrets) apply false
}