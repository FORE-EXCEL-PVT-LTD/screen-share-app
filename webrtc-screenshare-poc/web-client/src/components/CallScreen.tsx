import React, { useState, useEffect, useRef } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";
import webRTCService from "../services/webrtc";
import Notification from "./Notification";
import { CallData } from "../types";
import "./CallScreen.css";

const CallScreen: React.FC = () => {
  const [roomId, setRoomId] = useState<string>("");
  const [callStatus, setCallStatus] = useState<
    "idle" | "calling" | "connected"
  >("idle");
  const [incomingCall, setIncomingCall] = useState<CallData | null>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const { currentUser, logout } = useAuth();
  const params = useParams<{ roomId?: string }>();
  const navigate = useNavigate();

  useEffect(() => {
    // Initialize WebRTC service
    webRTCService.init();

    // Register current user with socket server
    if (currentUser) {
      webRTCService.registerUser(currentUser.userId, currentUser.username);
    }

    // Setup event handlers
    webRTCService.registerListeners({
      onCall: (data: CallData) => {
        console.log("Incoming call", data);
        setIncomingCall(data);
      },

      onCalling: () => {
        setCallStatus("calling");
      },

      onConnected: () => {
        setCallStatus("connected");
      },

      onDisconnected: () => {
        setCallStatus("idle");
        if (videoRef.current && videoRef.current.srcObject) {
          const stream = videoRef.current.srcObject as MediaStream;
          stream.getTracks().forEach((track) => track.stop());
          videoRef.current.srcObject = null;
        }
      },

      onRemoteStream: (stream: MediaStream) => {
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
        }
      },
    });

    // Check if room ID is in URL params
    if (params.roomId) {
      setRoomId(params.roomId);
      joinRoom(params.roomId);
    }

    // Cleanup on component unmount
    return () => {
      webRTCService.endCall();
    };
  }, [currentUser, params.roomId]);

  const joinRoom = (targetRoomId: string) => {
    if (!targetRoomId) {
      return;
    }

    setCallStatus("calling");
    webRTCService.joinRoom(targetRoomId);
  };

  const handleJoinRoom = (e: React.FormEvent) => {
    e.preventDefault();
    if (!roomId) return;

    navigate(`/call/${roomId}`);
    joinRoom(roomId);
  };

  const acceptCall = () => {
    if (!incomingCall) return;

    webRTCService.acceptJoinRequest(incomingCall.roomId, incomingCall.socketId);
    webRTCService.setupPeerConnection();
    setCallStatus("connected");
    setIncomingCall(null);
  };

  const rejectCall = () => {
    setIncomingCall(null);
  };

  const endCall = () => {
    webRTCService.endCall();
    setCallStatus("idle");
    navigate("/");
  };

  const handleLogout = () => {
    webRTCService.endCall();
    logout();
    navigate("/login");
  };

  return (
    <div className="call-screen-container">
      {incomingCall && (
        <Notification
          title="Incoming Call"
          message={`${incomingCall.username} is trying to share their screen with you.`}
          onAccept={acceptCall}
          onReject={rejectCall}
        />
      )}

      <div className="header">
        <h1>Screen Share Web Client</h1>
        <button className="btn-logout" onClick={handleLogout}>
          Logout
        </button>
      </div>

      <div className="content">
        {callStatus === "idle" ? (
          <div className="join-form">
            <h2>Join a Room</h2>
            <form onSubmit={handleJoinRoom}>
              <div className="form-group">
                <label htmlFor="roomId">Room ID</label>
                <input
                  type="text"
                  id="roomId"
                  value={roomId}
                  onChange={(e) => setRoomId(e.target.value)}
                  placeholder="Enter Room ID"
                />
              </div>
              <button type="submit" className="btn-join">
                Join Room
              </button>
            </form>

            <div className="instructions">
              <h3>Instructions:</h3>
              <p>1. Get the Room ID from the Android user.</p>
              <p>2. Enter the Room ID in the field above.</p>
              <p>3. Click "Join Room" to start the session.</p>
              <p>4. Allow microphone access when prompted.</p>
            </div>
          </div>
        ) : (
          <div className="call-container">
            <div className="video-container">
              <video ref={videoRef} autoPlay playsInline></video>

              {callStatus === "calling" && (
                <div className="call-status">
                  <div className="spinner"></div>
                  <p>Connecting to room...</p>
                </div>
              )}

              {callStatus === "connected" && (
                <div className="room-info">
                  <p>Room ID: {roomId}</p>
                </div>
              )}
            </div>

            <div className="call-controls">
              <button className="btn-end-call" onClick={endCall}>
                End Call
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default CallScreen;
