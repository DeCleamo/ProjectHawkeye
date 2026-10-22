from pydantic import BaseModel


# ============================================================
# TOURIST
# ============================================================

class TouristCreate(BaseModel):

    hawkeye_id: str

    name: str

    emergency_contact: str


# ============================================================
# NODE
# ============================================================

class NodeCreate(BaseModel):

    node_id: str

    zone: str

    latitude: float

    longitude: float


# ============================================================
# LOCATION
# ============================================================

class LocationCreate(BaseModel):

    hawkeye_id: str

    latitude: float

    longitude: float


# ============================================================
# SOS
# ============================================================

class SOSCreate(BaseModel):

    hawkeye_id: str

    latitude: float

    longitude: float


# ============================================================
# CHAT REQUEST
# ============================================================

class ChatRequestCreate(BaseModel):

    sender_id: str

    receiver_id: str


# ============================================================
# CHAT REQUEST RESPONSE ACTION
# ============================================================

class ChatRequestAction(BaseModel):

    hawkeye_id: str


# ============================================================
# SEND MESSAGE
# ============================================================

class MessageCreate(BaseModel):

    conversation_id: int

    sender_id: str

    message: str