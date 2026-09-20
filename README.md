# Droid Containers · Droid 容器

> 在 Android 手机上免 root 运行 Docker 镜像 · Run Docker images on your Android device — no root required.

**简体中文** | [English](#english)

---

## 简体中文

**Droid 容器** 是一款原生 Android 应用，基于 **proot 用户态容器**技术，把 Docker 风格的容器体验完整搬到手机上——**无需 root、无需 Termux**，你的系统保持原样，所有数据只留在设备上。

### ✨ 功能特性

- 🖼️ **镜像拉取** — 内置 OCI Registry 客户端，逐层下载、断点续传、进度实时可见；内置多镜像源自动回退，支持私有仓库登录
- 📦 **容器管理** — 创建 / 启动 / 停止 / 删除容器，前台服务托管，后台持续运行
- 💻 **交互终端** — 容器内交互式 Shell 与命令执行，快速查看日志、定位问题
- 🧩 **一键编排** — 用 YAML 描述一组服务（如 mosquitto + Node-RED + Home Assistant），一键启动/停止
- 🌐 **远程控制** — App 内嵌 HTTP 服务，电脑浏览器即可管理容器；兼容 Docker Engine API 子集，**Portainer 可直接连接**
- 🔒 **隐私优先** — 无账号、无统计、无追踪，所有镜像与容器数据仅保存在本机
- 🌍 **多语言界面** — 简体中文 / English / Русский / 日本語

### 📱 系统要求

- Android 8.0（API 26）及以上
- 仅 64 位：arm64-v8a / x86_64

### 📥 下载

Google Play 搜索「**Droid 容器**」，或访问：

https://play.google.com/store/apps/details?id=cn.yzapp.androidcontainer

### 💬 反馈

- GitHub Issues：https://github.com/nesror/Droid-Containers/issues
- 邮箱：nestorgu@foxmail.com

反馈问题时请附上设备型号、Android 版本、镜像名称与容器日志输出，以便更快定位。

---

## English

**Droid Containers** is a native Android app that brings Docker-style container workflows to your phone via **proot user-space containers** — **no root, no Termux**. Your system stays untouched, and all data stays on your device.

### ✨ Features

- 🖼️ **Image pull** — Built-in OCI Registry client with per-layer progress, resumable downloads, automatic registry-mirror fallback, and private-registry login
- 📦 **Container management** — Create / start / stop / remove containers, kept alive in the background by a dedicated foreground service
- 💻 **Interactive terminal** — Interactive shell and one-off command execution inside containers for quick inspection
- 🧩 **Compose-style orchestration** — Describe a stack in YAML (e.g. mosquitto + Node-RED + Home Assistant) and bring it up with one tap
- 🌐 **Remote control** — Built-in HTTP service: manage containers from your PC browser; speaks a subset of the Docker Engine API, so **Portainer can connect directly**
- 🔒 **Privacy-first** — No account, no analytics, no tracking. Images and containers never leave your device
- 🌍 **Localized UI** — 简体中文 / English / Русский / 日本語

### 📱 Requirements

- Android 8.0 (API 26) or later
- 64-bit only: arm64-v8a / x86_64

### 📥 Download

Search for “**Droid Containers**” on Google Play, or visit:

https://play.google.com/store/apps/details?id=cn.yzapp.androidcontainer

### 💬 Feedback

- GitHub Issues: https://github.com/nesror/Droid-Containers/issues
- Email: nestorgu@foxmail.com

When reporting a problem, please include your device model, Android version, image name, and container logs.
