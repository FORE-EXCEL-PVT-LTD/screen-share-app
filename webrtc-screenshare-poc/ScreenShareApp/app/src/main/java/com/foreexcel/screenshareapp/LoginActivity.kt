package com.foreexcel.screenshareapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.foreexcel.screenshareapp.api.ApiClient
import com.foreexcel.screenshareapp.api.LoginRequest
import com.foreexcel.screenshareapp.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if already logged in
        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPrefs.getString("token", null)
        if (token != null) {
            navigateToMainActivity()
            return
        }

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString()
            val password = binding.etPassword.text.toString()

            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Username and password are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginUser(username, password)
        }

        binding.btnRegister.setOnClickListener {
            val username = binding.etUsername.text.toString()
            val password = binding.etPassword.text.toString()

            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Username and password are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            registerUser(username, password)
        }
    }

    private fun loginUser(username: String, password: String) {
        setLoading(true)

        lifecycleScope.launch {
            try {
                val request = LoginRequest(username, password)
                val response = ApiClient.authService.login(request)

                if (response.isSuccessful) {
                    val userData = response.body()
                    if (userData != null) {
                        // Save auth data
                        saveUserData(userData.token, userData.userId, userData.username)
                        Toast.makeText(this@LoginActivity, "Login successful", Toast.LENGTH_SHORT).show()
                        navigateToMainActivity()
                    } else {
                        Toast.makeText(this@LoginActivity, "Invalid response from server", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, "Login failed: ${response.message()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error: ${e.message}")
                Toast.makeText(this@LoginActivity, "Login failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun registerUser(username: String, password: String) {
        setLoading(true)

        lifecycleScope.launch {
            try {
                val request = LoginRequest(username, password)
                val response = ApiClient.authService.register(request)

                if (response.isSuccessful) {
                    val userData = response.body()
                    if (userData != null) {
                        // Save auth data
                        saveUserData(userData.token, userData.userId, userData.username)
                        Toast.makeText(this@LoginActivity, "Registration successful", Toast.LENGTH_SHORT).show()
                        navigateToMainActivity()
                    } else {
                        Toast.makeText(this@LoginActivity, "Invalid response from server", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, "Registration failed: ${response.message()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration error: ${e.message}")
                Toast.makeText(this@LoginActivity, "Registration failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun saveUserData(token: String, userId: String, username: String) {
        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putString("token", token)
            putString("userId", userId)
            putString("username", username)
            apply()
        }
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun setLoading(isLoading: Boolean) {
        // Update UI to show loading state
        binding.btnLogin.isEnabled = !isLoading
        binding.btnRegister.isEnabled = !isLoading
        binding.etUsername.isEnabled = !isLoading
        binding.etPassword.isEnabled = !isLoading

        // Show or hide loading indicator
        if (isLoading) {
            binding.btnLogin.text = "Loading..."
            binding.btnRegister.visibility = View.INVISIBLE
        } else {
            binding.btnLogin.text = "Login"
            binding.btnRegister.visibility = View.VISIBLE
        }
    }
}