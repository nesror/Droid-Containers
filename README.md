# Droid Containers (Droid 容器)

English | [简体中文](README.zh-CN.md)

> Google Play listing name: **Droid Containers** (en/ru), Droid 容器 (zh), ドロイドコンテナ (ja)

Run Docker images on Android **without root and without Termux** — a standalone app that pulls, manages and runs containers.

Built on **proot user-space containers** (filesystem isolation via ptrace), it brings Docker-style command semantics (pull / run / ps / logs / exec / rm / compose) to a native Android app, following Google's recommended architecture with Jetpack Compose + Material 3 throughout.

## 📲 Download (try the release build)

Grab the official Google Play build — ready to use, with **22 orchestration templates verified on real devices** (Home Assistant, n8n, code-server, Vaultwarden and more), no need to build it yourself:

| Channel | Link |
| --- | --- |
| 🟢 **Google Play (recommended)** | [play.google.com/store/apps/details?id=cn.yzapp.androidcontainer](https://play.google.com/store/apps/details?id=cn.yzapp.androidcontainer) |
| 🌐 Website (tutorials / privacy policy / feedback) | [https://191005.xyz/](https://191005.xyz/) |

> An APK built from this repository is fully functional but **ships without the built-in template catalog**; the store build is continuously updated, so prefer it for daily use.

> 📄 Template authoring guide: [`docs/template_authoring_guide.md`](docs/template_authoring_guide.md); a fully annotated example template: [`core/engine/src/test/resources/examples/hello-world.yaml`](core/engine/src/test/resources/examples/hello-world.yaml)

## ✨ Features

| Feature | Description |
| --- | --- |
| 🖼️ Image pull | OCI Registry V2 client in pure Kotlin: layered streaming download, resumable transfers, layer cache, live progress |
| 🔄 Registry fallback | Built-in multi-registry candidates (1ms / DaoCloud / Docker Hub etc.), automatic failover, custom mirrors pinned on top |
| 📦 Container management | Create / start / stop / remove containers, zombie RUNNING auto-correction, unified error banner |
| 💻 Interactive terminal | termux-app `terminal-view` + `terminal-emulator`, interactive `exec` shell inside the container |
| 🧩 Orchestration (compose) | A docker compose equivalent in-app: describe services in YAML, one-tap up/down, dependency topological sort, rollback on failure |
| 🌐 Remote control (M8) | Embedded Ktor HTTP server (foreground-service keep-alive): open `http://<phone-ip>:<port>` in a browser, sign in with a token to the web console (dashboard / images / containers / compose / audit); REST `/api/v1/*` (Bearer auth) |
| 🐳 Docker compatibility (M9) | Docker Engine API subset (v1.43): `docker -H tcp://<phone-ip>:<port> ps/run/stop/rm/pull/logs/exec`, works as a Portainer endpoint; proot semantics (no namespaces, ports shared with the host stack) are disclosed via `Proot:true` in `/info` |
| ⚙️ Settings | DataStore persistence: registries, DNS, auto-start policy, remote control (Web/Docker switches, ports, API token, audit log) |
| 🌍 Four languages | English by default, with Simplified Chinese / Russian / Japanese built in |

## 📱 System requirements

- **Android 8.0+ (API 26)**
- **64-bit only**: arm64-v8a / x86_64 (mainstream images ship 64-bit only; 32-bit devices fail with `unsupportedDevice`)
- No root required

## 🏗️ Architecture

Follows Google's recommended architecture (UI Layer → Domain Layer → Data Layer + unidirectional data flow):

```
┌─────────────────────────────────────────────────────────┐
│ UI Layer (Compose + Material 3)                          │
│   Screen (stateless) ←→ ViewModel (StateFlow UI state)   │
├─────────────────────────────────────────────────────────┤
│ Domain Layer (use cases: complex flows, pure Kotlin)     │
├─────────────────────────────────────────────────────────┤
│ Data Layer (repository interfaces + impls, Hilt)         │
├─────────────────────────────────────────────────────────┤
│ Container engine core:engine (the core of this project)  │
│   OciRegistryClient / TarExtractor / ProotRuntime        │
│   / ContainerProcessManager / ComposeParser              │
├─────────────────────────────────────────────────────────┤
│ Native: proot (shipped via jniLibs) + zstd-jni + Room    │
└─────────────────────────────────────────────────────────┘
```

### Module layout

```
├─ app/                        # shell: navigation, theme, Hilt entry
├─ core/
│  ├─ designsystem/            # Material 3 theme, shared components (ProgressCard, LogViewer, Terminal)
│  ├─ model/                   # pure-Kotlin data models (Image, Container, PullProgress…)
│  ├─ common/                  # dispatchers, Result wrappers, ABI detection
│  ├─ data/                    # repository impls + Room + DataStore
│  ├─ network/                 # OkHttp base config (registry fallback scheduling)
│  └─ engine/                  # ★ container engine: OCI pull / tar extraction / proot runtime
└─ feature/
   ├─ dashboard/  ├─ images/  ├─ containers/  ├─ compose/  └─ settings/
```

Dependency direction: `feature → core:{data,model,designsystem,common} → core:engine`; `app` assembles everything. `core:model` and the Domain layer stay pure Kotlin (JVM modules) for easy unit testing.

## 🛠️ Tech stack

| Aspect | Choice |
| --- | --- |
| Language / build | Kotlin 2.x, Version Catalog (`libs.versions.toml`), JVM 17 |
| UI | Jetpack Compose + Material 3 (NavigationSuiteScaffold adapts to phone / tablet / desktop) |
| Architecture components | ViewModel, Navigation 3, `collectAsStateWithLifecycle` |
| DI | Hilt |
| Async | Coroutines + Flow; pull/extract progress via `SharedFlow` (shared with the foreground service) |
| Persistence | Room (image / container / compose-project metadata) + DataStore-Preferences (settings) |
| Networking | OkHttp + hand-written Registry V2 client |
| YAML | kaml (docker compose parsing) |
| Terminal | termux-app `terminal-view` + `terminal-emulator` (Apache-2.0) |
| zstd | `com.github.luben:zstd-jni` |
| Testing | JUnit + Turbine + Robolectric (engine file operations) |

## 🚀 Build

Requirements: **JDK 17**, Android SDK (compileSdk 37).

```bash
# Debug build (the app module has a channel flavor: global = Play, cn = China)
./gradlew assembleGlobalDebug

# Install on a connected device
./gradlew installGlobalDebug

# Run all unit tests
./gradlew test
```

> 💡 On Windows, if `gradlew` lacks the executable bit, call it with `sh ./gradlew ...`.

### Engine packaging constraints (important)

proot must `exec` at runtime, so the `app` module must configure (a `core:engine`-level setting does not propagate into the APK):

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true                  // must be extracted to disk for exec
        keepDebugSymbols += "**/libproot.so"       // keep AGP from stripping executability away
        keepDebugSymbols += "**/libproot_loader.so"
    }
}
```

The proot runtime binaries are fetched and preprocessed by [`tool/fetch_proot_runtime.py`](tool/fetch_proot_runtime.py); **the artifacts are committed under jniLibs**, so regular builds don't need to run the script. What it does:

1. **ELF dynamic-dependency rename**: proot's `DT_NEEDED` is `libtalloc.so.2`, but jniLibs only accepts names ending in `.so` — rewritten in place inside `.dynstr` to `libtalloc.so`;
2. **Loader path override**: the compile-time Termux loader path is redirected to `nativeLibraryDir` via the `PROOT_LOADER` environment variable;
3. **Architecture check**: validated by ELF `e_machine` to keep wrong-arch artifacts out;
4. proot hard-depends on `libandroid-shmem.so`, also handled by the script.

## 📊 Project status

| Milestone | Scope | Status |
| --- | --- | --- |
| M1 | Multi-module scaffold, design system, NavigationSuiteScaffold five-page nav, four languages | ✅ |
| M2 | tar extractor, OCI Registry client, proot runtime (argv/env injection, children-first stop) | ✅ |
| M3 | PullEngine (per-registry fallback / layer cache / progress stream), dataSync foreground service, Room inventory, images screen | ✅ |
| M4 | proot binaries into jniLibs (DT_NEEDED rename), ContainerManager, containers screen | ✅ |
| M5 | Settings screen (DataStore), exec terminal, auto-start policy | ✅ |
| M6 | Unified error banner, zombie RUNNING correction, real dashboard | ✅ |
| M7 | Compose screen, phase one | ✅ |
| M8 | Remote control: HTTP service + REST + single-page web console (phases one + two) | ✅ |
| M9 | Docker Engine API compatibility: read-only + write ops + exec stream (phases one + two) | ✅ |
| — | Compose screen phase two (template catalog, YAML highlighting, port-conflict checks, readiness probes) | ✅ |

Verified end-to-end on a real device (arm64): pull image → create container → start → exec all work; all 22 built-in orchestration templates verified on hardware.

## 🗺️ Roadmap

- Remote-control hardening: IP allowlist, self-signed TLS; Portainer field testing
- Enhanced `docker load` import on the images screen
- Registry speed test / ordering in settings

## 📚 References & acknowledgements

- [jinhan1414/android-docker-cli](https://github.com/jinhan1414/android-docker-cli) — Termux + proot Docker-style CLI; source of the product semantics and command compatibility matrix
- [termux/termux-app](https://github.com/termux/termux-app) — terminal emulation components (Apache-2.0)
- [proot](https://proot-me.github.io/) — user-space filesystem isolation

## 📄 License

This repository's source is released under the [PolyForm Noncommercial 1.0.0](LICENSE) license: **free to use, modify and distribute for noncommercial purposes**; contact the author for commercial licensing.

- The app name (Droid Containers / Droid 容器) and icon are **not licensed with the source**; redistributed builds must not list themselves under this brand on app stores.
- The store build's built-in orchestration templates and signing keys are not part of this repository.

## 💬 Feedback & support

- **GitHub Issues (recommended)**: [nesror/Droid-Containers/issues](https://github.com/nesror/Droid-Containers/issues)
- **Email**: nestorgu@foxmail.com

When reporting an issue, please include: device model, Android version, image name, container log output (visible on the in-app log screen) and reproduction steps — it makes triage much faster.
