package com.square.aircommand.backgroundcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.square.aircommand.cameraServies.BackgroundCameraService

/**
 * AlarmManager로 예약된 시간 이후에 호출되어
 * CameraService를 종료하고 UI 상태를 갱신합니다.
 */
class CameraStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("CameraStopReceiver", "📴 알람으로 카메라 서비스 종료됨")

        // 서비스 종료
        context.stopService(Intent(context, BackgroundCameraService::class.java))

        // 상태 저장
        val prefs = context.getSharedPreferences("air_command_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("camera_service_enabled", false).apply()

        // UI 갱신을 위한 브로드캐스트 전송
        val updateIntent = Intent("com.square.aircommand.ACTION_CAMERA_STOPPED")
        context.sendBroadcast(updateIntent)

        Toast.makeText(context, "카메라 서비스가 자동 종료되었습니다", Toast.LENGTH_SHORT).show()
    }
}