package com.example.routeon

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RegisterActivity : AppCompatActivity() {

    private var generatedCode = ""
    private var isPhoneVerified = false
    private lateinit var otpBoxes: Array<EditText>

    // 💡 팝업 없이 백그라운드에서 조용히 문자를 낚아채는 리시버
    private val silentSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val messageBody = sms.messageBody
                    // RouteOn에서 보낸 문자인지 확인
                    if (messageBody.contains("[RouteOn]")) {
                        val code = Regex("\\d{6}").find(messageBody)?.value

                        if (code != null) {
                            for (i in 0 until 6) {
                                otpBoxes[i].setText(code[i].toString())
                            }
                            // 자동으로 인증 확인 버튼 클릭
                            findViewById<Button>(R.id.btn_verify_sms).performClick()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // 앱 시작 시 SMS 읽기 권한 요청
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ), 101)
        }

        val etOrgCode = findViewById<EditText>(R.id.et_org_code)
        val etUsername = findViewById<EditText>(R.id.et_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etPhone = findViewById<EditText>(R.id.et_phone)
        val btnRegister = findViewById<Button>(R.id.btn_register)

        val btnSendSms = findViewById<Button>(R.id.btn_send_sms)
        val layoutVerification = findViewById<LinearLayout>(R.id.layout_verification)
        val btnVerifySms = findViewById<Button>(R.id.btn_verify_sms)

        otpBoxes = arrayOf(
            findViewById(R.id.otp1), findViewById(R.id.otp2), findViewById(R.id.otp3),
            findViewById(R.id.otp4), findViewById(R.id.otp5), findViewById(R.id.otp6)
        )
        setupOtpInputs()

        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(silentSmsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(silentSmsReceiver, filter)
        }

        // ==========================================
        // 1. 인증번호 발송 (SDK 없이 다이렉트 HTTP 요청!)
        // ==========================================
        btnSendSms.setOnClickListener {
            val phone = etPhone.text.toString().trim().replace("-", "")
            if (phone.isEmpty()) {
                Toast.makeText(this, "휴대전화 번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            generatedCode = (100000..999999).random().toString()
            btnSendSms.isEnabled = false
            Toast.makeText(this, "인증번호를 발송 중입니다...", Toast.LENGTH_SHORT).show()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 🚨 (주의) 테스트 완료 후 실제 서비스 배포 시 API 키는 백엔드로 숨기셔야 합니다.
                    val apiKey = "NCS5JONCEGRELVCQ"
                    val apiSecret = "YLPZWOHL2V3ZXI4YBJGCAF3DUB9GNSGK"

                    // 솔라피 인증용 서명(Signature) 생성
                    val salt = UUID.randomUUID().toString().replace("-", "")
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val date = sdf.format(Date())

                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
                    val signature = mac.doFinal((date + salt).toByteArray()).joinToString("") {
                        String.format("%02x", (it.toInt() and 0xFF))
                    }
                    val authHeader = "HMAC-SHA256 apiKey=$apiKey, date=$date, salt=$salt, signature=$signature"

                    // 솔라피 서버로 문자 전송 요청
                    val url = URL("https://api.solapi.com/messages/v4/send")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", authHeader)
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true

                    val messageObj = JSONObject().apply {
                        put("to", phone)
                        put("from", "01057022581")
                        put("text", "[RouteOn] 기사 가입 인증번호는 [$generatedCode] 입니다.")
                    }
                    val jsonParam = JSONObject().apply { put("message", messageObj) }

                    OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                    val responseCode = conn.responseCode
                    withContext(Dispatchers.Main) {
                        btnSendSms.isEnabled = true
                        if (responseCode == 200) {
                            layoutVerification.visibility = View.VISIBLE
                            otpBoxes[0].requestFocus()
                            Toast.makeText(this@RegisterActivity, "인증번호 발송 완료! (자동입력 대기중)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@RegisterActivity, "발송 실패 (코드: $responseCode)", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnSendSms.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "문자 발송 네트워크 에러", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // ==========================================
        // 2. 인증번호 직접 확인
        // ==========================================
        btnVerifySms.setOnClickListener {
            val inputCode = otpBoxes.joinToString("") { it.text.toString() }

            if (inputCode == generatedCode && inputCode.length == 6) {
                isPhoneVerified = true
                Toast.makeText(this, "인증이 완료되었습니다.", Toast.LENGTH_SHORT).show()

                etPhone.isEnabled = false
                otpBoxes.forEach { it.isEnabled = false }
                btnSendSms.isEnabled = false
                btnVerifySms.isEnabled = false
            } else {
                Toast.makeText(this, "인증번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // ==========================================
        // 3. 회원가입 서버 전송
        // ==========================================
        btnRegister.setOnClickListener {
            if (!isPhoneVerified) {
                Toast.makeText(this, "휴대폰 인증을 먼저 완료해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val orgCode = etOrgCode.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            if (orgCode.isEmpty() || username.isEmpty() || password.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnRegister.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("http://swc.ddns.net:8000/auth/register")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true

                    val jsonParam = JSONObject().apply {
                        put("username", username)
                        put("password", password)
                        put("phone", phone)
                        put("org_code", orgCode)
                        put("role", "driver")
                    }

                    OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                    val responseCode = conn.responseCode
                    withContext(Dispatchers.Main) {
                        btnRegister.isEnabled = true
                        if (responseCode == 201 || responseCode == 200) {
                            Toast.makeText(this@RegisterActivity, "가입 완료! 로그인 해주세요.", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            Toast.makeText(this@RegisterActivity, "가입 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        btnRegister.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupOtpInputs() {
        for (i in otpBoxes.indices) {
            otpBoxes[i].addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < 5) {
                        otpBoxes[i + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            otpBoxes[i].setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (otpBoxes[i].text.isEmpty() && i > 0) {
                        otpBoxes[i - 1].requestFocus()
                        otpBoxes[i - 1].text.clear()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(silentSmsReceiver)
        } catch (_: Exception) {}
    }
}