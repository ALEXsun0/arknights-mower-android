package com.aliothmoon.maameow.remote.internal

/** 物理屏屏保与虚拟屏各自持有活动目标，关闭其中一项不影响另一项。 */
internal class DisplayKeepAliveTargets {
    private var physical = -1
    private var virtual = -1
    @Synchronized fun physical(id: Int) { physical = id }
    @Synchronized fun virtual(id: Int) { virtual = id }
    @Synchronized fun clear() { physical = -1; virtual = -1 }
    @Synchronized fun snapshot(sdk: Int): List<Int> = buildList {
        if (physical >= 0) add(physical)
        // Android 13 起才创建独立显示组；旧系统不能让虚拟屏保活变成主屏常亮。
        if (sdk >= 33 && virtual > 0 && virtual != physical) add(virtual)
    }
}
