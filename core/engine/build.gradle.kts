plugins {
    alias(libs.plugins.android.library)
}

// ---------------------------------------------------------------------------
// 模板正文代码生成
//
// 内容源：`src/main/templates/<template-id>.yaml`（有高亮、有 YAML 语法校验、改内容不动 Kotlin）
// 产物：`build/generated/templateSources/kotlin/.../TemplateYaml.kt`
//
// 之所以选构建期生成而不是运行期读文件：正文最终要参与 `ComposeTemplates` 的静态目录，
// 生成常量既能保持零运行时开销与编译期可见性，又不必把内容写在 Kotlin raw string 里。
// ---------------------------------------------------------------------------
val templateSourceDir = layout.projectDirectory.dir("src/main/templates")
val generatedTemplateDir = layout.buildDirectory.dir("generated/templateSources/kotlin")

val generateTemplateSources = tasks.register("generateTemplateSources") {
    group = "build"
    description = "Generates TemplateYaml.kt from src/main/templates/*.yaml"

    // 配置期解析成 File，任务执行期不再触碰 project（兼容 configuration-cache）
    val inputDir = templateSourceDir.asFile
    val outputDir = generatedTemplateDir.get().asFile
    inputs.dir(inputDir).withPropertyName("templateYaml")
    outputs.dir(outputDir).withPropertyName("generatedKotlin")

    doLast {
        fun literal(content: String, bodyIndent: String, closingIndent: String): String {
            val escaped = content.replace("$", "\${'\$'}")
            if (!escaped.contains("\"\"\"")) {
                val body = escaped.split("\n").joinToString("\n") { line ->
                    if (line.isEmpty()) "" else bodyIndent + line
                }
                return "\"\"\"\n$body\n$closingIndent\"\"\".trimIndent()"
            }
            // 正文含三引号（YAML 里合法但会撑破 raw string）：退化为转义后的普通字面量
            return buildString {
                append('"')
                content.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '$' -> append("\\$")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        }

        val taxonomyId = "_taxonomy"
        val prepared = inputDir.walkTopDown()
            .filter { it.isFile && it.extension == "yaml" }
            .sortedBy { it.name }
            .map { file ->
                val id = file.nameWithoutExtension
                val isMetadata = id.startsWith("_")
                if (isMetadata && id != taxonomyId) {
                    throw GradleException("unknown metadata file \"${file.name}\": only $taxonomyId.yaml is supported")
                }
                val constName = if (isMetadata) "TAXONOMY" else id.uppercase().replace('-', '_')
                if (!isMetadata && !constName.matches(Regex("[A-Z][A-Z0-9_]*"))) {
                    throw GradleException("template id \"$id\" is not usable as a Kotlin constant name")
                }
                // 统一换行并去掉文件末尾换行，使生成结果与源文件正文逐字节一致
                val content = file.readText().replace("\r\n", "\n").removeSuffix("\n")
                if (content.isBlank()) {
                    throw GradleException("${file.name} is empty")
                }
                if (content.first().isWhitespace() || content.last().isWhitespace()) {
                    throw GradleException("${file.name} must not start or end with a blank line")
                }
                Triple(id, constName, content)
            }
            .toList()
        // 开源公开仓的 templates 目录可能整个为空（真实模板留在私有层）：
        // 容忍空目录与缺失 _taxonomy.yaml，生成空常量文件，保证独立构建可用（开源方案 §8-②）
        val hasTaxonomy = prepared.any { it.first == taxonomyId }
        val taxonomy = prepared.firstOrNull { it.first == taxonomyId }
            ?: Triple(taxonomyId, "TAXONOMY", "")
        val templates = prepared.filter { it.first != taxonomyId }

        val targetPackage = "cn.yzapp.androidcontainer.core.engine.compose"
        val outFile = outputDir.resolve(targetPackage.replace('.', '/') + "/TemplateYaml.kt")
        outFile.parentFile.mkdirs()

        val text = buildString {
            append("// 由 :core:engine:generateTemplateSources 从 src/main/templates/*.yaml 生成，请勿手改。\n")
            append("// 改模板内容请编辑对应 YAML 源文件，重新构建即可生效。\n\n")
            append("package $targetPackage\n\n")
            append("/** 模板文件原文（内容源：`core/engine/src/main/templates/`）。 */\n")
            append("internal object TemplateYaml {\n")
            append("\n    /** `templates/${taxonomy.first}.yaml` */\n")
            append("    val ${taxonomy.second}: String = ")
            append(literal(taxonomy.third, bodyIndent = "        ", closingIndent = "    "))
            append("\n")
            templates.forEach { (id, constName, content) ->
                append("\n    /** `templates/$id.yaml` */\n")
                append("    val $constName: String = ")
                append(literal(content, bodyIndent = "        ", closingIndent = "    "))
                append("\n")
            }
            append("\n    /** 模板 id（= 文件名）→ 文件原文。 */\n")
            append("    val TEMPLATES: Map<String, String> = mapOf(\n")
            templates.forEach { (id, constName, _) ->
                append("        \"$id\" to $constName,\n")
            }
            append("    )\n")
            append("}\n")
        }

        // 内容没变就不写盘，避免下游编译任务无谓失效
        if (!outFile.isFile || outFile.readText() != text) {
            outFile.writeText(text)
        }
        logger.lifecycle(
            "generateTemplateSources: ${templates.size} template(s)" +
                (if (hasTaxonomy) " + ${taxonomy.first}.yaml" else " (no taxonomy)") +
                " -> ${outFile.absolutePath}",
        )
    }
}

android {
    namespace = "cn.yzapp.androidcontainer.core.engine"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // 与 proot 运行时一致：仅 64 位（fetch_proot_runtime.py 的 jniLibs 目录）
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    // libpty.so：容器终端的 PTY 桥（openpty/forkpty，Java 层无 ioctl 只能 JNI 创建）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            // proot 必须解压到磁盘才能 exec（API 29 起禁止从数据目录执行文件，
            // exec 只允许 nativeLibraryDir 下的路径，jniLibs 解压后正位于该目录）
            useLegacyPackaging = true
            // 阻止 AGP 剥离符号（strip 后 proot 无法执行）
            keepDebugSymbols.add("**/libproot.so")
            keepDebugSymbols.add("**/libproot_loader.so")
        }
    }
    // AGP 9：源码目录通过 directories（String 路径）注册；任务依赖由下面的 preBuild 保证
    sourceSets.getByName("main").kotlin.directories.add(generatedTemplateDir.get().asFile.absolutePath)
}

kotlin {
    jvmToolchain(17)
}

// 生成的正文常量参与 `ComposeTemplates` 编译，必须先于任何编译任务产出
tasks.named("preBuild") {
    dependsOn(generateTemplateSources)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // zstd-jni 必须用 @aar 制品：AAR 内嵌 Bionic 链接的 libzstd-jni-<ver>.so，
    // 经 System.loadLibrary 在 Android 正常加载；默认 JAR 内是 glibc 原生库 + 资源抽取
    // 加载路径（Android 10+ W^X 禁止），不可用（2026-09-18 真机验证，
    // 对齐 home_assistant_flutter 项目已验证组合）。
    // 版本取 1.5.7-6：1.5.7-14+ 的 AAR 声明 minCompileSdk=37，超出本工程 compileSdk 36
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    // 单测用 aircompressor 纯 Java 生成 zstd 测试数据；zstd 解压读取的本地单测
    // 需桌面原生库而 AAR 不携带，测试中以 Assume 守卫（真机已验证）
    testImplementation(libs.aircompressor)
    // docker compose 解析（方案 §3.1 M7）
    implementation(libs.kaml)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
