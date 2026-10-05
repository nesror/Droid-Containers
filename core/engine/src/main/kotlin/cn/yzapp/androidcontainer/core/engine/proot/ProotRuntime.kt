package cn.yzapp.androidcontainer.core.engine.proot

import cn.yzapp.androidcontainer.core.model.EngineErrorCode
import cn.yzapp.androidcontainer.core.model.EngineException
import java.io.File

/** 容器内路径 → 宿主路径的绑定挂载。 */
data class BindMount(val hostPath: File, val containerPath: String, val readOnly: Boolean = false)

/**
 * proot argv 组装与环境注入（方案 §3.4）。
 * argv 模板：
 * proot -0 --link2symlink --kill-on-exit -w <workdir> -r <rootfs>
 *       -b <tmp>:/tmp [-b 用户卷] [extra] <entrypoint/cmd>
 * （/run、/var/log、/var/cache、/var/run 不再整目录 bind：会遮住镜像内子目录，
 *   缺失时改为在 rootfs 内补建）
 *
 * 注意：proot CLI 不支持 `--` 选项终止符（报 unknown option '--'），命令直接追加；
 * 以 `-` 开头的容器命令会被 proot 误当选项，实际镜像命令极少如此，暂不处理。
 */
class ProotRuntime(
    private val nativeLibraryDir: File,
    private val engineDir: File,
) {

    data class LaunchSpec(
        val argv: List<String>,
        val environment: Map<String, String>,
    )

    fun launch(
        rootfs: File,
        workdir: String,
        command: List<String>,
        userBinds: List<BindMount> = emptyList(),
        extraProotArgs: List<String> = emptyList(),
    ): LaunchSpec {
        val prootBin = File(nativeLibraryDir, "libproot.so")
        if (!prootBin.isFile) {
            throw EngineException(
                EngineErrorCode.RUNTIME_MISSING,
                "proot binary missing in ${nativeLibraryDir.path}",
            )
        }
        val tmpDir = File(engineDir, "tmp").apply { mkdirs() }

        val argv = mutableListOf(
            prootBin.path,
            "-0", // fake-root：兼容期望 root 启动再降权的镜像
            "--link2symlink", // Android 文件系统不支持硬链接
            "--kill-on-exit", // 主命令退出后清理派生子进程
            "-w", workdir,
            "-r", rootfs.path,
        )
        argv += listOf("-b", "${tmpDir.path}:/tmp")
        // 运行时目录不整目录 bind：bind 引擎目录会**遮住镜像内已有子目录**
        // （nginx:alpine 的 /var/cache/nginx、/var/log/nginx 在空 bind 下不存在，
        // 启动即 emerg；2026-09-23 模板验证），且 binds 目录是引擎全局共享，
        // 本就没有按容器隔离的收益。改为镜像缺失时在 rootfs 内补建（幂等）。
        listOf("run", "var/log", "var/cache", "var/run").forEach { File(rootfs, it).mkdirs() }
        // 标准系统目录绑定（Termux proot-distro 同款）：镜像进程普遍依赖 /dev/null、/proc、/sys，
        // 而 rootfs 是普通目录树没有设备节点（python subprocess 打不开 /dev/null 直接
        // FileNotFoundError，2026-09-18 真机 HA 启动即崩）。proot 绑定只是路径翻译不涉及真实
        // mount；宿主侧 open() 权限仍按 App uid 校验，-0 仅伪造 stat 归属，不放大实际权限。
        // 访问点目录必须先存在（rootfs 内 mkdirs 幂等）。
        listOf("dev", "proc", "sys").forEach { File(rootfs, it).mkdirs() }
        argv += listOf("-b", "/dev:/dev")
        argv += listOf("-b", "/proc:/proc")
        argv += listOf("-b", "/sys:/sys")
        userBinds.forEach { bind ->
            val mapping = "${bind.hostPath.path}:${bind.containerPath}" + if (bind.readOnly) ":ro" else ""
            argv += listOf("-b", mapping)
        }
        argv += extraProotArgs
        // 不加 `--`：proot CLI 无选项终止符，实测报 "unknown option '--'"（2026-09-18 真机）
        argv += command

        return LaunchSpec(argv, environment(tmpDir))
    }

    private fun environment(tmpDir: File): Map<String, String> = mapOf(
        // 容器内 PATH：ProcessBuilder 会继承 Android 宿主环境（/system/bin 等，不含 /bin），
        // proot 内 shell 按 PATH 找 mkdir/node/python3 等会全部 "not found"（2026-09-18 真机），
        // 必须显式注入容器标准 PATH（与 proot-distro 一致）；引擎环境后合并故可覆盖用户误设
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LD_LIBRARY_PATH" to nativeLibraryDir.path,
        "PROOT_LOADER" to File(nativeLibraryDir, "libproot_loader.so").path,
        "PROOT_LOADER_32" to File(nativeLibraryDir, "libproot_loader32.so").path,
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_TMP_DIR" to tmpDir.path,
    )

    companion object {
        const val DNS_DEFAULT = "1.1.1.1"

        /**
         * s6-overlay / PID 1 适配（方案 §3.4）：proot 不虚拟化 PID，
         * /init（s6-overlay v3）检查 getpid() != 1 必然失败。
         * Entrypoint 为 /init 且存在 etc/services.d 下的服务 run 脚本时，返回 true 提示上层
         * 改用服务 run 脚本等价命令或明确提示不支持。
         */
        fun isS6OverlayEntrypoint(rootfs: File, entrypoint: String): Boolean {
            if (entrypoint != "/init") return false
            val servicesDir = File(rootfs, "etc/services.d")
            return servicesDir.isDirectory &&
                servicesDir.listFiles()?.any { File(it, "run").isFile } == true
        }

        /**
         * 无 shell 镜像回退（方案 §3.4）：rootfs 无 /bin/sh 时不经 shell -c 包装。
         * 注意 /bin/sh 在镜像内常为**绝对路径**符号链接（Alpine: sh -> /bin/busybox），
         * 宿主视角解析不到链接目标（isFile=false），须把目标映射回 rootfs 内再判断。
         */
        fun hasShell(rootfs: File): Boolean {
            val sh = File(rootfs, "bin/sh")
            if (sh.isFile) return true
            return runCatching {
                val target = java.nio.file.Files.readSymbolicLink(sh.toPath()).toString()
                val resolved = if (target.startsWith("/")) {
                    File(rootfs, target.trimStart('/'))
                } else {
                    File(sh.parentFile, target)
                }
                resolved.isFile
            }.getOrDefault(false)
        }
    }
}
