# AGENTS.md

> 本文件面向在本仓库工作的 AI 编码代理（CodeBuddy / WorkBuddy / Claude Code 等）。开始任务前请先通读本文件并严格遵守。

## 项目简介

**Droid Containers（Android容器大师）**：在 Android 设备上无需 root、无需 Termux，以独立 App 形态拉取、管理、运行 Docker 镜像的容器工具（proot 用户态容器）。

- 包名 `cn.yzapp.androidcontainer`
- 许可：PolyForm Noncommercial 1.0.0（可用但不可商用）
- **本仓库的 `core/engine/src/main/templates/` 有意留空**：应用商店版内置的编排模板不随源码分发；构建与测试在空模板目录下必须保持可用，不要往生成器或目录约定里加入「必须有模板」的假设

## ⚡ 核心工作流规则（最高优先级）

1. 只 `git add` 与本次任务相关的文件（禁止 `git add -A` / `git add .`）
2. 提交信息使用 Conventional Commits 格式：`feat: xxx` / `fix: xxx` / `docs: xxx` / `refactor: xxx` / `test: xxx` / `chore: xxx`，描述用英文小写、简明扼要
3. 若工作区存在与本任务无关的他人改动，不要提交它们，保持原样

## 系统要求与构建

- **JDK 17**，Android SDK（compileSdk 37，minSdk 26，targetSdk 36）
- 仅 64 位：arm64-v8a / x86_64
- Gradle 构建（daemon 模式，禁 `--no-daemon`）：

```bash
./gradlew assembleDebug        # Debug 构建
./gradlew installDebug         # 安装到已连接设备
./gradlew test                 # 全部单测
```

- **渠道 flavor（`channel` 维度）**：`global`（Play 分发，内购 + 付费模板解锁）/ `cn`（国内分发，免购、隐藏付费模板、Web 控制台免解锁）。含渠道差异的模块：`app`、`core:billing`、`core:data` 与全部 `feature:*`。app 模块任务必须带变体名：`assembleGlobalDebug` / `assembleCnDebug` / `installCnDebug`；声明了 flavor 的模块单测任务是 `testGlobalDebugUnitTest` / `testCnDebugUnitTest`，未声明 flavor 的模块（engine 等）仍是 `testDebugUnitTest`。

改动引擎或 data 层后至少跑 `:core:engine:testDebugUnitTest`；新增 UI 模块时跑对应模块的单测。

## 代码约定

### 国际化（硬性要求）
- 所有 UI 文案使用 string 资源，**禁止硬编码**
- 默认英文 `values/`，且必须同步补齐 `values-zh` / `values-ru` / `values-ja` 四语言
- 新 UI 组件与页面落成时，四语言文件同一次提交内完成

### 架构（Google 推荐架构）
- UI 层：Jetpack Compose + Material 3；Screen 无状态，ViewModel 暴露 `StateFlow`，用 `collectAsStateWithLifecycle` 订阅
- 分层：`feature → core:{data,model,designsystem,common} → core:engine`；`app` 组装全部
- `core:model` 与 Domain 层保持纯 Kotlin（JVM 模块）
- DI 用 Hilt；异步用 Coroutines + Flow，进度类事件用 `SharedFlow`

### 引擎层注意事项（已踩坑结论，勿回退）
- `app` 模块必须设 `useLegacyPackaging=true` + `keepDebugSymbols(libproot/libproot_loader)`——在 library 模块设置不会传导
- proot 的 `DT_NEEDED` 硬依赖 `libandroid-shmem.so`（`tool/fetch_proot_runtime.py` 已处理改名与架构校验；产物已随 jniLibs 入库）
- tar 解压用 zstd-jni，proguard 需保留 `com.github.luben.zstd.**`；同名条目先 `deleteEntry` 再创建（防符号链接 EROFS）
- zstd-jni 必须用 `@aar` 制品：默认 JAR 内嵌 glibc 链接 so，Bionic dlopen 拒载（用 1.5.7-6@aar，1.5.7-14+ 声明 minCompileSdk=37）
- 目录条目只表示「确保存在」，**绝不递归清空**：OCI 后续层会重复携带父目录条目，清空会抹掉前层文件（删除语义只能由 whiteout 触发）
- proot 环境必须注入容器标准 PATH（继承宿主 PATH 则 shell 内 mkdir/node 等全 not found），并绑定 `-b /dev:/dev -b /proc:/proc -b /sys:/sys`
- 停容器顺序：先子后父（SIGTERM → SIGKILL → 收 proot）；`ContainerManager.stop` 保持幂等
- 符号链接目标必须原样保留——改写绝对 linkName 会让 Alpine 系全挂 `/bin/sh ENOENT`

### 编排模板内容（一个模板 = 一个自描述 YAML 文件）
- 内容源在 `core/engine/src/main/templates/`：`<template-id>.yaml`（compose 正文 + 多语言文案 + 类型元数据）与 `_taxonomy.yaml`（`_` 前缀 = 元数据文件，共享分类学）
- **本仓库模板目录为空**：真实模板不随源码分发；模板文件格式、字段语义与编写规范见 `docs/template_authoring_guide.md`，字段齐全的示例见 `core/engine/src/test/resources/examples/hello-world.yaml`（解析守门测试从该示例的 classpath 资源读取校验）
- 文件由 Gradle 任务 `:core:engine:generateTemplateSources` 在**构建期**搬成 `TemplateYaml.kt` 常量（产物在 `build/generated/templateSources/` 下，不入库），`preBuild` 依赖该任务；任务只搬运文本、不解析；**空目录/缺 `_taxonomy.yaml` 时生成空常量文件而非构建失败**（勿回退为抛异常）
- 容错（目标是「直接丢一个 compose 文件进来也能用」）：
  - 整份文件只有 compose 正文（根上是 `services:`、没有 `compose:`）→ 取文件名当 id、名称用文件名兜底、归入兜底分类 `other`；`premium` 保持锁定、`risk` 取最保守的 `experimental`
  - `id` / `category` / `name` 缺失各有兜底（id 用文件名、未知分类归 `other`、name 用 id）；`desc` / `notes` / `webPorts` 允许为空
  - 只有「正文解析失败」或「既没有 `services` 也没有 `compose`」才丢弃文件
  - 以上情况一律记 `TemplateIssue`（英文、仅内部诊断）；`TemplateTaxonomy.parse` 对空白分类学文本返回 EMPTY 且**不记 issue**
- 解析与校验在 `core:engine` 的 `TemplateCatalog`（纯 Kotlin，kaml）：内置与远端模板共用同一个解析器；`TemplateCatalog.builtIn()` 进程内解析一次，`ContainerApp` 在后台预热
- ⚠️ **注释里不要写 `templates/*.yaml`**：Kotlin 块注释可嵌套，`/*` 会开启新注释并吞掉后面整段代码
- 生成任务必须写成 **configuration-cache 安全**形式（本工程已开启配置缓存）：不要在 `doLast` 里触碰 `project` / `rootDir`

### 远程控制与 Web 控制台
- **Web 控制台与 `/api/v1` 属于模板包，未解锁不可用**：设置页开关置灰 / `ServerService` 按权益启停 / `RemoteHttpServer` 服务端 402（落地页注入解锁状态）。**Docker 兼容实例不门禁**；`GET /_health` 保持免鉴权免门禁
- 锁定的付费模板不下发 `compose`（`core/server/.../TemplateFeed.kt` 的内容级门禁），与服务级门禁形成纵深；两者共用同一份 `EntitlementRepository.state`，判定规则都是「仅 Unlocked 放行」
- 控制台内容源：`core/server/src/main/resources/web/index.html`（单文件、零构建、无 CDN）
- **控制台 i18n**：四语言目录 `I18N` 内置于该文件（en 为基准，缺键回退 en）；**改控制台文案后必须跑 `tool/check_console_i18n.py`**（四语言键集/占位符/引用一致性守门，非 0 退出即失败）；服务端枚举保持英文原值、由前端映射翻译
- 权益缓存损坏要能自愈：`EntitlementStore` 的 DataStore 已配 `ReplaceFileCorruptionHandler`，不要改回抛异常

### 容器终端
- **一个终端会话 = 独立 proot shell 进程 + 真 PTY**（等价 `docker exec -it`）：`ContainerRepository.openTerminal` 挂同一 rootfs 跑 `/bin/sh -l`，经 `core/engine/terminal`（`PtyJni` + `libpty.so`，NDK 源码 `core/engine/src/main/cpp/pty.c`）赋予 PTY。Android toybox 没有 `script`、Java 层无 ioctl，PTY 只能 JNI 创建
- 终端环境注入与 `start()` 同级：镜像 ENV + 用户 env + 引擎运行环境 + `TERM=xterm-256color`；**容器 stop/remove 时必须关终端会话**（`terminals.closeForContainer`）
- 终端会话关闭契约：`closeOnce` CAS 单次关闭；nativeLock 是短锁，阻塞 I/O 绝不持锁；`@JavascriptInterface` 一律 scope.launch 异步派发

### 导航 / 生命周期（勿回退）
- `NavDisplay` 显式传双 entryDecorator；**长任务挂 `DataGraph.appScope` 严禁 viewModelScope**；跨页运行态用进程级单例；**出栈一律走 `Navigation.kt` 的 `pop` 兜底**
- 导航形态：显式 layoutType；Compact → 底部栏否则 rail；insets 用 `.union()` 没有 plus
