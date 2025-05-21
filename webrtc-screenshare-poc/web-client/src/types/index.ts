export interface User {
  userId: string;
  username: string;
  token: string;
}

export interface AuthContextType {
  currentUser: User | null;
  isAuthenticated: boolean;
  login: (username: string, password: string) => Promise<User>;
  register: (username: string, password: string) => Promise<User>;
  logout: () => void;
}

export interface CallData {
  roomId: string;
  userId: string;
  username: string;
  socketId: string;
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

export interface NotificationProps {
  title: string;
  message: string;
  onAccept: () => void;
  onReject: () => void;
}
