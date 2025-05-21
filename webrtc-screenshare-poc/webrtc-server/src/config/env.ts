// src/config/env.ts
import dotenv from "dotenv";
dotenv.config();

export default {
  PORT: process.env.PORT || 5000,
  JWT_SECRET: process.env.JWT_SECRET || "default_jwt_secret",
  MONGODB_URI:
    process.env.MONGODB_URI ||
    "mongodb+srv://admin:Pass12345@devflow.sjquttw.mongodb.net/screen-share-app-db",
};
