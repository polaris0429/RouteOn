package com.example.routeon

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatDelegate

class PendingActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)

        findViewById<Button>(R.id.btn_back_to_login).setOnClickListener {
            finish()
        }
    }
}
