plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cn.yzapp.androidcontainer.core.data"
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

// Room schema JSON 导出目录（exportSchema = true 时 KSP 生成，入库供迁移 diff，
// 审查 P1-14：升级漏写迁移必须在构建期暴露，而不是运行时静默清库）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:engine"))
    api(project(":core:network"))
    // 权益仓库由 DataGraph 挂载；Play API 全部隔离在 core:billing 内
    api(project(":core:billing"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    // 容器 cmd/env 的库内 JSON 存储（M7）
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
