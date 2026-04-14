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
            val usernameStr = etUsername.text.toString().trim()
            val passwordStr = etPassword.text.toString().trim()

            if (usernameStr.isEmpty() || passwordStr.isEmpty()) {
                Toast.makeText(this, "아이디와 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // ====================================================
                    // 💡 수정된 부분: 폼 데이터가 아닌 JSON 형식으로 전송합니다.
                    // ====================================================
                    val loginUrl = URL("http://swc.ddns.net:8000/auth/login")
                    val loginConn = loginUrl.openConnection() as HttpURLConnection
                    loginConn.requestMethod = "POST"
                    loginConn.setRequestProperty("Content-Type", "application/json") // JSON 지정
                    loginConn.doOutput = true

                    // JSON 객체 생성
                    val loginJson = JSONObject().apply {
                        put("username", usernameStr)
                        put("password", passwordStr)
                    }
                    OutputStreamWriter(loginConn.outputStream).use { it.write(loginJson.toString()) }
                    // ====================================================

                    if (loginConn.responseCode == 200) {
                        val response = loginConn.inputStream.bufferedReader().use { it.readText() }
                        val accessToken = JSONObject(response).getString("access_token")

                        // 2. 내 정보 조회 API 호출
                        val meUrl = URL("http://swc.ddns.net:8000/auth/me")
                        val meConn = meUrl.openConnection() as HttpURLConnection
                        meConn.requestMethod = "GET"
                        meConn.setRequestProperty("Authorization", "Bearer $accessToken")

                        if (meConn.responseCode == 200) {
                            val userResponse = meConn.inputStream.bufferedReader().use { it.readText() }
                            val userJson = JSONObject(userResponse)

                            val role = userJson.optString("role")
                            val userId = userJson.optString("id")
                            val userName = userJson.optString("username")

                            withContext(Dispatchers.Main) {
                                btnLogin.isEnabled = true

                                if (role == "pending") {
                                    val intent = Intent(this@LoginActivity, PendingActivity::class.java)
                                    startActivity(intent)
                                } else {
                                    sharedPref.edit().apply {
                                        putString("access_token", accessToken)
                                        putBoolean("isLoggedIn", true)
                                        putString("username", userName)
                                        putString("role", role)
                                        putString("user_id", userId)
                                        apply()
                                    }

                                    Toast.makeText(this@LoginActivity, "로그인 성공!", Toast.LENGTH_SHORT).show()
                                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                    finish()
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                btnLogin.isEnabled = true
                                Toast.makeText(this@LoginActivity, "사용자 정보를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            btnLogin.isEnabled = true
                            Toast.makeText(this@LoginActivity, "아이디 또는 비밀번호가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, "서버 연결에 실패했습니다.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}