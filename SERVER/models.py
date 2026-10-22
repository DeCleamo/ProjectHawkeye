from sqlalchemy import (
    Column,
    Integer,
    String,
    Float,
    DateTime,
    UniqueConstraint
)

from datetime import datetime

from database import Base


# ============================================================
# TOURIST
# ============================================================

class Tourist(Base):
    __tablename__ = "tourists"

    id = Column(Integer, primary_key=True, index=True)

    hawkeye_id = Column(
        String,
        unique=True,
        index=True
    )

    name = Column(String)

    emergency_contact = Column(String)


# ============================================================
# HAWKEYE NODE
# ============================================================

class Node(Base):
    __tablename__ = "nodes"

    id = Column(Integer, primary_key=True, index=True)

    node_id = Column(
        String,
        unique=True,
        index=True
    )

    zone = Column(String)

    latitude = Column(Float)
    longitude = Column(Float)

    status = Column(
        String,
        default="OFFLINE"
    )

    last_seen = Column(
        DateTime,
        nullable=True
    )

    ip_address = Column(
        String,
        nullable=True
    )


# ============================================================
# TOURIST LOCATION
# ============================================================

class Location(Base):
    __tablename__ = "locations"

    id = Column(Integer, primary_key=True, index=True)

    hawkeye_id = Column(
        String,
        index=True
    )

    latitude = Column(Float)
    longitude = Column(Float)

    timestamp = Column(
        DateTime,
        default=datetime.utcnow
    )


# ============================================================
# SOS ALERT
# ============================================================

class SOSAlert(Base):
    __tablename__ = "sos_alerts"

    id = Column(Integer, primary_key=True, index=True)

    hawkeye_id = Column(
        String,
        index=True
    )

    latitude = Column(Float)
    longitude = Column(Float)

    timestamp = Column(
        DateTime,
        default=datetime.utcnow
    )

    status = Column(
        String,
        default="ACTIVE"
    )


# ============================================================
# CHAT REQUEST
# ============================================================

class ChatRequest(Base):
    __tablename__ = "chat_requests"

    id = Column(
        Integer,
        primary_key=True,
        index=True
    )

    sender_id = Column(
        String,
        index=True,
        nullable=False
    )

    receiver_id = Column(
        String,
        index=True,
        nullable=False
    )

    status = Column(
        String,
        default="PENDING",
        nullable=False
    )

    created_at = Column(
        DateTime,
        default=datetime.utcnow
    )


# ============================================================
# CONVERSATION
# ============================================================

class Conversation(Base):
    __tablename__ = "conversations"

    id = Column(
        Integer,
        primary_key=True,
        index=True
    )

    user_a = Column(
        String,
        index=True,
        nullable=False
    )

    user_b = Column(
        String,
        index=True,
        nullable=False
    )

    created_at = Column(
        DateTime,
        default=datetime.utcnow
    )

    __table_args__ = (
        UniqueConstraint(
            "user_a",
            "user_b",
            name="unique_conversation"
        ),
    )


# ============================================================
# MESSAGE
# ============================================================

class Message(Base):
    __tablename__ = "messages"

    id = Column(
        Integer,
        primary_key=True,
        index=True
    )

    conversation_id = Column(
        Integer,
        index=True,
        nullable=False
    )

    sender_id = Column(
        String,
        index=True,
        nullable=False
    )

    message = Column(
        String,
        nullable=False
    )

    timestamp = Column(
        DateTime,
        default=datetime.utcnow
    )