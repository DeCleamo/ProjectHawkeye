from fastapi import FastAPI, Depends, HTTPException, Response
from sqlalchemy.orm import Session
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, PlainTextResponse
from pydantic import BaseModel
from datetime import datetime, timedelta

import models
import schemas

from database import engine, get_db


# ============================================================
# DATABASE
# ============================================================

models.Base.metadata.create_all(bind=engine)


# ============================================================
# FASTAPI
# ============================================================

app = FastAPI(
    title="Hawkeye Server",
    description="Offline Smart Tourist Safety Network",
    version="0.4.0"
)


# ============================================================
# STATIC FILES
# ============================================================

app.mount(
    "/static",
    StaticFiles(directory="static"),
    name="static"
)


# ============================================================
# ADMIN PANEL
# ============================================================

@app.get("/admin")
def admin_panel():
    return FileResponse("static/index.html")


# ============================================================
# ROOT
# ============================================================

@app.get("/")
def root():
    return {
        "system": "HAWKEYE",
        "status": "ONLINE",
        "version": "0.4.0"
    }


# ============================================================
# TEMPORARY TOURIST -> NODE ROUTING
# ============================================================
#
# Example:
#
# HWK-U001 -> HWK-N01
# HWK-U002 -> HWK-N03
#
# This is intentionally in-memory for the prototype.
# ============================================================

tourist_node_map = {}


# ============================================================
# NODE OUTBOX
# ============================================================
#
# Example:
#
# node_outbox["HWK-N03"] = [
#     "CHAT,HWK-U001,HWK-U002,Hello"
# ]
#
# ESP32 polls:
#
# GET /nodes/HWK-N03/outbox
# ============================================================

node_outbox = {}


def queue_for_node(node_id: str, packet: str):

    if node_id not in node_outbox:
        node_outbox[node_id] = []

    node_outbox[node_id].append(packet)

    print(
        f"[OUTBOX] Queued for {node_id}: {packet}"
    )


# ============================================================
# REGISTER TOURIST
# ============================================================

@app.post("/tourists")
def register_tourist(
    tourist: schemas.TouristCreate,
    db: Session = Depends(get_db)
):

    existing = db.query(
        models.Tourist
    ).filter(
        models.Tourist.hawkeye_id == tourist.hawkeye_id
    ).first()

    if existing:

        return {
            "status": "TOURIST_ALREADY_EXISTS",
            "hawkeye_id": existing.hawkeye_id,
            "name": existing.name
        }

    new_tourist = models.Tourist(
        hawkeye_id=tourist.hawkeye_id,
        name=tourist.name,
        emergency_contact=tourist.emergency_contact
    )

    db.add(new_tourist)
    db.commit()
    db.refresh(new_tourist)

    return new_tourist


# ============================================================
# REGISTER NODE
# ============================================================

@app.post("/nodes")
def register_node(
    node: schemas.NodeCreate,
    db: Session = Depends(get_db)
):

    existing = db.query(
        models.Node
    ).filter(
        models.Node.node_id == node.node_id
    ).first()

    if existing:

        existing.zone = node.zone
        existing.latitude = node.latitude
        existing.longitude = node.longitude

        db.commit()
        db.refresh(existing)

        return existing

    new_node = models.Node(
        node_id=node.node_id,
        zone=node.zone,
        latitude=node.latitude,
        longitude=node.longitude,
        status="OFFLINE",
        last_seen=None,
        ip_address=None
    )

    db.add(new_node)
    db.commit()
    db.refresh(new_node)

    return new_node


# ============================================================
# NODE HEARTBEAT
# ============================================================

@app.post("/nodes/{node_id}/heartbeat")
def node_heartbeat(
    node_id: str,
    db: Session = Depends(get_db)
):

    node = db.query(
        models.Node
    ).filter(
        models.Node.node_id == node_id
    ).first()

    if not node:

        return {
            "status": "NODE_NOT_REGISTERED",
            "node_id": node_id,
            "zone": "UNKNOWN"
        }

    now = datetime.utcnow()

    node.status = "ONLINE"
    node.last_seen = now

    db.commit()
    db.refresh(node)

    return {
        "status": "HEARTBEAT_RECEIVED",
        "node_id": node.node_id,
        "zone": node.zone,
        "latitude": node.latitude,
        "longitude": node.longitude,
        "ip_address": node.ip_address,
        "last_seen": node.last_seen
    }


# ============================================================
# UPDATE LOCATION
# ============================================================

@app.post("/location")
def update_location(
    location: schemas.LocationCreate,
    db: Session = Depends(get_db)
):

    new_location = models.Location(
        hawkeye_id=location.hawkeye_id,
        latitude=location.latitude,
        longitude=location.longitude
    )

    db.add(new_location)
    db.commit()
    db.refresh(new_location)

    return {
        "status": "LOCATION_RECEIVED",
        "hawkeye_id": location.hawkeye_id,
        "latitude": location.latitude,
        "longitude": location.longitude
    }


# ============================================================
# CREATE SOS
# ============================================================

@app.post("/sos")
def create_sos(
    sos: schemas.SOSCreate,
    db: Session = Depends(get_db)
):

    alert = models.SOSAlert(
        hawkeye_id=sos.hawkeye_id,
        latitude=sos.latitude,
        longitude=sos.longitude
    )

    db.add(alert)
    db.commit()
    db.refresh(alert)

    return {
        "status": "SOS_RECEIVED",
        "alert_id": alert.id
    }


# ============================================================
# NETWORK STATUS
# ============================================================

@app.get("/network")
def get_network(
    db: Session = Depends(get_db)
):

    now = datetime.utcnow()

    timeout = now - timedelta(seconds=20)

    nodes = db.query(
        models.Node
    ).all()

    for node in nodes:

        if (
            node.last_seen is not None
            and node.last_seen >= timeout
        ):

            node.status = "ONLINE"

        else:

            node.status = "OFFLINE"

    db.commit()

    tourists = db.query(
        models.Tourist
    ).all()

    locations = db.query(
        models.Location
    ).all()


    # ========================================================
    # LATEST LOCATION FOR EACH TOURIST
    # ========================================================

    latest_locations = {}

    for location in locations:

        existing = latest_locations.get(
            location.hawkeye_id
        )

        if (
            existing is None
            or location.timestamp > existing.timestamp
        ):

            latest_locations[
                location.hawkeye_id
            ] = location


    # ========================================================
    # ACTIVE SOS
    # ========================================================

    sos = db.query(
        models.SOSAlert
    ).filter(
        models.SOSAlert.status == "ACTIVE"
    ).all()


    # ========================================================
    # RESPONSE
    # ========================================================

    return {

        "tourists": [

            {
                "hawkeye_id": tourist.hawkeye_id,
                "name": tourist.name
            }

            for tourist in tourists
        ],


        "nodes": [

            {
                "node_id": node.node_id,
                "zone": node.zone,
                "latitude": node.latitude,
                "longitude": node.longitude,
                "status": node.status,
                "last_seen": node.last_seen,
                "ip_address": node.ip_address
            }

            for node in nodes
        ],


        "locations": [

            {
                "hawkeye_id": location.hawkeye_id,
                "latitude": location.latitude,
                "longitude": location.longitude,
                "timestamp": location.timestamp
            }

            for location in locations
        ],


        "latest_locations": [

            {
                "hawkeye_id": location.hawkeye_id,
                "latitude": location.latitude,
                "longitude": location.longitude,
                "timestamp": location.timestamp
            }

            for location in latest_locations.values()
        ],


        "sos": [

            {
                "id": alert.id,
                "hawkeye_id": alert.hawkeye_id,
                "latitude": alert.latitude,
                "longitude": alert.longitude,
                "timestamp": alert.timestamp
            }

            for alert in sos
        ]
    }


# ============================================================
# BLE MESSAGE MODEL
# ============================================================

class BLEMessage(BaseModel):
    message: str


# ============================================================
# BLE -> SERVER
# ============================================================
#
# Supported packets:
#
# REGISTER,HWK-U001
#
# LOCATION,HWK-U001,22.721800,75.861000
#
# SOS,HWK-U001,22.721800,75.861000
#
# CHAT,HWK-U001,HWK-U002,Hello
#
# ============================================================

@app.post("/nodes/{node_id}/ble-message")
def receive_ble_message(
    node_id: str,
    data: BLEMessage,
    db: Session = Depends(get_db)
):

    # ========================================================
    # VERIFY NODE
    # ========================================================

    node = db.query(
        models.Node
    ).filter(
        models.Node.node_id == node_id
    ).first()

    if not node:

        raise HTTPException(
            status_code=404,
            detail="Node not found"
        )


    message = data.message.strip()


    print()
    print("================================")
    print(
        f"[BLE] {node_id} ({node.zone}) -> {message}"
    )


    if not message:

        raise HTTPException(
            status_code=400,
            detail="Empty BLE message"
        )


    # ========================================================
    # REGISTER TOURIST
    # ========================================================
    #
    # REGISTER,HWK-U001
    #
    # ========================================================

    if message.startswith("REGISTER,"):

        parts = message.split(",", 1)

        if len(parts) != 2:

            raise HTTPException(
                status_code=400,
                detail="Invalid REGISTER format"
            )

        hawkeye_id = parts[1].strip()

        if not hawkeye_id:

            raise HTTPException(
                status_code=400,
                detail="Invalid Hawkeye ID"
            )

        # --------------------------------------------------------
        # AUTO-REGISTER NEW PHONE
        # --------------------------------------------------------
        # Android generates a unique persistent HWK-Uxxxxxx ID.
        # If this phone has never registered before, create it.
        # --------------------------------------------------------

        tourist = db.query(
            models.Tourist
        ).filter(
            models.Tourist.hawkeye_id == hawkeye_id
        ).first()

        if not tourist:

            tourist = models.Tourist(
                hawkeye_id=hawkeye_id,
                name="Tourist " + hawkeye_id[-6:],
                emergency_contact="Not provided"
            )

            db.add(tourist)
            db.commit()
            db.refresh(tourist)

            print(
                f"[REGISTER] New tourist created: {hawkeye_id}"
            )

        # Current location/node of this tourist.
        tourist_node_map[hawkeye_id] = node_id

        print(
            f"[REGISTER] {hawkeye_id} -> {node_id}"
        )

        print("================================")

        return {

            "status":
                "TOURIST_REGISTERED_ON_NODE",

            "hawkeye_id":
                hawkeye_id,

            "node_id":
                node_id,

            "zone":
                node.zone,

            "name":
                tourist.name
        }


    # ========================================================
    # LOCATION
    # ========================================================
    #
    # LOCATION,HWK-U001,22.721800,75.861000
    #
    # ========================================================

    if message.startswith("LOCATION,"):

        parts = message.split(",")

        if len(parts) != 4:

            raise HTTPException(
                status_code=400,
                detail="Invalid LOCATION format"
            )


        try:

            hawkeye_id = parts[1].strip()

            latitude = float(parts[2])

            longitude = float(parts[3])

        except ValueError:

            raise HTTPException(
                status_code=400,
                detail="Invalid latitude or longitude"
            )


        # Keep tourist associated with this node.

        tourist_node_map[hawkeye_id] = node_id


        location = models.Location(
            hawkeye_id=hawkeye_id,
            latitude=latitude,
            longitude=longitude
        )


        db.add(location)
        db.commit()


        print(
            f"[LOCATION] {hawkeye_id} -> "
            f"{latitude}, {longitude}"
        )

        print("================================")


        return {

            "status":
                "LOCATION_RECEIVED",

            "node_id":
                node_id,

            "zone":
                node.zone,

            "hawkeye_id":
                hawkeye_id,

            "latitude":
                latitude,

            "longitude":
                longitude
        }


    # ========================================================
    # SOS
    # ========================================================
    #
    # SOS,HWK-U001,22.721800,75.861000
    #
    # ========================================================

    if message.startswith("SOS,"):

        parts = message.split(",")

        if len(parts) != 4:

            raise HTTPException(
                status_code=400,
                detail="Invalid SOS format"
            )


        try:

            hawkeye_id = parts[1].strip()

            latitude = float(parts[2])

            longitude = float(parts[3])

        except ValueError:

            raise HTTPException(
                status_code=400,
                detail="Invalid latitude or longitude"
            )


        # Keep tourist associated with this node.

        tourist_node_map[hawkeye_id] = node_id


        alert = models.SOSAlert(
            hawkeye_id=hawkeye_id,
            latitude=latitude,
            longitude=longitude
        )


        db.add(alert)

        db.commit()

        db.refresh(alert)


        print(
            f"[SOS] {hawkeye_id} -> "
            f"{latitude}, {longitude}"
        )

        print("================================")


        return {

            "status":
                "SOS_RECEIVED",

            "node_id":
                node_id,

            "zone":
                node.zone,

            "hawkeye_id":
                hawkeye_id,

            "latitude":
                latitude,

            "longitude":
                longitude,

            "alert_id":
                alert.id
        }


    # ========================================================
    # CHAT MESSAGE
    # ========================================================
    #
    # CHAT,SENDER,RECEIVER,MESSAGE
    #
    # Example:
    #
    # CHAT,HWK-U001,HWK-U002,Hello
    #
    # ========================================================

    if message.startswith("CHAT,"):

        parts = message.split(",", 3)

        if len(parts) != 4:

            raise HTTPException(
                status_code=400,
                detail="Invalid CHAT format"
            )


        sender_id = parts[1].strip()

        receiver_id = parts[2].strip()

        text = parts[3].strip()


        if not sender_id:

            raise HTTPException(
                status_code=400,
                detail="Missing sender ID"
            )


        if not receiver_id:

            raise HTTPException(
                status_code=400,
                detail="Missing receiver ID"
            )


        if not text:

            raise HTTPException(
                status_code=400,
                detail="Message cannot be empty"
            )


        if sender_id == receiver_id:

            raise HTTPException(
                status_code=400,
                detail="Cannot message yourself"
            )


        # ====================================================
        # VERIFY SENDER
        # ====================================================

        sender = db.query(
            models.Tourist
        ).filter(
            models.Tourist.hawkeye_id == sender_id
        ).first()


        if not sender:

            raise HTTPException(
                status_code=404,
                detail="Sender tourist not found"
            )


        # ====================================================
        # VERIFY RECEIVER
        # ====================================================

        receiver = db.query(
            models.Tourist
        ).filter(
            models.Tourist.hawkeye_id == receiver_id
        ).first()


        if not receiver:

            raise HTTPException(
                status_code=404,
                detail="Receiver tourist not found"
            )


        # ====================================================
        # FIND CONVERSATION
        # ====================================================

        user_a = min(
            sender_id,
            receiver_id
        )

        user_b = max(
            sender_id,
            receiver_id
        )


        conversation = db.query(
            models.Conversation
        ).filter(
            models.Conversation.user_a == user_a,
            models.Conversation.user_b == user_b
        ).first()


        if not conversation:

            raise HTTPException(
                status_code=403,
                detail="Chat is not accepted"
            )


        # ====================================================
        # STORE MESSAGE
        # ====================================================

        new_message = models.Message(
            conversation_id=conversation.id,
            sender_id=sender_id,
            message=text
        )


        db.add(new_message)

        db.commit()

        db.refresh(new_message)


        # ====================================================
        # FIND RECEIVER NODE
        # ====================================================

        receiver_node = tourist_node_map.get(
            receiver_id
        )


        if not receiver_node:

            print(
                f"[CHAT] Receiver {receiver_id} "
                f"is not currently connected to a node."
            )

            print("================================")


            return {

                "status":
                    "MESSAGE_STORED",

                "message_id":
                    new_message.id,

                "conversation_id":
                    conversation.id,

                "delivery":
                    "RECEIVER_OFFLINE"
            }


        # ====================================================
        # QUEUE MESSAGE
        # ====================================================

        packet = (
            "CHAT,"
            + sender_id
            + ","
            + receiver_id
            + ","
            + text
        )


        queue_for_node(
            receiver_node,
            packet
        )


        print(
            f"[CHAT ROUTED] "
            f"{sender_id} -> "
            f"{receiver_id} "
            f"via {receiver_node}"
        )


        print("================================")


        return {

            "status":
                "MESSAGE_SENT",

            "message_id":
                new_message.id,

            "conversation_id":
                conversation.id,

            "sender_id":
                sender_id,

            "receiver_id":
                receiver_id,

            "receiver_node":
                receiver_node,

            "message":
                text,

            "delivery":
                "QUEUED"
        }


    # ========================================================
    # GENERAL MESSAGE
    # ========================================================

    print("================================")


    return {

        "status":
            "MESSAGE_RECEIVED",

        "node_id":
            node_id,

        "zone":
            node.zone,

        "message":
            message
    }


# ============================================================
# NODE OUTBOX
# ============================================================
#
# ESP32 polls this every second.
#
# 204 = nothing waiting
#
# 200 = one packet waiting
#
# ============================================================

@app.get("/nodes/{node_id}/outbox")
def get_node_outbox(
    node_id: str
):

    # Create empty queue if necessary.

    if node_id not in node_outbox:

        node_outbox[node_id] = []


    # ========================================================
    # NOTHING WAITING
    # ========================================================

    if len(node_outbox[node_id]) == 0:

        return Response(
            status_code=204
        )


    # ========================================================
    # GET FIRST PACKET
    # ========================================================

    packet = node_outbox[node_id].pop(0)


    print()
    print("================================")
    print(
        f"[OUTBOX DELIVERY] {node_id}"
    )
    print(
        f"Packet -> {packet}"
    )
    print("================================")


    # ========================================================
    # RETURN PACKET AS PLAIN TEXT
    # ========================================================

    return PlainTextResponse(
        content=packet,
        status_code=200
    )


# ============================================================
# CHAT REQUEST
# ============================================================

@app.post("/chat/request")
def create_chat_request(
    request: schemas.ChatRequestCreate,
    db: Session = Depends(get_db)
):

    sender_id = request.sender_id.strip()

    receiver_id = request.receiver_id.strip()


    # --------------------------------------------------------
    # Cannot request yourself
    # --------------------------------------------------------

    if sender_id == receiver_id:

        raise HTTPException(
            status_code=400,
            detail="Cannot send a chat request to yourself"
        )


    # --------------------------------------------------------
    # Verify sender
    # --------------------------------------------------------

    sender = db.query(
        models.Tourist
    ).filter(
        models.Tourist.hawkeye_id == sender_id
    ).first()


    if not sender:

        raise HTTPException(
            status_code=404,
            detail="Sender tourist not found"
        )


    # --------------------------------------------------------
    # Verify receiver
    # --------------------------------------------------------

    receiver = db.query(
        models.Tourist
    ).filter(
        models.Tourist.hawkeye_id == receiver_id
    ).first()


    if not receiver:

        raise HTTPException(
            status_code=404,
            detail="Receiver tourist not found"
        )


    # --------------------------------------------------------
    # Existing pending request
    # --------------------------------------------------------

    existing_request = db.query(
        models.ChatRequest
    ).filter(
        models.ChatRequest.sender_id == sender_id,
        models.ChatRequest.receiver_id == receiver_id,
        models.ChatRequest.status == "PENDING"
    ).first()


    if existing_request:

        raise HTTPException(
            status_code=409,
            detail="Chat request already pending"
        )


    # --------------------------------------------------------
    # Create request
    # --------------------------------------------------------

    chat_request = models.ChatRequest(
        sender_id=sender_id,
        receiver_id=receiver_id,
        status="PENDING"
    )


    db.add(chat_request)

    db.commit()

    db.refresh(chat_request)


    print(
        f"[CHAT REQUEST] "
        f"{sender_id} -> {receiver_id}"
    )


    # ========================================================
    # ROUTE REQUEST TO RECEIVER NODE
    # ========================================================

    receiver_node = tourist_node_map.get(
        receiver_id
    )


    if receiver_node:

        packet = (
            "CHAT_REQUEST,"
            + str(chat_request.id)
            + ","
            + sender_id
            + ","
            + receiver_id
        )


        queue_for_node(
            receiver_node,
            packet
        )


        print(
            f"[CHAT REQUEST ROUTED] "
            f"{receiver_id} -> {receiver_node}"
        )


    return {

        "status":
            "REQUEST_SENT",

        "request_id":
            chat_request.id,

        "sender_id":
            sender_id,

        "receiver_id":
            receiver_id,

        "request_status":
            chat_request.status,

        "delivery": (
            "QUEUED"
            if receiver_node
            else "RECEIVER_OFFLINE"
        ),

        "created_at":
            chat_request.created_at
    }


# ============================================================
# GET CHAT REQUESTS
# ============================================================

@app.get("/chat/requests/{hawkeye_id}")
def get_chat_requests(
    hawkeye_id: str,
    db: Session = Depends(get_db)
):

    tourist = db.query(
        models.Tourist
    ).filter(
        models.Tourist.hawkeye_id == hawkeye_id
    ).first()


    if not tourist:

        raise HTTPException(
            status_code=404,
            detail="Tourist not found"
        )


    requests = db.query(
        models.ChatRequest
    ).filter(

        (
            models.ChatRequest.receiver_id == hawkeye_id
        )
        |
        (
            models.ChatRequest.sender_id == hawkeye_id
        )

    ).order_by(
        models.ChatRequest.created_at.desc()
    ).all()


    return {

        "hawkeye_id":
            hawkeye_id,

        "requests": [

            {
                "id":
                    request.id,

                "sender_id":
                    request.sender_id,

                "receiver_id":
                    request.receiver_id,

                "status":
                    request.status,

                "created_at":
                    request.created_at
            }

            for request in requests
        ]
    }


# ============================================================
# ACCEPT CHAT REQUEST
# ============================================================

@app.post("/chat/request/{request_id}/accept")
def accept_chat_request(
    request_id: int,
    action: schemas.ChatRequestAction,
    db: Session = Depends(get_db)
):

    chat_request = db.query(
        models.ChatRequest
    ).filter(
        models.ChatRequest.id == request_id
    ).first()


    if not chat_request:

        raise HTTPException(
            status_code=404,
            detail="Chat request not found"
        )


    # Only receiver can accept.

    if chat_request.receiver_id != action.hawkeye_id:

        raise HTTPException(
            status_code=403,
            detail="Only the receiver can accept this request"
        )


    # Request must still be pending.

    if chat_request.status != "PENDING":

        raise HTTPException(
            status_code=400,
            detail="Chat request is no longer pending"
        )


    # Mark accepted.

    chat_request.status = "ACCEPTED"


    # Normalize user ordering.

    user_a = min(
        chat_request.sender_id,
        chat_request.receiver_id
    )

    user_b = max(
        chat_request.sender_id,
        chat_request.receiver_id
    )


    # Find existing conversation.

    conversation = db.query(
        models.Conversation
    ).filter(
        models.Conversation.user_a == user_a,
        models.Conversation.user_b == user_b
    ).first()


    # Create if needed.

    if not conversation:

        conversation = models.Conversation(
            user_a=user_a,
            user_b=user_b
        )

        db.add(conversation)


    db.commit()

    db.refresh(conversation)


    print(
        f"[CHAT ACCEPTED] "
        f"{chat_request.sender_id} <-> "
        f"{chat_request.receiver_id}"
    )


    # ========================================================
    # NOTIFY SENDER
    # ========================================================

    sender_node = tourist_node_map.get(
        chat_request.sender_id
    )


    if sender_node:

        packet = (
            "CHAT_ACCEPTED,"
            + str(conversation.id)
            + ","
            + chat_request.sender_id
            + ","
            + chat_request.receiver_id
        )


        queue_for_node(
            sender_node,
            packet
        )


    return {

        "status":
            "REQUEST_ACCEPTED",

        "request_id":
            chat_request.id,

        "conversation_id":
            conversation.id,

        "user_a":
            conversation.user_a,

        "user_b":
            conversation.user_b
    }


# ============================================================
# DECLINE CHAT REQUEST
# ============================================================

@app.post("/chat/request/{request_id}/decline")
def decline_chat_request(
    request_id: int,
    action: schemas.ChatRequestAction,
    db: Session = Depends(get_db)
):

    chat_request = db.query(
        models.ChatRequest
    ).filter(
        models.ChatRequest.id == request_id
    ).first()


    if not chat_request:

        raise HTTPException(
            status_code=404,
            detail="Chat request not found"
        )


    # Only receiver can decline.

    if chat_request.receiver_id != action.hawkeye_id:

        raise HTTPException(
            status_code=403,
            detail="Only the receiver can decline this request"
        )


    # Request must still be pending.

    if chat_request.status != "PENDING":

        raise HTTPException(
            status_code=400,
            detail="Chat request is no longer pending"
        )


    chat_request.status = "DECLINED"

    db.commit()


    print(
        f"[CHAT DECLINED] "
        f"{chat_request.sender_id} -> "
        f"{chat_request.receiver_id}"
    )


    # ========================================================
    # NOTIFY SENDER
    # ========================================================

    sender_node = tourist_node_map.get(
        chat_request.sender_id
    )


    if sender_node:

        packet = (
            "CHAT_DECLINED,"
            + str(chat_request.id)
            + ","
            + chat_request.sender_id
            + ","
            + chat_request.receiver_id
        )


        queue_for_node(
            sender_node,
            packet
        )


    return {

        "status":
            "REQUEST_DECLINED",

        "request_id":
            chat_request.id
    }


# ============================================================
# CHAT CONVERSATIONS
# ============================================================

@app.get("/chat/conversations/{hawkeye_id}")
def get_conversations(
    hawkeye_id: str,
    db: Session = Depends(get_db)
):

    tourist = db.query(
        models.Tourist
    ).filter(
        models.Tourist.hawkeye_id == hawkeye_id
    ).first()


    if not tourist:

        raise HTTPException(
            status_code=404,
            detail="Tourist not found"
        )


    conversations = db.query(
        models.Conversation
    ).filter(

        (
            models.Conversation.user_a == hawkeye_id
        )
        |
        (
            models.Conversation.user_b == hawkeye_id
        )

    ).order_by(
        models.Conversation.created_at.desc()
    ).all()


    result = []


    for conversation in conversations:

        if conversation.user_a == hawkeye_id:

            other_user = conversation.user_b

        else:

            other_user = conversation.user_a


        latest_message = db.query(
            models.Message
        ).filter(
            models.Message.conversation_id == conversation.id
        ).order_by(
            models.Message.timestamp.desc()
        ).first()


        result.append({

            "conversation_id":
                conversation.id,

            "other_user":
                other_user,

            "created_at":
                conversation.created_at,

            "latest_message": (

                {

                    "sender_id":
                        latest_message.sender_id,

                    "message":
                        latest_message.message,

                    "timestamp":
                        latest_message.timestamp

                }

                if latest_message

                else None
            )
        })


    return {

        "hawkeye_id":
            hawkeye_id,

        "conversations":
            result
    }


# ============================================================
# SEND CHAT MESSAGE THROUGH HTTP
# ============================================================
#
# Kept for testing.
#
# Normal path:
#
# Android
#    ↓ BLE
# ESP
#    ↓ Wi-Fi
# /nodes/{node_id}/ble-message
#
# ============================================================

@app.post("/chat/messages")
def send_chat_message(
    message: schemas.MessageCreate,
    db: Session = Depends(get_db)
):

    conversation = db.query(
        models.Conversation
    ).filter(
        models.Conversation.id == message.conversation_id
    ).first()


    if not conversation:

        raise HTTPException(
            status_code=404,
            detail="Conversation not found"
        )


    sender_id = message.sender_id.strip()


    if sender_id not in (
        conversation.user_a,
        conversation.user_b
    ):

        raise HTTPException(
            status_code=403,
            detail="Sender is not a participant in this conversation"
        )


    text = message.message.strip()


    if not text:

        raise HTTPException(
            status_code=400,
            detail="Message cannot be empty"
        )


    # Determine receiver.

    if sender_id == conversation.user_a:

        receiver_id = conversation.user_b

    else:

        receiver_id = conversation.user_a


    # Store message.

    new_message = models.Message(
        conversation_id=conversation.id,
        sender_id=sender_id,
        message=text
    )


    db.add(new_message)

    db.commit()

    db.refresh(new_message)


    # Route to receiver node.

    receiver_node = tourist_node_map.get(
        receiver_id
    )


    if receiver_node:

        packet = (
            "CHAT,"
            + sender_id
            + ","
            + receiver_id
            + ","
            + text
        )


        queue_for_node(
            receiver_node,
            packet
        )


    print(
        f"[CHAT MESSAGE] "
        f"{sender_id} -> {receiver_id}: "
        f"{text}"
    )


    return {

        "status":
            "MESSAGE_SENT",

        "message_id":
            new_message.id,

        "conversation_id":
            new_message.conversation_id,

        "sender_id":
            new_message.sender_id,

        "receiver_id":
            receiver_id,

        "message":
            new_message.message,

        "delivery": (
            "QUEUED"
            if receiver_node
            else "RECEIVER_OFFLINE"
        ),

        "timestamp":
            new_message.timestamp
    }


# ============================================================
# GET CHAT MESSAGES
# ============================================================

@app.get("/chat/messages/{conversation_id}")
def get_chat_messages(
    conversation_id: int,
    hawkeye_id: str,
    db: Session = Depends(get_db)
):

    conversation = db.query(
        models.Conversation
    ).filter(
        models.Conversation.id == conversation_id
    ).first()


    if not conversation:

        raise HTTPException(
            status_code=404,
            detail="Conversation not found"
        )


    if hawkeye_id not in (
        conversation.user_a,
        conversation.user_b
    ):

        raise HTTPException(
            status_code=403,
            detail="You are not a participant in this conversation"
        )


    messages = db.query(
        models.Message
    ).filter(
        models.Message.conversation_id == conversation_id
    ).order_by(
        models.Message.timestamp.asc()
    ).all()


    return {

        "conversation_id":
            conversation_id,

        "user_a":
            conversation.user_a,

        "user_b":
            conversation.user_b,

        "messages": [

            {

                "id":
                    msg.id,

                "sender_id":
                    msg.sender_id,

                "message":
                    msg.message,

                "timestamp":
                    msg.timestamp
            }

            for msg in messages
        ]
    }


# ============================================================
# DEBUG: CONNECTIONS
# ============================================================

@app.get("/debug/connections")
def debug_connections():

    return {

        "tourist_node_map":
            tourist_node_map,

        "node_outbox":
            node_outbox
    }


# ============================================================
# DEBUG: QUEUE MESSAGE
# ============================================================
#
# This lets us test:
#
# FastAPI -> ESP -> Android
#
# before Android chat is completely wired.
#
# ============================================================

class DebugQueueMessage(BaseModel):

    message: str


@app.post("/debug/queue/{node_id}")
def debug_queue_message(
    node_id: str,
    data: DebugQueueMessage
):

    queue_for_node(
        node_id,
        data.message
    )


    return {

        "status":
            "QUEUED",

        "node_id":
            node_id,

        "message":
            data.message
    }