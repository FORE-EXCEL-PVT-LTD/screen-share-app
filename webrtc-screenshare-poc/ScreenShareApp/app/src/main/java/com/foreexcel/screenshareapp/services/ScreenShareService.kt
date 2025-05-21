package com.foreexcel.screenshareapp.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.foreexcel.screenshareapp.MainActivity
import com.foreexcel.screenshareapp.R
import com.foreexcel.screenshareapp.signaling.SocketManager
import com.foreexcel.screenshareapp.webrtc.WebRTCClient
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.util.*

class ScreenShareService : Service(), WebRTCClient.RTCCommunicationListener {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_share_channel"

        const val ACTION_START_FOREGROUND = "com.foreexcel.screenshareapp.START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.foreexcel.screenshareapp.STOP_FOREGROUND"
        const val ACTION_INIT_MEDIA_PROJECTION = "com.foreexcel.screenshareapp.INIT_MEDIA_PROJECTION"
        const val ACTION_START_SCREEN_SHARE = "com.foreexcel.screenshareapp.START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "com.foreexcel.screenshareapp.STOP_SCREEN_SHARE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ROOM_ID = "room_id"
    }

    // Binder to allow activity to communicate with service
    private val binder = LocalBinder()

    // WebRTC and Socket components
    private lateinit var webRTCClient: WebRTCClient
    private val socketManager = SocketManager()
    private var mediaProjection: MediaProjection? = null
    private var currentRoomId: String? = null
    private var remoteSocketId: String? = null

    // Store projection permission data
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    // Handler for running tasks on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    inner class LocalBinder : Binder() {
        fun getService(): ScreenShareService = this@ScreenShareService
    }

    override fun onCreate() {
        super.onCreate()
        webRTCClient = WebRTCClient(applicationContext, this)
        initializeSocketManager()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val notification = createNotification("Initializing screen sharing...")
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP_FOREGROUND -> {
                stopScreenSharing()
                stopForeground(true)
                stopSelf()
            }
            ACTION_INIT_MEDIA_PROJECTION -> {
                // Instead of creating and storing the MediaProjection object,
                // we now store the result code and data intent
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                // No need to create mediaProjection here anymore
            }
            ACTION_START_SCREEN_SHARE -> {
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
                roomId?.let { startScreenSharing(it) }
            }
            ACTION_STOP_SCREEN_SHARE -> {
                stopScreenSharing()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        socketManager.disconnect()
        webRTCClient.dispose()
    }

    private fun initializeSocketManager() {
        socketManager.apply {
            onConnected = {
                Log.d(TAG, "Socket connected")
            }

            onDisconnected = {
                Log.d(TAG, "Socket disconnected")
            }

            onRemoteJoinRequest = { userId, username, socketId ->
                Log.d(TAG, "Join request from ${username ?: "unknown user"} with socket ID $socketId")
                remoteSocketId = socketId
                // Accept the join request
                socketManager.acceptJoinRequest(socketId)
            }

            onRemoteSessionDescription = { sessionDescription ->
                Log.d(TAG, "Remote session description received: ${sessionDescription.type}")
                mainHandler.post {
                    webRTCClient.setRemoteDescription(sessionDescription)
                }
            }

            onRemoteIceCandidate = { iceCandidate ->
                Log.d(TAG, "Remote ICE candidate received")
                mainHandler.post {
                    webRTCClient.addIceCandidate(iceCandidate)
                }
            }

            onSessionEnded = {
                Log.d(TAG, "Session ended")
                stopScreenSharing()
            }

            init()
        }
    }

    fun registerUser(userId: String, username: String) {
        socketManager.registerUser(userId, username)
    }

    fun createRoom(roomId: String) {
        socketManager.createRoom(roomId)
        currentRoomId = roomId
    }

    fun joinRoom(roomId: String) {
        socketManager.joinRoom(roomId)
        currentRoomId = roomId
    }

    // Modified method that uses the stored resultCode and resultData
    fun startScreenSharing(roomId: String) {
        // Check if we have the necessary permission data
        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "Screen capture permission data not available")
            return
        }

        // Initialize WebRTC components
        webRTCClient.initializePeerConnection()
        webRTCClient.setupAudioSource()

        // Start screen capture using the stored permission data
        webRTCClient.startScreenCapture(resultCode, resultData!!)
        updateNotification("Screen sharing active")

        // Notify socket about sharing
        socketManager.startSharing()

        // Create an offer
        webRTCClient.createOffer()
    }

    fun stopScreenSharing() {
        if (currentRoomId == null) {
            Log.d(TAG, "No active sharing to stop")
            return
        }

        Log.d(TAG, "Stopping screen sharing for room: $currentRoomId")
        // Stop WebRTC screen capture
        webRTCClient.stopScreenCapture()

        // End session on socket
        socketManager.endSession()

        // Reset state
        currentRoomId = null
        remoteSocketId = null

        // Update notification
        updateNotification("Screen sharing stopped")
    }

    private fun startForegroundService() {
        val notification = createNotification("Initializing screen sharing...")
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onScreenCaptureEnded() {
        Log.d(TAG, "Screen capture ended callback received")

        // Update notification
        updateNotification("Screen sharing stopped")

        // Notify socket about end of session
        if (currentRoomId != null) {
            socketManager.endSession()
            currentRoomId = null
        }

        // If you want to restart the screen capture, you would need to request permission again
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for screen sharing functionality"
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        // Stop action
        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP_FOREGROUND
        }

        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        // Create the notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Sharing")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your own icon
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // WebRTC communication listener implementations
    override fun onSessionDescriptionSend(sessionDescription: SessionDescription) {
        Log.d(TAG, "Session description created: ${sessionDescription.type}")
        if (sessionDescription.type == SessionDescription.Type.OFFER) {
            socketManager.sendOffer(sessionDescription)
        } else {
            remoteSocketId?.let { socketId ->
                socketManager.sendAnswer(sessionDescription, socketId)
            }
        }
    }

    override fun onIceCandidateSend(iceCandidate: IceCandidate) {
        Log.d(TAG, "ICE candidate: ${iceCandidate.sdpMid}")
        remoteSocketId?.let { socketId ->
            socketManager.sendIceCandidate(iceCandidate, socketId)
        } ?: socketManager.sendIceCandidate(iceCandidate)
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        Log.d(TAG, "Connection state changed: $state")
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                updateNotification("Connected to remote peer")
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                updateNotification("Disconnected from remote peer")
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                updateNotification("Connection failed")
                stopScreenSharing()
            }
            else -> {
                // Handle other states if needed
            }
        }
    }
}