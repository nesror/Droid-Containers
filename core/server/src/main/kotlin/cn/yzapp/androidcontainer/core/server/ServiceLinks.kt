package cn.yzapp.androidcontainer.core.server

import cn.yzapp.androidcontainer.core.data.ContainerPayloads
import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.engine.compose.ComposeParser
import cn.yzapp.androidcontainer.core.engine.compose.containerPorts

/**
 * 服务直达（点击链接直接打开容器里的 Web 服务）所需的端口派生。
 *
 * proot 与手机共享网络栈、端口不做映射，所以**容器内端口就是局域网里能直接访问的端口**，
 * 与 App 内「端口直达」的语义一致，只是浏览器在另一台机器上、要用手机的局域网 IP。
 */
object ServiceLinks {

    /** 项目正文的派生信息（一次解析同时给出服务数与端口，避免同一份 YAML 被解析多次）。 */
    data class ProjectPlan(
        val serviceCount: Int,
        val ports: List<Int>,
        /** 正文无法解析：控制台据此提示「YAML 有问题」而不是显示成 0 个服务。 */
        val parseError: Boolean,
    )

    fun planOfProject(yaml: String?): ProjectPlan {
        val spec = if (yaml.isNullOrBlank()) {
            null
        } else {
            runCatching { ComposeParser.parse(yaml) }.getOrNull()
        }
        return if (spec == null) {
            ProjectPlan(serviceCount = 0, ports = emptyList(), parseError = true)
        } else {
            ProjectPlan(serviceCount = spec.services.size, ports = spec.containerPorts, parseError = false)
        }
    }

    /**
     * 项目下容器的**已声明 HTTP 端口**（容器编辑器里手工声明的那份）。
     *
     * compose 建出来的容器默认没有这份声明，所以它只是补充信息：
     * 项目端口以 [planOfProject] 的 [ProjectPlan.ports] 为准，这份用于优先展示用户明确标过的 Web 端口。
     */
    fun httpPortsOfContainers(containers: List<ContainerEntity>): List<Int> =
        containers.flatMap { ContainerPayloads.httpPortsOf(it) }.distinct()
}
