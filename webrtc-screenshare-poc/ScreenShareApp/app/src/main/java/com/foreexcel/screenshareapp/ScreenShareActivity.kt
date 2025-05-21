package com.foreexcel.screenshareapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.foreexcel.screenshareapp.databinding.ActivityScreenShareBinding
import com.foreexcel.screenshareapp.services.ScreenShareService

class ScreenShareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScreenShareActivity"
    }

    private lateinit var binding: ActivityScreenShareBinding
    private var screenShareService: ScreenShareService? = null
    private var bound = false
    private var roomId: String? = null
    private var isJoining = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenShareService.LocalBinder
            screenShareService = binder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            screenShareService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get room ID and mode from intent
        roomId = intent.getStringExtra("roomId")
        isJoining = intent.getBooleanExtra("isJoining", false)

        if (roomId == null) {
            Toast.makeText(this, "Room ID not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Bind to the service
        bindService()

        // Setup UI based on mode
        setupUI()

        // Setup listeners
        binding.btnEndCall.setOnClickListener {
            endSession()
        }

        binding.btnShareRoom.setOnClickListener {
            shareRoomId()
        }

        binding.btnCopyRoomId.setOnClickListener {
            copyRoomId()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService()
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun bindService() {
        Intent(this, ScreenShareService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun setupUI() {
        binding.tvRoomId.text = roomId

        if (isJoining) {
            binding.tvRoomIdLabel.text = "Joined Room:"
            binding.tvStatus.text = "Waiting for screen sharing to start..."
            binding.btnEndCall.text = "Leave Room"
        } else {
            binding.tvRoomIdLabel.text = "Sharing Room:"
            binding.tvStatus.text = "Sharing your screen and audio..."
            binding.btnEndCall.text = "Stop Sharing"
        }
    }

    private fun endSession() {
        if (!isJoining) {
            // Stop screen sharing
            val intent = Intent(this, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_STOP_SCREEN_SHARE
            }
            startService(intent)
        }

        // Go back to main activity
        finish()
    }

    private fun shareRoomId() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join my screen sharing session")
            putExtra(Intent.EXTRA_TEXT, "Join my screen sharing session with ID: $roomId")
        }
        startActivity(Intent.createChooser(shareIntent, "Share Room ID"))
    }

    private fun copyRoomId() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Room ID", roomId)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Room ID copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // Prevent accidental back press by showing a confirmation toast
        Toast.makeText(this, "Use the End button to stop sharing", Toast.LENGTH_SHORT).show()
    }
}