# Contributing to Droid Containers

感谢关注本项目！欢迎 Issue 与 PR。

## 开发环境

- **JDK 17**、Android SDK（compileSdk 37）
- 仅支持 64 位构建目标（arm64-v8a / x86_64），与 App 运行时一致

```bash
# 构建（app 模块带渠道 flavor：global=Play 版，cn=国内版）
./gradlew assembleGlobalDebug

# 安装到已连接设备
./gradlew installGlobalDebug

# 单测
./gradlew :core:engine:testDebugUnitTest        # 引擎模块
./gradlew :core:data:testCnDebugUnitTest        # data 模块（带渠道 flavor）
./gradlew test                                  # 全部
```

改动引擎（`core:engine`）或 data 层后，请至少跑对应模块的单测再提交。

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

## 编排模板

- 一个模板 = 一个自描述 YAML 文件，字段说明与写作规范见 [`docs/template_authoring_guide.md`](docs/template_authoring_guide.md)
- 字段齐全的完整示例见 [`core/engine/src/test/resources/examples/hello-world.yaml`](core/engine/src/test/resources/examples/hello-world.yaml)
- 模板元数据中的文案不走 `strings.xml`：内置文案按 App 当前语言解析（回退链 `zh-Hant → zh → en → 任一`），`en` 必填
- 模板解析守门测试在 `core/engine` 的 `TemplateCatalogTest`：新模板必须零 issue 解析

## 提交规范

- Conventional Commits：`feat: xxx` / `fix: xxx` / `docs: xxx` / `refactor: xxx` / `test: xxx` / `chore: xxx`，描述用英文小写、简明扼要
- 一个 PR 聚焦一件事；UI 改动请附截图

## 报告 Bug

请附上：设备型号、Android 版本、镜像名称、容器日志输出（App 内日志页可查看）与复现步骤。
