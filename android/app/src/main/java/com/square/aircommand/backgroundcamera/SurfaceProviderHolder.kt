package com.square.aircommand.backgroundcamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat

/**
 * 실제 UI 없이 Surface를 구성해주는 객체
 * CameraX가 프리뷰 출력을 처리할 수 있도록 제공됨
 */
object SurfaceProviderHolder {

    fun create(context: Context): Preview.SurfaceProvider {
        return Preview.SurfaceProvider { request ->
            val surfaceTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(640, 480) // 원하는 프리뷰 해상도
            }

            val surface = Surface(surfaceTexture)

            // Surface 제공 및 해제 처리
            request.provideSurface(surface, ContextCompat.getMainExecutor(context)) {
                surfaceTexture.release()
                surface.release()
            }
        }
    }
}