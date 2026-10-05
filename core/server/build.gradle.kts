plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.yzapp.androidcontainer.core.server"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    // 渠道维度（与 app/core:billing 配对，全链路声明以保证变体匹配）
    flavorDimensions += "channel"
    productFlavors {
        create("global") { dimension = "channel" }
        create("cn") { dimension = "channel" }
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
    implementation(project(":core:data"))
    // 渠道差异（DistributionChannel）：cn 渠道不下发收费模板
    implementation(project(":core:billing"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.ktor.server.core)
    api(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    // 终端 WebSocket 通道（/api/v1/containers/{id}/terminal）
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
