package cn.yzapp.androidcontainer.core.common

/**
 * 远程控制前台服务触发约定（M8）：
 * feature 层通过隐式 action + 包名限定触发（同 DataSyncService 先例，避免跨模块类依赖）。
 */
object RemoteControlActions {
    const val ACTION_START = "cn.yzapp.androidcontainer.action.REMOTE_START"
    const val ACTION_STOP = "cn.yzapp.androidcontainer.action.REMOTE_STOP"
}
