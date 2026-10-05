package cn.yzapp.androidcontainer.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 调度器注入接口：engine/domain 层不直接引用 Dispatchers，便于单测。
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}
