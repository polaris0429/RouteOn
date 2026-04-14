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
import android.widget.TextView
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
    private lateinit var otpBoxes: Array<EditText>

    // 저장할 데이터들
    private var orgCode = ""
    private var companyName = ""
    private var username = ""
    private var password = ""
    private var isUsernameChecked = false

    private val silentSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val messageBody = sms.messageBody
                    if (messageBody.contains("[RouteOn]")) {
                        val code = Regex("\\d{6}").find(messageBody)?.value
                        if (code != null) {
                            for (i in 0 until 6) {
                                otpBoxes[i].setText(code[i].toString())
                            }
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 101)
        }

        val layoutStep1 = findViewById<LinearLayout>(R.id.layout_step1)
        val layoutStep2 = findViewById<LinearLayout>(R.id.layout_step2)
        val layoutStep3 = findViewById<LinearLayout>(R.id.layout_step3)
        val layoutStep4 = findViewById<LinearLayout>(R.id.layout_step4)

        // Step 1
        val etOrgCode = findViewById<EditText>(R.id.et_org_code)
        val btnNextStep1 = findViewById<Button>(R.id.btn_next_step1)

        // Step 2
        val tvCompanyName = findViewById<TextView>(R.id.tv_company_name)
        val etName = findViewById<EditText>(R.id.et_name)
        val etUsername = findViewById<EditText>(R.id.et_username)
        val btnCheckUsername = findViewById<Button>(R.id.btn_check_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etPasswordConfirm = findViewById<EditText>(R.id.et_password_confirm)
        val btnNextStep2 = findViewById<Button>(R.id.btn_next_step2)

        // Step 3
        val etPhone = findViewById<EditText>(R.id.et_phone)
        val btnSendSms = findViewById<Button>(R.id.btn_send_sms)
        val layoutVerification = findViewById<LinearLayout>(R.id.layout_verification)
        val btnVerifySms = findViewById<Button>(R.id.btn_verify_sms)

        // Step 4
        val btnGoToLogin = findViewById<Button>(R.id.btn_go_to_login)

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
        // 🚀 STEP 1 -> STEP 2 (조직코드 확인)
        // ==========================================
        btnNextStep1.setOnClickListener {
            orgCode = etOrgCode.text.toString().trim()
            if (orgCode.isEmpty()) {
                Toast.makeText(this, "조직코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 💡 [안내] 백엔드 명세서 상 조직코드로 기업명을 조회하는 단독 API가 명시되어 있지 않아,
            // 일단 화면 전환이 되도록 회사명을 임의 지정했습니다. 백엔드 팀원에게 "조직코드 조회 API"를 요청해주세요!
            companyName = "등록된 물류회사"

            tvCompanyName.text = "$companyName 기사님 환영합니다!"
            layoutStep1.visibility = View.GONE
            layoutStep2.visibility = View.VISIBLE
        }

        // ==========================================
        // 🚀 STEP 2 -> STEP 3 (아이디/비밀번호 확인)
        // ==========================================
        btnCheckUsername.setOnClickListener {
            val inputId = etUsername.text.toString().trim()
            if (inputId.isEmpty()) {
                Toast.makeText(this, "아이디를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 💡 [안내] 명세서에 아이디 중복확인 API가 없어 통과되도록 임시 처리했습니다.
            isUsernameChecked = true
            Toast.makeText(this, "사용 가능한 아이디입니다.", Toast.LENGTH_SHORT).show()
            btnCheckUsername.text = "확인완료"
            btnCheckUsername.isEnabled = false
        }

        btnNextStep2.setOnClickListener {
            val name = etName.text.toString().trim()
            username = etUsername.text.toString().trim()
            password = etPassword.text.toString().trim()
            val passwordConfirm = etPasswordConfirm.text.toString().trim()

            if (name.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isUsernameChecked) {
                Toast.makeText(this, "아이디 중복확인을 진행해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password != passwordConfirm) {
                Toast.makeText(this, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            layoutStep2.visibility = View.GONE
            layoutStep3.visibility = View.VISIBLE
        }

        // ==========================================
        // 🚀 STEP 3: SMS 인증 후 최종 가입 (API 전송)
        // ==========================================
        btnSendSms.setOnClickListener {
            val phone = etPhone.text.toString().trim().replace("-", "")
            if (phone.isEmpty()) {
                Toast.makeText(this, "휴대전화 번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            generatedCode = (100000..999999).random().toString()
            btnSendSms.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val apiKey = "NCS5JONCEGRELVCQ"
                    val apiSecret = "YLPZWOHL2V3ZXI4YBJGCAF3DUB9GNSGK"

                    val salt = UUID.randomUUID().toString().replace("-", "")
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val date = sdf.format(Date())

                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
                    val signature = mac.doFinal((date + salt).toByteArray()).joinToString("") { String.format("%02x", (it.toInt() and 0xFF)) }
                    val authHeader = "HMAC-SHA256 apiKey=$apiKey, date=$date, salt=$salt, signature=$signature"

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
                            Toast.makeText(this@RegisterActivity, "인증번호 발송 완료!", Toast.LENGTH_SHORT).show()
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

        btnVerifySms.setOnClickListener {
            val inputCode = otpBoxes.joinToString("") { it.text.toString() }
            val phone = etPhone.text.toString().trim()

            if (inputCode == generatedCode && inputCode.length == 6) {
                // 💡 인증 성공 즉시 백엔드로 회원가입 데이터 전송!
                btnVerifySms.isEnabled = false
                registerUserOnServer(username, password, phone, orgCode)
            } else {
                Toast.makeText(this, "인증번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // ==========================================
        // 🚀 STEP 4: 가입 완료 화면 -> 로그인 이동
        // ==========================================
        btnGoToLogin.setOnClickListener {
            finish() // 현재 회원가입 창을 닫고 로그인 창으로 돌아갑니다.
        }
    }

    private fun registerUserOnServer(usernameStr: String, passwordStr: String, phoneStr: String, orgCodeStr: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://swc.ddns.net:8000/auth/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                // 백엔드 명세서에 맞춘 JSON 바디 (role: driver)
                val jsonParam = JSONObject().apply {
                    put("username", usernameStr)
                    put("password", passwordStr)
                    put("phone", phoneStr)
                    put("org_code", orgCodeStr)
                    put("role", "driver")
                }

                OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                val responseCode = conn.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode == 201 || responseCode == 200) {
                        // 💡 가입 성공 시 STEP 4 화면 띄우기
                        findViewById<LinearLayout>(R.id.layout_step3).visibility = View.GONE
                        findViewById<LinearLayout>(R.id.layout_step4).visibility = View.VISIBLE
                    } else {
                        findViewById<Button>(R.id.btn_verify_sms).isEnabled = true
                        Toast.makeText(this@RegisterActivity, "가입 실패. (조직코드를 다시 확인해주세요)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<Button>(R.id.btn_verify_sms).isEnabled = true
                    Toast.makeText(this@RegisterActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupOtpInputs() {
        for (i in otpBoxes.indices) {
            otpBoxes[i].addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s?.length == 1 && i < 5) otpBoxes[i + 1].requestFocus()
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
        try { unregisterReceiver(silentSmsReceiver) } catch (_: Exception) {}
    }
}