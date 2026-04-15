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

    private var orgCode = ""
    private var companyName = ""
    private var isUsernameChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // 뷰 초기화
        val layoutStep1 = findViewById<LinearLayout>(R.id.layout_step1)
        val layoutStep2 = findViewById<LinearLayout>(R.id.layout_step2)
        val tvCompanyName = findViewById<TextView>(R.id.tv_company_name)
        val tvWelcomeMsg = findViewById<TextView>(R.id.tv_welcome_msg)
        val etOrgCode = findViewById<EditText>(R.id.et_org_code)
        val btnNextStep1 = findViewById<Button>(R.id.btn_next_step1)
        val etUsername = findViewById<EditText>(R.id.et_username)
        val btnCheckUsername = findViewById<Button>(R.id.btn_check_username)

        // 🚀 STEP 1: 조직코드 조회 API 연동
        btnNextStep1.setOnClickListener {
            orgCode = etOrgCode.text.toString().trim()
            if (orgCode.isEmpty()) return@setOnClickListener

            btnNextStep1.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("http://swc.ddns.net:8000/organizations/lookup?org_code=$orgCode")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(responseData)

                        // 💡 org_name 키로 회사명 가져오기
                        companyName = jsonResponse.optString("org_name", "조직")

                        withContext(Dispatchers.Main) {
                            btnNextStep1.isEnabled = true
                            tvCompanyName.text = companyName
                            tvWelcomeMsg.visibility = View.VISIBLE
                            layoutStep1.visibility = View.GONE
                            layoutStep2.visibility = View.VISIBLE
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            btnNextStep1.isEnabled = true
                            Toast.makeText(this@RegisterActivity, "유효하지 않은 조직코드입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnNextStep1.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 🚀 아이디 중복확인 API 연동
        btnCheckUsername.setOnClickListener {
            val inputId = etUsername.text.toString().trim()
            if (inputId.isEmpty()) return@setOnClickListener

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("http://swc.ddns.net:8000/auth/check-username?username=$inputId")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"

                    val responseCode = conn.responseCode
                    withContext(Dispatchers.Main) {
                        if (responseCode == 200) {
                            isUsernameChecked = true
                            Toast.makeText(this@RegisterActivity, "사용 가능한 아이디입니다.", Toast.LENGTH_SHORT).show()
                            btnCheckUsername.text = "확인완료"
                            btnCheckUsername.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#03C75A"))
                        } else {
                            Toast.makeText(this@RegisterActivity, "이미 사용 중인 아이디입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@RegisterActivity, "통신 에러", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        // 나머지 인증 및 가입 로직 생략 (기존과 동일하게 작동)
    }
}