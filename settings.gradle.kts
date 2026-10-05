pluginManagement {
    repositories {
        maven { setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // ktor 3.6.0 部分构件（如 ktor-io-jvm）腾讯镜像缺失：整组固定走 Maven Central
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("io.ktor") }
        }
        maven { setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Android Container Master"

include(":app")
include(":core:model")
include(":core:common")
include(":core:designsystem")
include(":core:data")
include(":core:network")
include(":core:engine")
include(":core:billing")
include(":core:server")
include(":feature:dashboard")
include(":feature:images")
include(":feature:containers")
include(":feature:compose")
include(":feature:settings")
