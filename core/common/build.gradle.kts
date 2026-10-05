plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
