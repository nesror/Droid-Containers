plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cn.yzapp.androidcontainer.core.designsystem"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material3.windowsize)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.lifecycle.runtime.compose)
    api(project(":core:model"))
    api(project(":core:common"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
