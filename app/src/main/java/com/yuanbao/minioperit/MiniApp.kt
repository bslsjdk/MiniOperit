package com.yuanbao.minioperit

import android.app.Application
import com.yuanbao.minioperit.backend.BackendRegistry

/**
 * 在这里注册你自己的推理后端。
 *
 * 示例：
 *   BackendRegistry.register(MyHexagonBackend())
 *   BackendRegistry.setActive("myhex")
 */
class MiniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 默认后端（调试回显 + llama.cpp 骨架）已在 BackendRegistry.init 注册
        // 接入自有后端就在这里加一行 register
    }
}
