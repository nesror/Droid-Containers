package cn.yzapp.androidcontainer.core.common

/**
 * 容器运行时保活前台服务触发约定：
 * feature 层通过隐式 action + 包名限定触发（同 RemoteControlActions / DataSyncService 先例，
 * 避免跨模块类依赖）。服务位于 app 模块（ContainerRuntimeService）。
 */
object ContainerRuntimeActions {
    const val ACTION_START = "cn.yzapp.androidcontainer.action.RUNTIME_START"
    const val ACTION_STOP = "cn.yzapp.androidcontainer.action.RUNTIME_STOP"

    /** 停止全部运行中容器并退出保活服务（通知栏操作）。 */
    const val ACTION_STOP_ALL = "cn.yzapp.androidcontainer.action.RUNTIME_STOP_ALL"
}
