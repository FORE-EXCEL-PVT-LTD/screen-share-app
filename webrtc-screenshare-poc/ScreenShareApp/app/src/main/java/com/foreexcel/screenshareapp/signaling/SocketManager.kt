package com.foreexcel.screenshareapp.signaling

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException
import org.webrtc.SessionDescription
import org.webrtc.IceCandidate

class SocketManager {
    companion object {
        private const val TAG = "SocketManager"
        private const val SERVER_URL = "http://your-server-address:5000"
    }

    private var socket: Socket? = null
    private var roomId: String? = null
    private var sessionEnded = false

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onJoinedRoom: ((String) -> Unit)? = null
    var onRemoteJoinRequest: ((userId: String?, username: String?, socketId: String) -> Unit)? = null
    var onRemoteSessionDescription: ((SessionDescription) -> Unit)? = null
    var onRemoteIceCandidate: ((IceCandidate) -> Unit)? = null
    var onSessionEnded: (() -> Unit)? = null

    fun init() {
        try {
            val options = IO.Options.builder()
                .setForceNew(true)
                .setReconnection(true)
                .setReconnectionAttempts(5)
                .setReconnectionDelay(5000)
                .setTimeout(20000) // 20 second timeout
                .build()

            socket = IO.socket(SERVER_URL, options)
            setupSocketEvents()
            connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Socket initialization error: ${e.message}")
            onError?.invoke("Failed to initialize connection: ${e.message}")
        }
    }

    private fun setupSocketEvents() {
        socket?.let { socket ->
            socket.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Socket connected")
                onConnected?.invoke()
            }

            socket.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Socket disconnected")
                onDisconnected?.invoke()
            }

            socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "Unknown error"
                Log.e(TAG, "Connection error: $error")
                onError?.invoke("Connection error: $error")
            }

            socket.on("room-created") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val roomId = data.getString("roomId")
                        Log.d(TAG, "Room created: $roomId")
                        onJoinedRoom?.invoke(roomId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing room-created: ${e.message}")
                    }
                }
            }

            socket.on("join-request") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        // Use optString to handle missing fields gracefully
                        val userId = data.optString("userId", null)
                        val username = data.optString("username", "unknown")
                        val socketId = data.getString("socketId") // This should always be present

                        Log.d(TAG, "Join request from $username")
                        onRemoteJoinRequest?.invoke(userId, username, socketId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing join-request: ${e.message}")
                        e.printStackTrace() // Print the full stack trace
                    }
                }
            }

            socket.on("offer") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val descriptionJson = data.getJSONObject("description")

                        val type = SessionDescription.Type.valueOf(descriptionJson.getString("type").uppercase())
                        val sdp = descriptionJson.getString("sdp")

                        val sessionDescription = SessionDescription(type, sdp)
                        Log.d(TAG, "Received offer: type=${type}")
                        onRemoteSessionDescription?.invoke(sessionDescription)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing offer: ${e.message}")
                    }
                }
            }

            socket.on("answer") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val descriptionJson = data.getJSONObject("description")

                        val type = SessionDescription.Type.valueOf(descriptionJson.getString("type").uppercase())
                        val sdp = descriptionJson.getString("sdp")

                        val sessionDescription = SessionDescription(type, sdp)
                        Log.d(TAG, "Received answer: type=${type}")
                        onRemoteSessionDescription?.invoke(sessionDescription)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing answer: ${e.message}")
                    }
                }
            }

            socket.on("ice-candidate") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val candidateJson = data.getJSONObject("candidate")

                        val candidate = IceCandidate(
                            candidateJson.getString("sdpMid"),
                            candidateJson.getInt("sdpMLineIndex"),
                            candidateJson.getString("candidate")
                        )

                        Log.d(TAG, "Received ICE candidate")
                        onRemoteIceCandidate?.invoke(candidate)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing ice-candidate: ${e.message}")
                    }
                }
            }

            socket.on("session-ended") { args ->
                Log.d(TAG, "Session ended")
                sessionEnded = true
                roomId = null
                onSessionEnded?.invoke()
            }

            socket.on("join-accepted") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val roomId = data.getString("roomId")
                        Log.d(TAG, "Join accepted for room: $roomId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing join-accepted: ${e.message}")
                    }
                }
            }
        }
    }

    fun connect() {
        socket?.connect()
    }

    fun disconnect() {
        socket?.disconnect()
    }

    fun registerUser(userId: String, username: String) {
        val userData = JSONObject()
        userData.put("userId", userId)
        userData.put("username", username)
        socket?.emit("register", userData)
    }

    fun createRoom(roomId: String) {
        sessionEnded = false
        this.roomId = roomId
        socket?.emit("create-room", roomId)
    }

    fun joinRoom(roomId: String) {
        sessionEnded = false
        this.roomId = roomId
        socket?.emit("join-room", roomId)
    }

    fun acceptJoinRequest(targetSocketId: String) {
        val data = JSONObject()
        data.put("roomId", roomId)
        data.put("targetSocketId", targetSocketId)
        socket?.emit("accept-join", data)
    }

    fun sendOffer(sessionDescription: SessionDescription) {
        val data = JSONObject()
        data.put("roomId", roomId)

        val descriptionJson = JSONObject()
        descriptionJson.put("type", sessionDescription.type.name.lowercase())
        descriptionJson.put("sdp", sessionDescription.description)

        data.put("description", descriptionJson)
        Log.d(TAG, "Sending offer: type=${sessionDescription.type}")
        socket?.emit("offer", data)
    }

    fun sendAnswer(sessionDescription: SessionDescription, to: String) {
        val data = JSONObject()
        data.put("roomId", roomId)
        data.put("to", to)

        val descriptionJson = JSONObject()
        descriptionJson.put("type", sessionDescription.type.name.lowercase())
        descriptionJson.put("sdp", sessionDescription.description)

        data.put("description", descriptionJson)
        Log.d(TAG, "Sending answer: type=${sessionDescription.type}")
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(iceCandidate: IceCandidate, to: String? = null) {
        val data = JSONObject()
        data.put("roomId", roomId)

        if (to != null) {
            data.put("to", to)
        }

        val candidateJson = JSONObject()
        candidateJson.put("sdpMid", iceCandidate.sdpMid)
        candidateJson.put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        candidateJson.put("candidate", iceCandidate.sdp)

        data.put("candidate", candidateJson)
        Log.d(TAG, "Sending ICE candidate")
        socket?.emit("ice-candidate", data)
    }

    fun startSharing() {
        roomId?.let { room ->
            Log.d(TAG, "Starting sharing for room: $room")
            socket?.emit("start-sharing", room)
        }
    }

    fun endSession() {
        // Only end the session if we have a room ID and haven't already ended it
        if (roomId != null && !sessionEnded) {
            sessionEnded = true
            Log.d(TAG, "Ending session for room: $roomId")
            socket?.emit("end-session", roomId)
            roomId = null
        } else {
            Log.d(TAG, "Session already ended or no room ID")
        }
    }
}