package com.foreexcel.screenshareapp


import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.foreexcel.screenshareapp.databinding.ActivityMainBinding
import com.foreexcel.screenshareapp.services.ScreenShareService
import java.util.*

class MainActivity : AppCompatActivity() {


    companion object {
        private const val TAG = "MainActivity"
        private const val CAPTURE_PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private var screenShareService: ScreenShareService? = null
    private var bound = false
    private var roomId: String? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenShareService.LocalBinder
            screenShareService = binder.getService()
            bound = true

            // Register user with socket
            val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val userId = sharedPrefs.getString("userId", "") ?: ""
            val username = sharedPrefs.getString("username", "") ?: ""

            if (userId.isNotBlank() && username.isNotBlank()) {
                screenShareService?.registerUser(userId, username)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            screenShareService = null
        }
    }

    // Permission launcher for foreground service
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, proceed with screen capture
            startScreenCapture()
        } else {
            // Permission denied, show message
            Toast.makeText(this, "Foreground service permission is required for screen sharing", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize MediaProjectionManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Start and bind to the service
        startAndBindService()

        binding.btnStartSharing.setOnClickListener {
            val roomIdInput = binding.etRoomId.text.toString()
            if (roomIdInput.isBlank()) {
                // Generate a random room ID if not provided
                roomId = UUID.randomUUID().toString().substring(0, 8)
                binding.etRoomId.setText(roomId)
            } else {
                roomId = roomIdInput
            }

            // Create room via service
            screenShareService?.createRoom(roomId!!)

            // Request screen capture permission
            startScreenCapture()
        }

        binding.btnJoinRoom.setOnClickListener {
            val roomIdInput = binding.etRoomId.text.toString()
            if (roomIdInput.isBlank()) {
                Toast.makeText(this, "Please enter a Room ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            roomId = roomIdInput
            screenShareService?.joinRoom(roomId!!)

            // Navigate to ScreenShareActivity
            val intent = Intent(this, ScreenShareActivity::class.java).apply {
                putExtra("roomId", roomId)
                putExtra("isJoining", true)
            }
            startActivity(intent)
        }

        binding.btnLogout.setOnClickListener {
            logout()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            startAndBindService()
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun startAndBindService() {
        // Start the service
        val serviceIntent = Intent(this, ScreenShareService::class.java)
        startService(serviceIntent)

        // Bind to the service
        Intent(this, ScreenShareService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    // Update your code where you start the foreground service
    private fun prepareScreenCapture() {
        // For Android 13 and above, request the foreground service permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check if we already have the permission
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startScreenCapture()
            } else {
                // Request the permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // For older Android versions, directly start screen capture
            startScreenCapture()
        }
    }

    private fun startScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            // Start foreground service for Android 12+
            val intent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START_FOREGROUND
            }
            startForegroundService(intent) // Use startForegroundService instead of startService
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Android 8-11
            // Start foreground service for Android 8-11
            val intent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START_FOREGROUND
            }
            startForegroundService(intent)
        } else {
            // For older Android versions
            val intent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START_FOREGROUND
            }
            startService(intent)
        }

        // Request screen capture permission
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, CAPTURE_PERMISSION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            // Pass the permission result to the service
            val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_INIT_MEDIA_PROJECTION
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
            }
            startService(serviceIntent)

            // Start screen sharing
            roomId?.let { roomId ->
                val sharingIntent = Intent(this, ScreenShareService::class.java).apply {
                    action = ScreenShareService.ACTION_START_SCREEN_SHARE
                    putExtra(ScreenShareService.EXTRA_ROOM_ID, roomId)
                }
                startService(sharingIntent)

                // Navigate to ScreenShareActivity
                val activityIntent = Intent(this, ScreenShareActivity::class.java).apply {
                    putExtra("roomId", roomId)
                    putExtra("isJoining", false)
                }
                startActivity(activityIntent)
            }
        } else if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE) {
            // Handle permission denied
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logout() {
        // Stop service if running
        Intent(this, ScreenShareService::class.java).also { intent ->
            intent.action = ScreenShareService.ACTION_STOP_FOREGROUND
            startService(intent)
        }

        // Clear saved credentials
        val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        sharedPrefs.edit().clear().apply()

        // Navigate back to login
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }
}