import { Server, Socket } from "socket.io";
import {
  User,
  Room,
  SessionDescription,
  IceCandidate,
  SocketData,
} from "../types";

export default (io: Server) => {
  // Keep track of connected users and their rooms
  const socketData: SocketData = {
    users: {},
    rooms: {},
  };

  io.on("connection", (socket: Socket) => {
    console.log(`New connection: ${socket.id}`);

    // User authentication and tracking
    socket.on("register", (userData: { username: string; userId: string }) => {
      console.log(`User registered: ${userData.username} (${socket.id})`);
      socketData.users[socket.id] = {
        username: userData.username,
        userId: userData.userId,
        socketId: socket.id,
      };

      // Notify other users about new user
      socket.broadcast.emit("user-joined", {
        username: userData.username,
        userId: userData.userId,
      });
    });

    // Create or join room
    socket.on("create-room", (roomId: string) => {
      console.log(
        `Room created/joined: ${roomId} by ${
          socketData.users[socket.id]?.username
        } (${socket.id})`
      );

      // Leave any previously joined rooms
      leaveAllRooms(socket);

      socket.join(roomId);

      if (!socketData.rooms[roomId]) {
        socketData.rooms[roomId] = {
          id: roomId,
          creator: socket.id,
          participants: [socket.id],
          active: true, // Add active flag to track room state
        };
      } else {
        if (!socketData.rooms[roomId].participants.includes(socket.id)) {
          socketData.rooms[roomId].participants.push(socket.id);
        }
        socketData.rooms[roomId].active = true; // Ensure room is marked active
      }

      socket.emit("room-created", { roomId });
    });

    // Join room by room ID
    socket.on("join-room", (roomId: string) => {
      if (!socketData.rooms[roomId]) {
        socket.emit("error", { message: "Room not found" });
        return;
      }

      console.log(
        `User ${socketData.users[socket.id]?.username} (${
          socket.id
        }) joining room: ${roomId}`
      );

      // Leave any previously joined rooms
      leaveAllRooms(socket);

      socket.join(roomId);

      if (!socketData.rooms[roomId].participants.includes(socket.id)) {
        socketData.rooms[roomId].participants.push(socket.id);
      }

      // Set room to active
      socketData.rooms[roomId].active = true;

      // Notify room creator of join request
      if (socketData.rooms[roomId].creator !== socket.id) {
        console.log(
          `Sending join request to creator: ${socketData.rooms[roomId].creator}`
        );
        io.to(socketData.rooms[roomId].creator).emit("join-request", {
          roomId,
          userId: socketData.users[socket.id]?.userId,
          username: socketData.users[socket.id]?.username,
          socketId: socket.id, // Include socket ID for direct communication
        });
      }
    });

    // Accept join request
    socket.on(
      "accept-join",
      (data: { roomId: string; targetSocketId: string }) => {
        console.log(
          `Join request accepted for ${data.targetSocketId} in room ${data.roomId}`
        );
        io.to(data.targetSocketId).emit("join-accepted", {
          roomId: data.roomId,
        });
      }
    );

    // Start screen sharing (from Android to Web)
    socket.on("start-sharing", (roomId: string) => {
      console.log(
        `Screen sharing started in room: ${roomId} by ${
          socketData.users[socket.id]?.username
        } (${socket.id})`
      );
      socket.to(roomId).emit("sharing-started", {
        initiator: socketData.users[socket.id]?.username,
      });
    });

    // WebRTC Signaling
    socket.on(
      "offer",
      (data: { roomId: string; description: SessionDescription }) => {
        if (!socketData.rooms[data.roomId]?.active) {
          console.log(`Ignoring offer for inactive room: ${data.roomId}`);
          return;
        }

        console.log(`Offer sent in room: ${data.roomId} from ${socket.id}`);
        console.log(`SDP type: ${data.description.type}`);

        // Forward offer to all other participants in the room
        socket.to(data.roomId).emit("offer", {
          description: data.description,
          from: socket.id,
        });
      }
    );

    socket.on(
      "answer",
      (data: {
        roomId: string;
        description: SessionDescription;
        to: string;
      }) => {
        if (!socketData.rooms[data.roomId]?.active) {
          console.log(`Ignoring answer for inactive room: ${data.roomId}`);
          return;
        }

        console.log(
          `Answer sent in room: ${data.roomId} from ${socket.id} to ${data.to}`
        );
        console.log(`SDP type: ${data.description.type}`);

        // Send answer directly to the specific peer
        io.to(data.to).emit("answer", {
          description: data.description,
          from: socket.id,
        });
      }
    );

    socket.on(
      "ice-candidate",
      (data: { roomId: string; candidate: IceCandidate; to?: string }) => {
        if (!socketData.rooms[data.roomId]?.active) {
          console.log(
            `Ignoring ICE candidate for inactive room: ${data.roomId}`
          );
          return;
        }

        if (data.to) {
          console.log(
            `ICE candidate from ${socket.id} to specific peer ${data.to} in room ${data.roomId}`
          );
          io.to(data.to).emit("ice-candidate", {
            candidate: data.candidate,
            from: socket.id,
          });
        } else {
          console.log(`ICE candidate from ${socket.id} to room ${data.roomId}`);
          socket.to(data.roomId).emit("ice-candidate", {
            candidate: data.candidate,
            from: socket.id,
          });
        }
      }
    );

    // End session
    socket.on("end-session", (roomId: string) => {
      // Prevent repeated end-session events
      if (!socketData.rooms[roomId] || !socketData.rooms[roomId].active) {
        console.log(`Ignoring end-session for inactive room: ${roomId}`);
        return;
      }

      console.log(
        `Session ended in room: ${roomId} by ${
          socketData.users[socket.id]?.username
        } (${socket.id})`
      );

      // Mark room as inactive
      if (socketData.rooms[roomId]) {
        socketData.rooms[roomId].active = false;
      }

      // Notify all other participants
      socket.to(roomId).emit("session-ended", {
        by: socketData.users[socket.id]?.username,
      });

      // Clean up room if initiator ended the session
      if (
        socketData.rooms[roomId] &&
        socketData.rooms[roomId].creator === socket.id
      ) {
        console.log(`Creator ended session, cleaning up room: ${roomId}`);

        // Leave the room
        socket.leave(roomId);

        // Remove participant
        socketData.rooms[roomId].participants = socketData.rooms[
          roomId
        ].participants.filter((id) => id !== socket.id);

        // Delete room if empty
        if (socketData.rooms[roomId].participants.length === 0) {
          delete socketData.rooms[roomId];
        }
      }
    });

    // Handle disconnect
    socket.on("disconnect", () => {
      const username = socketData.users[socket.id]?.username;
      console.log(`User disconnected: ${username} (${socket.id})`);

      // Collect all rooms where this user is a participant
      const userRooms: string[] = [];
      for (const roomId in socketData.rooms) {
        if (socketData.rooms[roomId].participants.includes(socket.id)) {
          userRooms.push(roomId);
        }
      }

      // Handle each room separately
      userRooms.forEach((roomId) => {
        console.log(
          `Handling disconnect for user ${socket.id} in room ${roomId}`
        );

        // Notify others in the room
        socket.to(roomId).emit("user-left", {
          username: socketData.users[socket.id]?.username,
        });

        // If this was the creator, notify others about session end
        if (socketData.rooms[roomId].creator === socket.id) {
          socket.to(roomId).emit("session-ended", {
            by: socketData.users[socket.id]?.username,
          });
        }

        // Remove the user from participants
        socketData.rooms[roomId].participants = socketData.rooms[
          roomId
        ].participants.filter((id) => id !== socket.id);

        // Delete room if empty or if creator left
        if (
          socketData.rooms[roomId].participants.length === 0 ||
          socketData.rooms[roomId].creator === socket.id
        ) {
          console.log(`Cleaning up room ${roomId} after disconnect`);
          delete socketData.rooms[roomId];
        }
      });

      // Remove user from users object
      delete socketData.users[socket.id];
    });

    // Helper function to leave all rooms a socket is in
    function leaveAllRooms(socket: Socket): void {
      for (const roomId in socketData.rooms) {
        if (socketData.rooms[roomId].participants.includes(socket.id)) {
          // Leave the Socket.IO room
          socket.leave(roomId);

          // Remove from participants list
          socketData.rooms[roomId].participants = socketData.rooms[
            roomId
          ].participants.filter((id) => id !== socket.id);

          // If this was the creator, notify others and clean up
          if (socketData.rooms[roomId].creator === socket.id) {
            socket.to(roomId).emit("session-ended", {
              by: socketData.users[socket.id]?.username,
            });

            // Delete the room
            delete socketData.rooms[roomId];
          }
          // If room is now empty, clean it up
          else if (socketData.rooms[roomId].participants.length === 0) {
            delete socketData.rooms[roomId];
          }
        }
      }
    }
  });

  // Add monitoring interval to check for orphaned rooms/connections
  setInterval(() => {
    // Log active rooms for debugging
    const activeRooms = Object.keys(socketData.rooms).length;
    if (activeRooms > 0) {
      console.log(`Active rooms: ${activeRooms}`);
      for (const roomId in socketData.rooms) {
        console.log(
          `  Room ${roomId}: ${socketData.rooms[roomId].participants.length} participants, active: ${socketData.rooms[roomId].active}`
        );
      }
    }

    // Clean up inactive rooms older than 10 minutes
    // Would need to add timestamp to room objects to implement this
  }, 60000); // Check every minute
};
