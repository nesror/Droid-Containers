plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cn.yzapp.androidcontainer.core.network"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
}
