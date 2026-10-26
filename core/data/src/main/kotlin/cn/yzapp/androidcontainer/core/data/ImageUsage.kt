package cn.yzapp.androidcontainer.core.data

import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.model.ContainerStatus

/**
 * 运行态镜像保护（issue #2「更新镜像后设置全丢」的配套修）。
 *
 * 本项目的镜像 **不是不可变内容**：`PullEngine.pull` 对同一 ref 是「先删旧 rootfs 再解压」，
 * `ImageRepository.remove` 是「整目录删 layers/<image>」。容器直接以 rootfs 为根跑 proot，
 * 所以只要该镜像被运行中的容器引用，重拉/删除就会把正在使用的文件从脚下抽走（进程随即 ENOENT）。
 *
 * 对齐 docker：`docker rmi` / 重拉被运行容器引用的镜像一律拒绝，提示先停容器。
 * 纯函数、无 Android 依赖，便于单测。
 */
object ImageUsage {

    /** 引用该镜像且处于运行态的容器名（按名排序，供提示文案稳定复现）。 */
    fun runningContainerNames(
        containers: List<ContainerEntity>,
        ref: String,
    ): List<String> = containers
        .filter { it.imageRef == ref && it.status == ContainerStatus.RUNNING }
        .map { it.name }
        .sorted()

    /** 拒绝操作的英文原因（EngineException.message 与镜像页失败进度共用同一句，便于排查）。 */
    fun inUseMessage(ref: String, names: List<String>): String =
        "image $ref is used by running container(s): ${names.joinToString(", ")}; " +
            "stop them first, then pull or delete the image again"
}
