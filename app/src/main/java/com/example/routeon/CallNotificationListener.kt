package com.example.routeon

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class CallNotificationListener : NotificationListenerService() {

    companion object {
        var answerIntent: PendingIntent? = null
        var declineIntent: PendingIntent? = null

        // 상태 추적 및 쿨타임용 변수 추가
        var isOverlayShowing = false
        private var lastRemovedTime: Long = 0
        private const val COOL_DOWN_TIME = 2000L // 2초 (통화 종료 직후 2초간은 새 팝업 차단)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL ||
                pkg.contains("telecom") || pkg.contains("dialer") || pkg.contains("incallui")

        if (isCall) {
            val currentTime = System.currentTimeMillis()

            // 통화 종료 직후에 연달아 들어오는 "통화 종료됨" 알림을 무시
            if (currentTime - lastRemovedTime < COOL_DOWN_TIME) {
                Log.d("CallDebug", "통화 종료 직후 잔여 알림 무시 (쿨타임 중)")
                return
            }

            // 이미 오버레이가 떠 있다면 다시 띄우지 않음
            if (isOverlayShowing) {
                Log.d("CallDebug", "이미 팝업이 떠 있어서 중복 실행 방지")
                return
            }

            val actions = sbn.notification.actions
            Log.d("CallDebug", "전화 알림 감지! 패키지: $pkg, 버튼 수: ${actions?.size ?: 0}")

            actions?.forEachIndexed { index, action ->
                val title = action.title?.toString()?.lowercase() ?: ""
                Log.d("CallDebug", "버튼[$index]: $title")

                if (title.contains("받기") || title.contains("answer") || title.contains("수신") || title.contains("accept")) {
                    answerIntent = action.actionIntent
                } else if (title.contains("거절") || title.contains("끊기") || title.contains("decline") || title.contains("hang")) {
                    declineIntent = action.actionIntent
                }
            }

            Log.d("CallDebug", "오버레이 서비스 시작")
            isOverlayShowing = true

            val intent = Intent(this, CallOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL ||
                pkg.contains("telecom") || pkg.contains("dialer") || pkg.contains("incallui")

        if (isCall) {
            Log.d("CallDebug", "전화 알림 제거 감지 -> 오버레이 종료 트리거")

            // 서비스 종료 호출
            stopService(Intent(this, CallOverlayService::class.java))

            // 상태 초기화 및 쿨타임 시작 시간 기록
            answerIntent = null
            declineIntent = null
            isOverlayShowing = false
            lastRemovedTime = System.currentTimeMillis()
        }
    }
}