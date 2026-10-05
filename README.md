# Android容器大师 (Droid Containers)

> Google Play 应用名：**Droid Containers**（en/ru）、Droid 容器（zh）、ドロイドコンテナ（ja）

在 Android 设备上**无需 root、无需 Termux**，以独立 App 的形态拉取、管理、运行 Docker 镜像的容器工具。

基于 **proot 用户态容器**（ptrace 实现文件系统隔离），将 Docker 风格的命令语义（pull / run / ps / logs / exec / rm / compose）带入原生 Android App，UI 遵循 Google 推荐架构，全程 Jetpack Compose + Material 3。

## 📲 下载安装（欢迎使用正式版）

欢迎下载 Google Play 正式版使用——开箱即用、内置 **22 个真机验证过的编排模板**（Home Assistant、n8n、code-server、Vaultwarden 等），无需自行构建：

| 渠道 | 链接 |
| --- | --- |
| 🟢 **Google Play（推荐）** | [play.google.com/store/apps/details?id=cn.yzapp.androidcontainer](https://play.google.com/store/apps/details?id=cn.yzapp.androidcontainer) |
| 🌐 官网（教程 / 隐私政策 / 反馈） | [https://191005.xyz/](https://191005.xyz/) |

> 本仓库源码构建的 APK 功能完整但**不含内置模板库**；正式版随版本持续更新，还请以商店渠道为准。

> 📄 模板编写规范见 [`docs/template_authoring_guide.md`](docs/template_authoring_guide.md)；字段齐全的示例模板见 [`core/engine/src/test/resources/examples/hello-world.yaml`](core/engine/src/test/resources/examples/hello-world.yaml)

## ✨ 功能特性

| 功能 | 说明 |
| --- | --- |
| 🖼️ 镜像拉取 | 纯 Kotlin 实现 OCI Registry V2 客户端，逐层流式下载、断点续传、层缓存，拉取进度实时可见 |
| 🔄 镜像源回退 | 内置多候选源（1ms / DaoCloud / 官方源等），失败自动切换下一个，支持自定义源置顶 |
| 📦 容器管理 | 创建 / 启动 / 停止 / 删除容器，僵尸 RUNNING 状态自动校正，统一错误条 |
| 💻 交互终端 | 基于 termux-app 的 terminal-view + terminal-emulator，容器内 `exec` 交互式 Shell |
| 🧩 编排（compose） | App 内的 docker compose 等价物：YAML 描述一组服务，一键 up/down，依赖拓扑排序、失败回滚 |
| 🌐 远程控制（M8） | App 内嵌 Ktor HTTP 服务（前台服务保活）：浏览器打开 `http://<手机IP>:<端口>`，输入 token 登录 Web 控制台（仪表盘/镜像/容器/编排/审计五页，拉取进度 SSE、日志跟随）；REST `/api/v1/*`（Bearer 鉴权） |
| 🐳 Docker 兼容（M9） | Docker Engine API 子集（v1.43）：`docker -H tcp://<手机IP>:<端口> ps/run/stop/rm/pull/logs/exec`，Portainer 可作 Endpoint 直连；proot 语义差异（无命名空间、端口共享宿主栈）在 `/info` 以 `Proot:true` 明示 |
| ⚙️ 设置 | DataStore 持久化：镜像源、DNS、自启策略、远程控制（Web/Docker 双开关、端口、API Token、审计日志） |
| 🌍 四语言 | 默认英文，内置简体中文 / 俄语 / 日语 |

## 📱 系统要求

- **Android 8.0+（API 26）**
- **仅 64 位**：arm64-v8a / x86_64（主流镜像仅提供 64 位，32 位设备直接报 `unsupportedDevice`）
- 无需 root

## 🏗️ 架构

采用 Google 官方推荐架构（UI Layer → Domain Layer → Data Layer + 单向数据流 UDF）：

```
┌─────────────────────────────────────────────────────────┐
│ UI Layer（Compose + Material 3）                          │
│   Screen（无状态） ←→ ViewModel（StateFlow 暴露 UI 状态）   │
├─────────────────────────────────────────────────────────┤
│ Domain Layer（UseCase：复杂业务编排，纯 Kotlin）            │
├─────────────────────────────────────────────────────────┤
│ Data Layer（Repository 接口 + 实现，Hilt 注入）             │
├─────────────────────────────────────────────────────────┤
│ 容器引擎 core:engine（本项目核心资产）                      │
│   OciRegistryClient / TarExtractor / ProotRuntime        │
│   / ContainerProcessManager / ComposeParser              │
├─────────────────────────────────────────────────────────┤
│ 原生层：proot（jniLibs 分发）+ zstd-jni + Room/DataStore   │
└─────────────────────────────────────────────────────────┘
```

### 模块划分

```
├─ app/                        # 壳工程：导航、主题、Hilt 入口
├─ core/
│  ├─ designsystem/            # Material 3 主题、通用组件（ProgressCard、LogViewer、Terminal）
│  ├─ model/                   # 纯 Kotlin 数据模型（Image、Container、PullProgress…）
│  ├─ common/                  # 调度器、Result 封装、ABI 检测等工具
│  ├─ data/                    # Repository 实现 + Room + DataStore
│  ├─ network/                 # OkHttp 基础配置（含镜像源回退调度）
│  └─ engine/                  # ★ 容器引擎：OCI 拉取 / tar 解压 / proot 运行时
└─ feature/
   ├─ dashboard/  ├─ images/  ├─ containers/  ├─ compose/  └─ settings/
```

依赖方向：`feature → core:{data,model,designsystem,common} → core:engine`；`app` 组装全部。`core:model` 与 Domain 层保持纯 Kotlin（JVM 模块），便于单测。

## 🛠️ 技术栈

| 维度 | 选型 |
| --- | --- |
| 语言 / 构建 | Kotlin 2.x，Version Catalog（`libs.versions.toml`），JVM 17 |
| UI | Jetpack Compose + Material 3（NavigationSuiteScaffold 适配手机/平板/桌面） |
| 架构组件 | ViewModel、Navigation 3、`collectAsStateWithLifecycle` |
| DI | Hilt |
| 异步 | Coroutines + Flow，拉取/解压进度用 `SharedFlow`（前台服务与 UI 共享） |
| 持久化 | Room（镜像/容器/编排项目元数据）+ DataStore-Preferences（设置） |
| 网络 | OkHttp + 手写 Registry V2 客户端 |
| YAML | kaml（docker compose 解析） |
| 终端 | termux-app 的 `terminal-view` + `terminal-emulator`（Apache-2.0） |
| zstd | `com.github.luben:zstd-jni` |
| 测试 | JUnit + Turbine + Robolectric（引擎层文件操作） |

## 🚀 构建

环境要求：**JDK 17**、Android SDK（compileSdk 37）。

```bash
# Debug 构建（app 模块带渠道 flavor：global=Play 版，cn=国内版）
./gradlew assembleGlobalDebug

# 安装到已连接设备
./gradlew installGlobalDebug

# 运行全部单测
./gradlew test
```

> 💡 Windows 下若 `gradlew` 无执行位，可用 `sh ./gradlew ...` 调用。

### 引擎相关的打包约束（重要）

proot 需要在运行时 `exec`，因此 `app` 模块必须配置（`core:engine` 内配置不会传导到 APK）：

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true                  // 必须解压到磁盘才能 exec
        keepDebugSymbols += "**/libproot.so"       // 阻止 AGP strip 破坏可执行性
        keepDebugSymbols += "**/libproot_loader.so"
    }
}
```

proot 运行时二进制由 [`tool/fetch_proot_runtime.py`](tool/fetch_proot_runtime.py) 获取并预处理，**产物已随 jniLibs 入库**，常规构建无需再执行脚本。脚本所做的处理：

1. **ELF 动态依赖改名**：proot 的 `DT_NEEDED` 为 `libtalloc.so.2`，jniLibs 只认 `.so` 结尾——在 `.dynstr` 中原位改写为 `libtalloc.so`；
2. **loader 路径覆盖**：编译期写死的 Termux loader 路径通过 `PROOT_LOADER` 环境变量指向 `nativeLibraryDir`；
3. **架构校验**：按 ELF `e_machine` 校验，防止错误架构产物入库；
4. proot 硬依赖 `libandroid-shmem.so`，脚本已一并处理。

## 📊 项目进度

| 里程碑 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 多模块脚手架、设计系统、NavigationSuiteScaffold 五页导航、四语言 | ✅ |
| M2 | tar 解压器、OCI Registry 客户端、proot 运行时（argv/环境注入、先子后父停止） | ✅ |
| M3 | PullEngine（逐源回退/层缓存/进度流）、dataSync 前台服务、Room 库存、镜像页 | ✅ |
| M4 | proot 二进制 jniLibs 落盘（DT_NEEDED 改名）、ContainerManager、容器页 | ✅ |
| M5 | 设置页（DataStore）、exec 终端、自启策略 | ✅ |
| M6 | 统一错误条、僵尸 RUNNING 状态校正、仪表盘真实化 | ✅ |
| M7 | 编排页（compose）阶段一 | ✅ |
| M8 | 远程控制：HTTP 服务 + REST + Web 控制台单页（阶段一+二） | ✅ |
| M9 | Docker Engine API 兼容：只读 + 写操作 + exec 流（阶段一+二） | ✅ |
| — | 编排页阶段二（模板库、YAML 高亮、端口冲突检查、健康探测） | ✅ |

已验证：真机（arm64）端到端链路跑通——拉取镜像 → 创建容器 → 启动 → exec 全链可用，22 个内置编排模板全部真机验证。

## 🗺️ Roadmap

- 远控安全增强：IP 白名单、TLS 自签；Portainer 实测
- 镜像页 `docker load` 导入增强
- 设置页镜像源测速 / 排序

## 📚 参考与致谢

- [jinhan1414/android-docker-cli](https://github.com/jinhan1414/android-docker-cli) — Termux + proot 的 Docker 风格 CLI，提供产品语义与命令兼容矩阵
- [termux/termux-app](https://github.com/termux/termux-app) — 终端模拟组件（Apache-2.0）
- [proot](https://proot-me.github.io/) — 用户态文件系统隔离

## 📄 许可

本项目源码以 [PolyForm Noncommercial 1.0.0](LICENSE) 许可发布：**可自由使用、修改、分发，但不得用于商业目的**；商业使用请联系作者另行授权。

- 应用名（Droid Containers / Android容器大师）与图标**不随源码授权**，重新分发的构建产物不得冒用该品牌上架应用商店。
- 应用商店版内置的编排模板与签名密钥不随源码分发。

## 💬 反馈与支持

- **GitHub Issues（推荐）**：[nesror/Droid-Containers/issues](https://github.com/nesror/Droid-Containers/issues)
- **邮箱**：nestorgu@foxmail.com

反馈问题时请附上：设备型号、Android 版本、镜像名称、容器日志输出（App 内日志页可查看），以及复现步骤，这样能更快定位问题。
