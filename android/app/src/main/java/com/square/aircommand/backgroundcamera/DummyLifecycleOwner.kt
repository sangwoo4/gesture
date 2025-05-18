package com.square.aircommand.backgroundcamera

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * CameraX의 bindToLifecycle에 필요한 LifecycleOwner 대체 구현
 * 실제 UI 생명주기가 없기 때문에 수동으로 Lifecycle 상태 설정
 */
class DummyLifecycleOwner : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    init {
        // ON_START 상태로 설정하여 CameraX가 실행 가능하도록 설정
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}