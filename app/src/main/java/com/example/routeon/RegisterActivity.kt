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
import com.example.routeon.BuildConfig

class RegisterActivity : AppCompatActivity() {

    private var generatedCode = ""
    private lateinit var otpBoxes: Array<EditText>

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
        // 🚀 STEP 1 -> STEP 2 (조직코드 실제 API 연동)
        // ==========================================
        btnNextStep1.setOnClickListener {
            orgCode = etOrgCode.text.toString().trim()
            if (orgCode.isEmpty()) {
                Toast.makeText(this, "조직코드를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnNextStep1.isEnabled = false
            Toast.makeText(this, "조직코드를 확인 중입니다...", Toast.LENGTH_SHORT).show()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 💡 신규 추가된 기업명 조회 API 호출
                    val url = URL("http://swc.ddns.net:8000/organizations/lookup?org_code=$orgCode")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000

                    val responseCode = conn.responseCode
                    if (responseCode == 200) {
                        val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(responseData)
                        companyName = jsonResponse.optString("name", "조직")

                        withContext(Dispatchers.Main) {
                            btnNextStep1.isEnabled = true
                            tvCompanyName.text = "$companyName \n기사님 환영합니다!"
                            layoutStep1.visibility = View.GONE
                            layoutStep2.visibility = View.VISIBLE
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            btnNextStep1.isEnabled = true
                            Toast.makeText(this@RegisterActivity, "유효하지 않거나 존재하지 않는 조직코드입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnNextStep1.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "서버 연결에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ==========================================
        // 🚀 아이디 중복확인 (실제 API 연동)
        // ==========================================
        btnCheckUsername.setOnClickListener {
            val inputId = etUsername.text.toString().trim()
            if (inputId.isEmpty()) {
                Toast.makeText(this, "아이디를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnCheckUsername.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 💡 신규 추가된 아이디 중복 확인 API 호출
                    val url = URL("http://swc.ddns.net:8000/auth/check-username?username=$inputId")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000

                    val responseCode = conn.responseCode
                    withContext(Dispatchers.Main) {
                        if (responseCode == 200) {
                            isUsernameChecked = true
                            Toast.makeText(this@RegisterActivity, "사용 가능한 아이디입니다.", Toast.LENGTH_SHORT).show()
                            btnCheckUsername.text = "확인완료"
                            btnCheckUsername.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#03C75A"))
                        } else {
                            btnCheckUsername.isEnabled = true
                            Toast.makeText(this@RegisterActivity, "이미 사용 중인 아이디입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnCheckUsername.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "중복확인 중 서버 에러가 발생했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 💡 아이디를 다시 수정하면 중복확인을 다시 받도록 상태 초기화
        etUsername.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isUsernameChecked) {
                    isUsernameChecked = false
                    btnCheckUsername.isEnabled = true
                    btnCheckUsername.text = "중복확인"
                    btnCheckUsername.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333"))
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ==========================================
        // 🚀 STEP 2 -> STEP 3
        // ==========================================
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
        // 🚀 STEP 3: SMS 인증 후 최종 가입
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
                    val apiKey = BuildConfig.SOLAPI_API_KEY
                    val apiSecret = BuildConfig.SOLAPI_API_SECRET

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
            finish()
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