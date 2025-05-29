package com.square.aircommand.screens

import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService

object TrainingCameraManager {
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    fun releaseCamera() {
        cameraExecutor?.shutdown()
        cameraExecutor = null

        cameraProvider?.unbindAll()
        cameraProvider = null
    }
}