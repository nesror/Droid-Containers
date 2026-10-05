package cn.yzapp.androidcontainer.core.common

/**
 * 解析 `docker run [options] IMAGE [COMMAND...]` 命令为容器创建参数。
 *
 * 阶段一能力边界：仅提取镜像、容器名、是否后台运行（-d）与启动命令；
 * 端口映射 / 卷挂载 / 环境变量等选项不支持，记录到 [DockerRunSpec.unsupportedOptions] 供 UI 提示。
 */
data class DockerRunSpec(
    val image: String?,
    val name: String?,
    val detached: Boolean,
    val entryCommand: List<String>,
    val unsupportedOptions: List<String>,
)

object DockerRunParser {

    /** 消费独立值的选项（这些选项的下一个 token 是参数值，不能误认为镜像名）。 */
    private val valueFlags = setOf(
        "-p", "--publish", "-v", "--volume", "--mount", "-e", "--env", "--env-file",
        "-w", "--workdir", "-u", "--user", "-h", "--hostname", "-l", "--label",
        "--label-file", "--network", "--net", "--restart", "--entrypoint",
        "-m", "--memory", "--memory-swap", "--memory-reservation", "--cpus", "--cpu-shares",
        "--cpuset-cpus", "--log-driver", "--log-opt", "--health-cmd", "--health-interval",
        "--health-retries", "--health-timeout", "--health-start-period", "--platform",
        "--device", "--dns", "--dns-search", "--dns-option", "--add-host", "--cap-add",
        "--cap-drop", "--security-opt", "--sysctl", "--tmpfs", "--ulimit", "--pull",
        "--ip", "--ip6", "--mac-address", "--cidfile", "--pid", "--ipc", "--uts",
        "--group-add", "--storage-opt", "--volumes-from", "--link", "--expose",
        "--stop-signal", "--stop-timeout", "--isolation", "--kernel-memory",
        "--name", "--hostname",
    )

    /** 解析失败（不是 docker run 命令或没有镜像）返回 null。 */
    fun parse(input: String): DockerRunSpec? {
        val tokens = tokenize(input)
        // 跳过前缀：docker [container] run（容忍 sudo 前缀）
        val startIndex = tokens.indexOfFirst { it == "run" }
        if (startIndex <= 0) return null
        val before = tokens.take(startIndex)
        if (before.firstOrNull() !in setOf("docker", "sudo") ) return null
        val optionTokens = if (before.first() == "sudo") {
            before.drop(1).filterNot { it == "docker" || it == "container" }
        } else {
            before.drop(1).filterNot { it == "container" }
        }
        if (optionTokens.isNotEmpty()) return null

        var name: String? = null
        var detached = false
        val unsupported = mutableListOf<String>()
        val rest = tokens.drop(startIndex + 1)
        var image: String? = null
        val command = mutableListOf<String>()
        var i = 0
        while (i < rest.size) {
            val token = rest[i]
            when {
                token == "--" -> {
                    // `--` 之后全部是镜像命令参数
                    command += rest.drop(i + 1)
                    i = rest.size
                    continue
                }
                // 镜像已确定 → 其余 token 全部原样作为容器命令（包括形如 -c 的参数）
                image != null -> command += token
                token.startsWith("--") -> {
                    val eq = token.indexOf('=')
                    val flag = if (eq > 0) token.substring(0, eq) else token
                    val hasInlineValue = eq > 0
                    when {
                        flag == "--name" -> name = if (hasInlineValue) token.substring(eq + 1) else rest.getOrNull(i + 1)
                        flag == "--detach" -> detached = true
                        else -> unsupported += flag
                    }
                    if (!hasInlineValue && flag in valueFlags) {
                        i += 1 // 跳过选项值（--name 的值已在上面读取）
                    }
                }
                token.startsWith("-") && token.length > 1 -> {
                    if (token in valueFlags) {
                        // 短选项带值（如 -p 8080:80）
                        if (token != "--name") unsupported += token
                        i += 1
                    } else {
                        // 组合短开关（如 -it / -di）
                        token.drop(1).forEach { c ->
                            when (c) {
                                'd' -> detached = true
                                'i', 't' -> Unit
                                else -> unsupported += "-$c"
                            }
                        }
                    }
                }
                image == null -> image = token
            }
            i += 1
        }
        return if (image.isNullOrBlank()) {
            null
        } else {
            DockerRunSpec(
                image = image,
                name = name?.takeIf { it.isNotBlank() },
                detached = detached,
                entryCommand = command,
                unsupportedOptions = unsupported.distinct(),
            )
        }
    }

    /** 按空白切分，尊重成对的单引号 / 双引号（引号内空白不切分，引号本身剥除）。 */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else {
                        current.append(ch)
                    }
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
