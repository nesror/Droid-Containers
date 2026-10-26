package cn.yzapp.androidcontainer.core.data

import cn.yzapp.androidcontainer.core.data.db.ContainerEntity
import cn.yzapp.androidcontainer.core.model.ContainerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageUsage] 守门测试：运行态镜像保护（issue #2）。
 * 背景（2026-10 用户反馈「更新镜像后所有设置恢复默认」）：同 tag 重拉会先删旧 rootfs、
 * 删除镜像会整目录删 layers/<image>，被运行中容器引用时会让进程立刻 ENOENT。
 */
class ImageUsageTest {

    private fun container(
        name: String,
        imageRef: String,
        status: ContainerStatus,
    ) = ContainerEntity(
        id = name,
        name = name,
        imageRef = imageRef,
        status = status,
    )

    @Test
    fun onlyRunningContainersOfTheSameImageBlockTheOperation() {
        val containers = listOf(
            container("alist", "xhofe/alist:latest", ContainerStatus.RUNNING),
            container("memos", "neosmemo/memos:stable", ContainerStatus.RUNNING),
            container("idle", "xhofe/alist:latest", ContainerStatus.STOPPED),
            container("created", "xhofe/alist:latest", ContainerStatus.CREATED),
        )

        assertEquals(
            listOf("alist"),
            ImageUsage.runningContainerNames(containers, "xhofe/alist:latest"),
        )
        assertEquals(
            emptyList<String>(),
            ImageUsage.runningContainerNames(containers, "xhofe/alist:v3"),
        )
    }

    @Test
    fun messageNamesEveryBlockerSoTheUserKnowsWhatToStop() {
        val names = ImageUsage.runningContainerNames(
            listOf(
                container("web", "halo/halo:2", ContainerStatus.RUNNING),
                container("db", "halo/halo:2", ContainerStatus.RUNNING),
            ),
            "halo/halo:2",
        )

        val message = ImageUsage.inUseMessage("halo/halo:2", names)
        assertTrue(message.contains("halo/halo:2"))
        assertTrue(message.contains("db, web"))
        assertTrue(message.contains("stop", ignoreCase = true))
    }
}
