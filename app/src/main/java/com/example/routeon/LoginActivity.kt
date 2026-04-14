package com.example.routeon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("RouteOnPrefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("isLoggedIn", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val etUsername = findViewById<EditText>(R.id.et_username)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val tvGoRegister = findViewById<TextView>(R.id.tv_go_register)

        tvGoRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "아이디와 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 1. 로그인 요청
                    val url = URL("http://swc.ddns.net:8000/auth/login")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true

                    val jsonParam = JSONObject().apply {
                        put("username", username)
                        put("password", password)
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }

                    if (conn.responseCode == 200) {
                        val responseData = conn.inputStream.bufferedReader().use { it.readText() }
                        val token = JSONObject(responseData).optString("access_token", "")

                        // 💡 2. 발급받은 토큰으로 내 정보(/auth/me)를 조회하여 고유 user_id(UUID)를 가져옵니다.
                        val meUrl = URL("http://swc.ddns.net:8000/auth/me")
                        val meConn = meUrl.openConnection() as HttpURLConnection
                        meConn.requestMethod = "GET"
                        meConn.setRequestProperty("Authorization", "Bearer $token")

                        if (meConn.responseCode == 200) {
                            val meData = meConn.inputStream.bufferedReader().use { it.readText() }
                            val userId = JSONObject(meData).optString("id", "")

                            // 토큰과 user_id를 함께 저장! (위치 전송에 사용됨)
                            sharedPref.edit().apply {
                                putBoolean("isLoggedIn", true)
                                putString("username", username)
                                putString("access_token", token)
                                putString("user_id", userId)
                                apply()
                            }

                            withContext(Dispatchers.Main) {
                                btnLogin.isEnabled = true
                                Toast.makeText(this@LoginActivity, "로그인 성공!", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                btnLogin.isEnabled = true
                                Toast.makeText(this@LoginActivity, "유저 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            btnLogin.isEnabled = true
                            Toast.makeText(this@LoginActivity, "아이디 또는 비밀번호가 틀렸습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, "서버 연결 실패", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}