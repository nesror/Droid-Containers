package cn.yzapp.androidcontainer.core.engine.compose

/**
 * 内置示例模板（编排页空态一键填充）。先内置静态 YAML，不做远程模板市场（方案 §5 决策记录）。
 */
object ComposeSamples {

    val REDIS_WEB: String =
        """
        # Sample stack: redis starts first, web depends on it; ports are not
        # mapped — a listening port is reachable on the host as-is
        services:
          cache:
            image: redis:7-alpine
            command: ["redis-server", "--save", ""]
          web:
            image: nginx:alpine
            environment:
              - TZ=Asia/Shanghai
            ports:
              - "80:80"
            depends_on:
              - cache
        """.trimIndent()

    /** 编排页首个项目名建议值。 */
    const val DEFAULT_PROJECT_NAME = "my-stack"
}
