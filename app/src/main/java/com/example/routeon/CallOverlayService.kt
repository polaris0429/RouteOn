package com.example.routeon

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class CallOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "CALL_CHANNEL")
            .setContentTitle("전화 제어 활성 중")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(101, notification)
        }

        if (Settings.canDrawOverlays(this)) {
            if (overlayView == null) showOverlay()
        } else {
            Log.e("CallDebug", "오버레이 권한 없음. 서비스 중단.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                // ✅ 화면 위치를 하단으로 변경
                gravity = Gravity.BOTTOM
                // 하단에서 살짝 띄우려면 y 값을 설정 (원하지 않으면 0으로)
                y = 100
            }

            overlayView = LayoutInflater.from(this).inflate(R.layout.layout_call_overlay, null)

            val btnAnswer = overlayView!!.findViewById<Button>(R.id.btnAnswer)
            val btnDecline = overlayView!!.findViewById<Button>(R.id.btnDecline)
            val btnEndCall = overlayView!!.findViewById<Button>(R.id.btnEndCall)
            val layoutActive = overlayView!!.findViewById<LinearLayout>(R.id.layoutActiveCall)
            val tvStatus = overlayView!!.findViewById<TextView>(R.id.tvCallStatus)

            // 받기 버튼 로직
            btnAnswer.setOnClickListener {
                Log.d("CallDebug", "받기 클릭됨")
                var answered = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            telecomManager.acceptRingingCall()
                            answered = true
                            Log.d("CallDebug", "TelecomManager로 받기 성공")
                        } catch (e: Exception) {
                            Log.e("CallDebug", "TelecomManager 받기 실패: ${e.message}")
                        }
                    }
                }

                if (!answered && CallNotificationListener.answerIntent != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val options = ActivityOptions.makeBasic()
                            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                            CallNotificationListener.answerIntent?.send(options.toBundle())
                        } else {
                            CallNotificationListener.answerIntent?.send()
                        }
                        Log.d("CallDebug", "알림 인텐트로 받기 시도")
                    } catch (e: Exception) {
                        Log.e("CallDebug", "인텐트 전송 실패: ${e.message}")
                    }
                }

                // UI 업데이트 (스피커폰 관련 텍스트 제거)
                tvStatus.text = "통화 중"
                btnAnswer.visibility = View.GONE
                btnDecline.visibility = View.GONE
                layoutActive.visibility = View.VISIBLE
            }

            // 거절/종료 버튼 로직
            val declineAction = View.OnClickListener {
                Log.d("CallDebug", "거절/종료 클릭됨")
                var ended = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            telecomManager.endCall()
                            ended = true
                            Log.d("CallDebug", "TelecomManager로 통화 종료 성공")
                        } catch (e: Exception) {
                            Log.e("CallDebug", "TelecomManager 종료 실패: ${e.message}")
                        }
                    }
                }

                if (!ended && CallNotificationListener.declineIntent != null) {
                    try {
                        CallNotificationListener.declineIntent?.send()
                        Log.d("CallDebug", "알림 인텐트로 거절/종료 시도")
                    } catch (e: Exception) {
                        Log.e("CallDebug", "거절 인텐트 실패: ${e.message}")
                    }
                }
                stopSelf()
            }

            btnDecline.setOnClickListener(declineAction)
            btnEndCall.setOnClickListener(declineAction)

            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("CallDebug", "오버레이 띄우기 실패: ${e.message}")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("CALL_CHANNEL", "Call Control", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        CallNotificationListener.isOverlayShowing = false
    }
}