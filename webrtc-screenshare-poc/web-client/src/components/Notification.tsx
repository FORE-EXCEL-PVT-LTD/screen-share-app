import React from "react";
import { NotificationProps } from "../types";
import "./Notification.css";

const Notification: React.FC<NotificationProps> = ({
  title,
  message,
  onAccept,
  onReject,
}) => {
  return (
    <div className="notification-overlay">
      <div className="notification-card">
        <h2>{title}</h2>
        <p>{message}</p>
        <div className="notification-actions">
          <button className="btn-accept" onClick={onAccept}>
            Accept
          </button>
          <button className="btn-reject" onClick={onReject}>
            Reject
          </button>
        </div>
      </div>
    </div>
  );
};

export default Notification;
