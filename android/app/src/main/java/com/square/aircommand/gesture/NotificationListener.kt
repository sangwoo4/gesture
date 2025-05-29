package com.square.aircommand.gesture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 음악 제어 등을 위해 알림을 수신할 수 있는 NotificationListenerService 구현
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationListener", "🔔 Listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 필요 시 알림 정보 처리 가능 (지금은 단순 로그만)
        Log.d("NotificationListener", "🔔 Posted: ${sbn.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Log.d("NotificationListener", "❌ Removed: ${sbn.packageName}")
    }
}