package com.square.aircommand.tflite

import java.util.*

object AIHubDefaults {
    // Qualcomm 디바이스에서 AI Hub 기본값에 해당하는 Delegate 목록
    val enabledDelegates: Set<TFLiteHelpers.DelegateType> = setOf(
        TFLiteHelpers.DelegateType.QNN_NPU,
        TFLiteHelpers.DelegateType.GPUv2
    )

    // CPU에서 실행되는 레이어에 사용할 기본 스레드 수 (전체의 절반)
    val numCPUThreads: Int = Runtime.getRuntime().availableProcessors() / 2

    // AI Hub의 기본 Delegate 우선순위 설정
    val delegatePriorityOrder: Array<Array<TFLiteHelpers.DelegateType>> = arrayOf(
        // 1. QNN_NPU + GPUv2 (최우선)
        arrayOf(TFLiteHelpers.DelegateType.QNN_NPU, TFLiteHelpers.DelegateType.GPUv2),

        // 2. GPUv2 단독 (NPU 미지원 시)
        arrayOf(TFLiteHelpers.DelegateType.GPUv2),

        // 3. CPU fallback (XNNPack 전용)
        arrayOf()
    )

    // 사용 가능한 Delegate 목록으로 필터링된 우선순위 배열 반환
    fun delegatePriorityOrderForDelegates(
        enabledDelegates: Set<TFLiteHelpers.DelegateType>
    ): Array<Array<TFLiteHelpers.DelegateType>> {
        return delegatePriorityOrder.filter { enabledDelegates.containsAll(it.toList()) }
            .toTypedArray()
    }
}
