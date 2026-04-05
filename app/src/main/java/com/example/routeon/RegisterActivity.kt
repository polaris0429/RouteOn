package com.example.routeon

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RegisterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 💡 앱 실행 시 강제 화이트 테마 적용 (가독성 유지)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val etOrgCode = findViewById<EditText>(R.id.et_org_code)
        val etUsername = findViewById<EditText>(R.id.et_username) // email -> username 으로 변경
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etPhone = findViewById<EditText>(R.id.et_phone)
        val btnRegister = findViewById<Button>(R.id.btn_register)

        btnRegister.setOnClickListener {
            val orgCode = etOrgCode.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val phone = etPhone.text.toString().trim()

            if (orgCode.isEmpty() || username.isEmpty() || password.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "모든 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnRegister.isEnabled = false
            Toast.makeText(this, "서버에 가입을 요청 중입니다...", Toast.LENGTH_SHORT).show()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 💡 DB_SCHEMA.md에 명시된 실제 백엔드 서버 주소와 새 엔드포인트 적용
                    val url = URL("http://swc.ddns.net:8000/auth/register")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true

                    // 💡 새 API 스펙(RegisterRequest)에 맞춘 JSON 데이터 조립
                    val jsonParam = JSONObject().apply {
                        put("username", username)
                        put("password", password)
                        put("phone", phone)
                        put("org_code", orgCode)
                        put("role", "driver") // 기사 회원가입임을 명시
                    }

                    OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                    val responseCode = conn.responseCode
                    // API 명세 상 201 Created 가 성공 응답
                    val responseData = if (responseCode == 201 || responseCode == 200) {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        conn.errorStream.bufferedReader().use { it.readText() }
                    }

                    withContext(Dispatchers.Main) {
                        btnRegister.isEnabled = true

                        if (responseCode == 201 || responseCode == 200) {
                            // (참고) 새 백엔드 응답에서 자동/대기 승인 상태(status)를 내려준다면
                            // 기존처럼 파싱해서 AlertDialog를 띄울 수 있습니다.
                            // 현재 스키마 상으로는 성공 시 201을 내려주므로 바로 로그인 또는 메인으로 이동시킵니다.
                            Toast.makeText(this@RegisterActivity, "가입이 완료되었습니다!", Toast.LENGTH_SHORT).show()
                            val intent = Intent(this@RegisterActivity, MainActivity::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            // FastAPI 등에서 내려주는 에러 메시지(detail) 파싱
                            val jsonResponse = JSONObject(responseData)
                            val msg = jsonResponse.optString("detail", "가입에 실패했습니다.")
                            Toast.makeText(this@RegisterActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        btnRegister.isEnabled = true
                        Toast.makeText(this@RegisterActivity, "서버 연결 실패 (네트워크를 확인하세요)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}