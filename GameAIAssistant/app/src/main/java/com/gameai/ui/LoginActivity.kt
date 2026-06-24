package com.gameai.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gameai.databinding.ActivityLoginBinding
import com.gameai.utils.PreferencesManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 已登录则跳过
        if (isLoggedIn()) {
            goToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (username.isEmpty()) {
                Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 本地简单验证，存储登录状态
            saveLogin(username)
            Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
            goToMain()
        }

        binding.btnSkip.setOnClickListener {
            goToMain()
        }
    }

    private fun saveLogin(username: String) {
        getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .edit()
            .putString("username", username)
            .putBoolean("logged_in", true)
            .putLong("login_time", System.currentTimeMillis())
            .apply()
    }

    private fun isLoggedIn(): Boolean {
        return getSharedPreferences("lookgm_auth", Context.MODE_PRIVATE)
            .getBoolean("logged_in", false)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
