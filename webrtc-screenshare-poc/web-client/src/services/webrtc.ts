import io, { Socket } from "socket.io-client";
import { CallData, SessionDescription, IceCandidate } from "../types";

interface WebRTCListeners {
  onCall?: (data: CallData) => void;
  onCalling?: () => void;
  onConnected?: () => void;
  onDisconnected?: () => void;
  onRemoteStream?: (stream: MediaStream) => void;
}

class WebRTCService {
  private socket: Socket | null = null;
  private peerConnection: RTCPeerConnection | null = null;
  private remoteStream: MediaStream | null = null;
  private currentRoomId: string | null = null;
  private listeners: WebRTCListeners = {};
  private remoteSocketId: string | null = null;

  init(): void {
    // Connect to the signaling server
    this.socket = io("http://localhost:5000");

    // Handle events
    this.socket.on("connect", this.handleConnect.bind(this));
    this.socket.on("disconnect", this.handleDisconnect.bind(this));
    this.socket.on("offer", this.handleOffer.bind(this));
    this.socket.on("answer", this.handleAnswer.bind(this));
    this.socket.on("ice-candidate", this.handleIceCandidate.bind(this));
    this.socket.on("join-request", this.handleJoinRequest.bind(this));
    this.socket.on("sharing-started", this.handleSharingStarted.bind(this));
    this.socket.on("session-ended", this.handleSessionEnded.bind(this));
    this.socket.on("join-accepted", this.handleJoinAccepted.bind(this));
  }

  registerListeners(listeners: WebRTCListeners): void {
    this.listeners = { ...this.listeners, ...listeners };
  }

  registerUser(userId: string, username: string): void {
    if (this.socket) {
      this.socket.emit("register", { userId, username });
    }
  }

  createRoom(roomId: string): void {
    if (this.socket) {
      this.socket.emit("create-room", roomId);
      this.currentRoomId = roomId;
    }
  }

  joinRoom(roomId: string): void {
    if (this.socket) {
      this.socket.emit("join-room", roomId);
      this.currentRoomId = roomId;
    }
  }

  acceptJoinRequest(roomId: string, targetSocketId: string): void {
    if (this.socket) {
      this.socket.emit("accept-join", { roomId, targetSocketId });
    }
  }

  async setupPeerConnection(): Promise<void> {
    const configuration: RTCConfiguration = {
      iceServers: [
        { urls: "stun:stun.l.google.com:19302" },
        { urls: "stun:stun1.l.google.com:19302" },
      ],
    };

    this.peerConnection = new RTCPeerConnection(configuration);

    // Handle ICE candidates
    this.peerConnection.onicecandidate = (event: RTCPeerConnectionIceEvent) => {
      if (event.candidate && this.socket) {
        const candidate = {
          sdpMLineIndex: event.candidate.sdpMLineIndex!,
          sdpMid: event.candidate.sdpMid!,
          candidate: event.candidate.candidate,
        };

        this.socket.emit("ice-candidate", {
          roomId: this.currentRoomId,
          candidate,
          to: this.remoteSocketId,
        });
      }
    };

    // Handle connection state changes
    this.peerConnection.onconnectionstatechange = () => {
      if (this.peerConnection) {
        switch (this.peerConnection.connectionState) {
          case "connected":
            console.log("WebRTC connected");
            if (this.listeners.onConnected) {
              this.listeners.onConnected();
            }
            break;
          case "disconnected":
          case "failed":
            console.log("WebRTC disconnected or failed");
            if (this.listeners.onDisconnected) {
              this.listeners.onDisconnected();
            }
            break;
          default:
            break;
        }
      }
    };

    // Handle remote tracks
    this.peerConnection.ontrack = (event: RTCTrackEvent) => {
      console.log("Received remote track", event);
      if (event.streams && event.streams[0]) {
        this.remoteStream = event.streams[0];
        if (this.listeners.onRemoteStream) {
          this.listeners.onRemoteStream(this.remoteStream);
        }
      }
    };

    // Add local audio track for two-way communication
    try {
      const localStream = await navigator.mediaDevices.getUserMedia({
        audio: true,
        video: false,
      });
      localStream.getAudioTracks().forEach((track) => {
        if (this.peerConnection) {
          this.peerConnection.addTrack(track, localStream);
        }
      });
    } catch (error) {
      console.error("Error getting local audio stream:", error);
    }
  }

  async createAnswer(
    offer: RTCSessionDescriptionInit,
    from: string
  ): Promise<void> {
    if (!this.peerConnection) {
      await this.setupPeerConnection();
    }

    this.remoteSocketId = from;

    if (this.peerConnection) {
      try {
        await this.peerConnection.setRemoteDescription(
          new RTCSessionDescription(offer)
        );
        const answer = await this.peerConnection.createAnswer();
        await this.peerConnection.setLocalDescription(answer);

        if (this.socket) {
          this.socket.emit("answer", {
            roomId: this.currentRoomId,
            description: this.peerConnection.localDescription,
            to: from,
          });
        }
      } catch (error) {
        console.error("Error creating answer:", error);
      }
    }
  }

  async handleOffer(data: {
    description: RTCSessionDescriptionInit;
    from: string;
  }): Promise<void> {
    console.log("Received offer", data);
    await this.createAnswer(data.description, data.from);
  }

  async handleAnswer(data: {
    description: RTCSessionDescriptionInit;
    from: string;
  }): Promise<void> {
    console.log("Received answer", data);
    if (this.peerConnection) {
      try {
        await this.peerConnection.setRemoteDescription(
          new RTCSessionDescription(data.description)
        );
      } catch (error) {
        console.error("Error setting remote description:", error);
      }
    }
  }

  async handleIceCandidate(data: {
    candidate: IceCandidate;
    from: string;
  }): Promise<void> {
    console.log("Received ICE candidate", data);
    if (this.peerConnection) {
      try {
        await this.peerConnection.addIceCandidate(
          new RTCIceCandidate(data.candidate)
        );
      } catch (error) {
        console.error("Error adding ICE candidate:", error);
      }
    }
  }

  handleJoinRequest(data: CallData): void {
    console.log("Join request received", data);
    this.remoteSocketId = data.socketId;
    if (this.listeners.onCall) {
      this.listeners.onCall(data);
    }
  }

  handleSharingStarted(data: { initiator: string }): void {
    console.log("Screen sharing started by", data.initiator);
  }

  handleJoinAccepted(data: { roomId: string }): void {
    console.log("Join accepted for room", data.roomId);
    if (this.listeners.onCalling) {
      this.listeners.onCalling();
    }
  }

  handleSessionEnded(data: { by: string }): void {
    console.log("Session ended by", data.by);
    this.endCall();
  }

  handleConnect(): void {
    console.log("Connected to signaling server");
  }

  handleDisconnect(): void {
    console.log("Disconnected from signaling server");
  }

  endCall(): void {
    if (this.socket && this.currentRoomId) {
      this.socket.emit("end-session", this.currentRoomId);
    }

    if (this.peerConnection) {
      this.peerConnection.close();
      this.peerConnection = null;
    }

    if (this.remoteStream) {
      this.remoteStream.getTracks().forEach((track) => track.stop());
      this.remoteStream = null;
    }

    this.remoteSocketId = null;

    if (this.listeners.onDisconnected) {
      this.listeners.onDisconnected();
    }
  }
}

const webRTCService = new WebRTCService();
export default webRTCService;
