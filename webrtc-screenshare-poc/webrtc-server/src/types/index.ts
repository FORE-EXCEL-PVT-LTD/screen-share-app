import { Socket } from "socket.io";

export interface User {
  username: string;
  userId: string;
  socketId: string;
}

export interface Room {
  id: string;
  creator: string; // socket ID
  participants: string[]; // socket IDs
}

export interface UserDocument {
  _id: string;
  username: string;
  password: string;
  createdAt: Date;
}

export interface SessionDescription {
  type: string;
  sdp: string;
}

export interface IceCandidate {
  sdpMid: string;
  sdpMLineIndex: number;
  candidate: string;
}

export interface SocketData {
  users: Record<string, User>;
  rooms: Record<string, Room>;
}
export interface Room {
  id: string;
  creator: string; // socket ID
  participants: string[]; // socket IDs
  active: boolean; // Added to track if room is active
}
