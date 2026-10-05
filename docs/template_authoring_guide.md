# 模板编写指南：特殊处理点与经验总结

> 本文总结 2026-09-23 全量真机验证（13/13 PASS，见 `template_verification_plan.md`）中沉淀的
> 模板 YAML 编写规则与踩坑经验。**新增模板前先读本文**；字段格式细则见仓库根 `AGENTS.md`
> 「编排模板内容」一节（一个模板 = 一个自描述 YAML 文件）。

---

## 1. command 的三种写法与语义

引擎对 `command` 的处理是模板编写中最大的语义分歧点：

| 写法 | 执行方式 | 是否经过镜像 ENTRYPOINT | 适用场景 |
| --- | --- | --- | --- |
| 不写 `command` | 回落**镜像默认入口**（PullEngine 落盘 config blob → `ImageDefaultEntry` 解析 ENTRYPOINT+CMD） | ✅ | 入口干净的标准镜像（如 `syncthing/syncthing`）；**优先选这种** |
| 数组形式 `command: ["redis-server","--save",""]` | argv 直启，不经 sh | ❌ | 需要精确控制 argv、参数含特殊字符时 |
| scalar 形式 `command: exec foo --bar` | 经 `/bin/sh -c` 执行 | ❌ **完全绕过** | 需要启动前预处理（写配置、建 shim、mkdir），用 `exec` 收尾 |

关键规则：

- **scalar command 必须写全命令名**（不经镜像 ENTRYPOINT，PATH 解析靠引擎注入的容器 PATH），
  且建议以 `exec <cmd> ...` 收尾，让目标进程替换 sh 成为 PID 1（信号能直达）。
- 不写 command 的回落路径依赖镜像 config 里的 ENTRYPOINT/CMD——**官方标准镜像基本都可用**；
  自定义入口脚本类镜像（见 §2）不能用这种写法。

## 2. s6-overlay / `/init` 类镜像：必须绕过启动器

s6-overlay 基础镜像（Home Assistant、Node-RED、filebrowser、code-server 等）的镜像入口是
`/init`，其依赖的 s6 环境在 proot 下不完整，**直接回落默认入口会死**。必须写 scalar command
直接启动真实二进制：

```yaml
# Home Assistant：绕 s6，用 python 模块直启
command: exec python3 -m homeassistant --config /config
# Node-RED：直启 node 脚本
command: cd /usr/src/node-red && exec node build/dist/src/node-red.js -u /data
```

验证方法：拉取后 `exec ls` 看二进制实际位置（上游会挪位置，见 §7 filebrowser 案例）。

## 3. proot 内是 root：二进制对 root 的隐含假设

引擎以 root 身份运行容器（proot `-0` fake-root），由此产生两类坑：

1. **程序拒绝 root**：PostgreSQL 初始化时直接报错 → 不收录该镜像；MariaDB 接受
   `--user=root`（`mariadb-install-db --user=root` + `exec mariadbd --user=root` 已验证可行）。
2. **程序假设 root 环境存在**：Go 二进制（如 filebrowser）以 root 运行时会 exec
   `getent passwd 0` 查询用户信息，而 Alpine busybox **没有 getent applet** → 启动即
   `not found`。解法是在 command 里先写一个兜底 shim 再 `exec`：

```yaml
command: |
  if ! command -v getent >/dev/null 2>&1; then
    printf '#!/bin/sh\n[ "$1" = passwd ] && [ "$2" = 0 ] && echo "root:x:0:0:root:/root:/bin/sh"\n[ "$1" = group ] && [ "$2" = 0 ] && echo "root:x:0:"\nexit 1\n' > /bin/getent
    chmod +x /bin/getent
  fi
  exec /bin/filebrowser -r /srv -d /database.db -a 0.0.0.0 -p 8081
```

模板里 `printf` 生成配置文件是安全做法（避免依赖镜像内既有配置路径）。

## 4. 端口：共享宿主网络栈

proot 容器与手机共享网络栈（**无端口重映射**），规则：

- 同一项目内两个服务**占用同一端口必然冲突**（守门测试 `TemplateCatalogTest` 会查）；
- 服务间互访用 `127.0.0.1:<port>`（如 HA 连 mosquitto：`127.0.0.1:1883`）；
- `webPorts` 必须与正文端口成对出现（守门测试覆盖），非 HTTP 端口（1883/3306/6379/22000/3012）
  只写进正文端口、不进 `webPorts`；
- 端口选择避开常见系统端口与 Android 本地端口段。

## 5. 镜像选择

- **优先固定主版本 tag**（`redis:7-alpine`、`mariadb:11`、`louislam/uptime-kuma:1`），
  防 `latest` 上游漂移（filebrowser 的二进制路径说挪就挪）。
- **选仍在活跃发布 Docker 镜像的上游**：MinIO 2025-10 起停止发布官方镜像，`minio/minio`
  manifest 404，模板已因此下架。收录前先确认 manifest 可解析。
- **注意体积**：manifest 层大小即实际下载量，解压后更大。Home Assistant ≈602MB 下载 /
  ≈2.5GB rootfs，模拟器（10GB 盘）都装不下——手机上收录此类大镜像要谨慎评估用户存储。
- 历史踩坑与镜像格式强相关，见 §7。

## 6. 环境变量

引擎按「镜像 ENV < compose `environment` < 引擎注入」三级合并（09-23 起）：

- 镜像 config 里的 ENV（如 syncthing 的 `HOME=/config`）会自动带上，**模板不必重复声明**；
- 需要覆盖或新增的写进 `environment`；
- 引擎注入的容器 PATH 保证 `sh`、`mkdir`、`node` 等基础命令可寻址。

## 6.5 volumes：只支持 named volume（2026-09-27 起）

- **支持**：`dbdata:/var/lib/db` —— 数据落 `files/engine/volumes/<name>/`，
  **容器/镜像删除不丢**（此前数据寄生 rootfs，删容器即丢）。顶层 `volumes:` 声明可写
  （取名字集合，driver/external 忽略），不写也允许（隐式创建，对齐容错哲学）。
- **老数据迁移（copy-on-first-use）**：volume 为空且 rootfs 内对应路径已有数据时，
  up 自动把数据拷入 volume；rootfs 原件保留。模板从「无 volume」改为加 volume 声明，
  老用户 up 一次即完成迁移，无手工操作。
- **不支持**：bind 路径（`/abs:/path`、`./rel:/path`、`C:\...`）——安全否决，解析期报
  `VOLUME_BIND_IGNORED`；匿名卷（`/path`）——报 `VOLUMES_IGNORED`。
- 多服务引用同一个 volume 名 = 共享数据（同 docker），适合「web + worker 同库」类编排。

## 6.6 restart 策略：常驻服务一律 `unless-stopped`（2026-09-30 起）

**规则：内置模板的每个 service 都写 `restart: unless-stopped`**，位置在该服务 `ports:` 列表
之后、`command:` / `depends_on:` 之前（与 `homeassistant` / `qinglong` 一致）；多服务模板
（`smarthome` / `mariadb-adminer` / `web-redis`）**逐个服务都要写**。守门测试
`TemplateCatalogTest.every built-in service opts into restart unless-stopped` 断言这条约定。

为什么不是「按需加」：

- 模板里的服务全是「部署后指望它一直跑」的常驻服务。手机后台内存紧张，Web 服务被系统
  杀掉后如果没有策略，容器会静默停在 STOPPED，用户得手动再启（对齐 docker：默认策略是
  `no`，模板正文又是用户在「YAML 预览」里直接看到、并会照抄的东西，写明才能复现行为）。
- 一部分 Web UI 的「重启」就是让主进程退出：HA 网页端重启 = 主进程 exit(100)，青龙面板
  重启 = 入口脚本退出——没有策略时容器当场停住（这是 09-28 引入策略的直接动因）。
- 引擎语义（`ContainerRepository.maybeAutoRestart`，见 `RestartPolicies` 文档）：**仅当用户
  没有显式停止**（期望状态仍在，`stop` / `remove` / `down` 会清掉）且策略属于
  `always` / `unless-stopped` / `on-failure` 时才拉起；退避 1s→2s→…封顶 30s，进程稳定运行
  超过 60s 后连败计数清零，**连续 5 次失败即放弃**（移动端不做 docker 那种无限重试）。
  因此 `unless-stopped` 既不跟用户的「停止」打架，也不会 crash-loop 空耗电。
- 已知边界：自动重启只在**本次 App 进程内**生效——`reconcile()` 会清空期望状态，App 被杀
  后重启不会自动拉起容器（那属于「开机自启」，另一条链路）。

---

## 7. 引擎级坑（模板撞到过的真实案例）

写模板时假设「tar 解压、proot 启动就是标准 Linux」不完全成立，以下案例均已修复但值得知道成因：

| 案例 | 现象 | 成因 | 模板侧启示 |
| --- | --- | --- | --- |
| n8n | `Cannot find module '../dist/config'` | tar PAX `linkpath`（>100 字节符号链接目标）曾被截断，pnpm 布局的 node_modules 大量长符号链接 | 报错模块名可能有误导，先怀疑解压完整性 |
| web-redis (nginx) | `mkdir /var/cache/nginx/client_temp failed` | 引擎旧版把 `/var/log` 等空目录 bind 进容器，遮蔽镜像内子目录 | 已修复（改 mkdirs）；若复活旧构建勿回退 |
| web-redis (nginx) | `/init not found` | Alpine `/bin/sh` 是指向 `/bin/busybox` 的**绝对路径**符号链接，宿主 `File.isFile()` 解析不到 | 已修复（`Files.readSymbolicLink`） |
| filebrowser | `not found` → `getent not found` | 上游镜像二进制从 `/usr/local/bin/` 挪到 `/bin/`；root 环境假设 | 见 §3；上游布局会变，固定 tag 只是缓解 |
| syncthing | `HOME: parameter not set` | 镜像 ENV 未应用（旧版） | 已修复（envFromConfig + 三级合并） |

## 8. 验证（新增/修改模板后的固定动作）

1. 跑守门测试：`./gradlew :core:engine:testDebugUnitTest`
   （`TemplateCatalogTest`：零 issue、四语言齐全、id 唯一、端口不冲突、免费边界=4、
   每个服务带 `restart: unless-stopped`；新模板记得同步更新其中的期望清单）。
2. 真机/模拟器跑 `tool/verify_templates_on_device.py --only <id> --keep`，
   通过判据与流程见 `template_verification_plan.md` §3。
3. 注意首启慢服务（HA/Node-RED/n8n/Uptime Kuma）进程 RUNNING ≠ 端口就绪，
   工具已做轮询（web 最长 180s / tcp 120s），记录首次就绪耗时。
4. 验证完回填 `template_verification_plan.md` §5 结果表。
5. `restart` 只影响「进程退出后是否拉起」，不改变启动路径与端口，因此补策略本身**不触发**
   重跑全量真机验证；但自重启场景（HA 网页端重启、青龙面板重启）需要单独真机确认——见
   `template_verification_plan.md` 的待验证项。

## 9. 免费边界与授权

- 免费模板固定 4 个：`web-redis` / `mqtt-broker` / `python-lab` / `homeassistant`（守门测试断言，动免费边界
  需同步改测试）；其余默认 `premium: true`。
- 模板文案（name/desc）是国际化例外，不走 string 资源，随 YAML 走 `LocalizedText`
  （`en` 必填作回退兜底）；其余 App UI 文案仍必须四语言 string 资源。
- ⚠️ **裸值文案里不要出现半角 `: `（冒号+空格）**：kaml 会报
  `mapping values are not allowed here`，整个模板 `PARSE_FAILED` 被静默丢弃
  （守门测试 `the built-in catalog parses without a single issue` 会拦截）。
  含半角冒号的行（英文/俄文 desc 最常见）用双引号包起来；中文全角「：」不受影响。
  收录前可用
  `grep -nE '^\s+(en|zh|ru|ja): [^"'\''].*[a-zA-Z0-9]: ' templates/*.yaml`
  快速自查。
