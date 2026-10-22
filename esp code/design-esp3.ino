#include <WiFi.h>
#include <HTTPClient.h>
#include <NimBLEDevice.h>

// ============================================================
// FORWARD DECLARATIONS
// ============================================================

void connectWiFi();
void startBLE();
void sendHeartbeat();
void sendToServer(String message);
void pollServerMessages();
void notifyAndroid(String message);
String jsonEscape(const String& input);


// ============================================================
// HAWKEYE NODE CONFIGURATION
// ============================================================

#define NODE_ID   "HWK-N03"
#define NODE_ZONE "Control"

NimBLECharacteristic* hawkeyeCharacteristic = nullptr;


// ============================================================
// COLLEGE WI-FI
// ============================================================

const char* WIFI_SSID = "SVKM NMIMS";

const char* EAP_IDENTITY = "70022400676";
const char* EAP_USERNAME = "70022400676";
const char* EAP_PASSWORD = "passw)123";


// ============================================================
// HAWKEYE SERVER
// ============================================================

const char* SERVER_IP = "10.160.81.20";
const int SERVER_PORT = 8000;


// ============================================================
// JSON ESCAPE
// ============================================================

String jsonEscape(const String& input) {

    String output;
    output.reserve(input.length() + 16);

    for (size_t i = 0; i < input.length(); i++) {

        char c = input[i];

        switch (c) {

            case '\\':
                output += "\\\\";
                break;

            case '"':
                output += "\\\"";
                break;

            case '\n':
                output += "\\n";
                break;

            case '\r':
                output += "\\r";
                break;

            case '\t':
                output += "\\t";
                break;

            default:
                output += c;
                break;
        }
    }

    return output;
}


// ============================================================
// SERVER -> ANDROID BLE NOTIFICATION
// ============================================================

void notifyAndroid(String message) {

    if (hawkeyeCharacteristic == nullptr) {

        Serial.println("BLE characteristic unavailable for notification.");

        return;
    }

    if (message.length() == 0) {

        return;
    }

    Serial.println();
    Serial.println("================================");
    Serial.println("SERVER MESSAGE READY FOR ANDROID");
    Serial.print("Packet: ");
    Serial.println(message);

    hawkeyeCharacteristic->setValue(
        (uint8_t*) message.c_str(),
        message.length()
    );

    hawkeyeCharacteristic->notify();

    Serial.println("BLE NOTIFICATION SENT");
    Serial.println("================================");
}


// ============================================================
// POLL FASTAPI FOR SERVER -> PHONE MESSAGES
// ============================================================
//
// Expected server contract:
//
// GET /nodes/<NODE_ID>/outbox
//
// 200 + text/plain body  -> one packet to notify to Android
// 204                    -> no pending packet
//
// Example body:
//
// CHAT,HWK-U002,HWK-U001,Hello
//
// ============================================================

void pollServerMessages() {

    if (WiFi.status() != WL_CONNECTED) {

        return;
    }

    HTTPClient http;

    String url =
        "http://" +
        String(SERVER_IP) +
        ":" +
        String(SERVER_PORT) +
        "/nodes/" +
        String(NODE_ID) +
        "/outbox";

    http.begin(url);

    int responseCode =
        http.GET();

    if (responseCode == 200) {

        String message =
            http.getString();

        message.trim();

        if (message.length() > 0) {

            Serial.print("OUTBOX RX: ");
            Serial.println(message);

            notifyAndroid(message);
        }

    } else if (responseCode != 204) {

        // Avoid flooding Serial Monitor for the normal empty-queue case.

        Serial.print("Outbox HTTP: ");
        Serial.println(responseCode);
    }

    http.end();
}


// ============================================================
// HEARTBEAT
// ============================================================

unsigned long lastHeartbeat = 0;

const unsigned long HEARTBEAT_INTERVAL = 10000;


// ============================================================
// SERVER -> BLE MESSAGE POLLING
// ============================================================

unsigned long lastMessagePoll = 0;

const unsigned long MESSAGE_POLL_INTERVAL = 1000;


// ============================================================
// BLE UUIDs
// ============================================================

#define SERVICE_UUID \
"7b7b0001-0000-4a57-8a10-000000000001"

#define CHARACTERISTIC_UUID \
"7b7b0002-0000-4a57-8a10-000000000001"


// ============================================================
// BLE CALLBACK
// ============================================================

class HawkeyeCallback : public NimBLECharacteristicCallbacks {

    void onWrite(
        NimBLECharacteristic* characteristic,
        NimBLEConnInfo& connInfo
    ) override {

        Serial.println();
        Serial.println("================================");
        Serial.println("BLE WRITE RECEIVED");


        // --------------------------------------------------------
        // GET VALUE
        // --------------------------------------------------------

        std::string value =
            characteristic->getValue();


        if (value.length() == 0) {

            Serial.println("ERROR: Empty BLE message");

            Serial.println("================================");

            return;
        }


        // --------------------------------------------------------
        // PRINT RAW DATA
        // --------------------------------------------------------

        Serial.print("Bytes received: ");
        Serial.println(value.length());


        Serial.print("BLE MESSAGE: ");

        for (size_t i = 0; i < value.length(); i++) {

            Serial.print(value[i]);
        }

        Serial.println();


        // --------------------------------------------------------
        // CONVERT TO ARDUINO STRING
        // --------------------------------------------------------

        String message =
            String(value.c_str());


        Serial.print("Parsed message: ");

        Serial.println(message);


        // --------------------------------------------------------
        // FORWARD TO FASTAPI
        // --------------------------------------------------------

        sendToServer(message);


        Serial.println("================================");
    }
};


// ============================================================
// SEND MESSAGE TO FASTAPI
// ============================================================

void sendToServer(String message) {

    if (WiFi.status() != WL_CONNECTED) {

        Serial.println("Wi-Fi unavailable.");

        return;
    }


    HTTPClient http;


    String url =
        "http://" +
        String(SERVER_IP) +
        ":" +
        String(SERVER_PORT) +
        "/nodes/" +
        String(NODE_ID) +
        "/ble-message";


    Serial.println();

    Serial.println("Sending BLE message to Hawkeye server:");

    Serial.println(url);


    http.begin(url);


    http.addHeader(
        "Content-Type",
        "application/json"
    );


    // --------------------------------------------------------
    // CREATE JSON
    // --------------------------------------------------------

    String json =
        "{\"message\":\"" +
        jsonEscape(message) +
        "\"}";


    Serial.print("HTTP JSON: ");

    Serial.println(json);


    // --------------------------------------------------------
    // POST
    // --------------------------------------------------------

    int responseCode =
        http.POST(json);


    Serial.print("HTTP Response: ");

    Serial.println(responseCode);


    if (responseCode > 0) {

        String response =
            http.getString();


        Serial.print("Server Response: ");

        Serial.println(response);

    } else {

        Serial.print("HTTP ERROR: ");

        Serial.println(
            http.errorToString(responseCode)
        );
    }


    http.end();
}


// ============================================================
// HEARTBEAT
// ============================================================

void sendHeartbeat() {

    if (WiFi.status() != WL_CONNECTED) {

        return;
    }


    HTTPClient http;


    String url =
        "http://" +
        String(SERVER_IP) +
        ":" +
        String(SERVER_PORT) +
        "/nodes/" +
        String(NODE_ID) +
        "/heartbeat";


    http.begin(url);


    int responseCode =
        http.POST("");


    Serial.print("Heartbeat HTTP: ");

    Serial.println(responseCode);


    if (responseCode > 0) {

        String response =
            http.getString();


        Serial.print("Heartbeat: ");

        Serial.println(response);
    }


    http.end();
}


// ============================================================
// WIFI CONNECTION
// ============================================================

void connectWiFi() {

    Serial.println();

    Serial.println("================================");

    Serial.println("PROJECT HAWKEYE NODE");


    Serial.print("Node ID: ");

    Serial.println(NODE_ID);


    Serial.print("Zone: ");

    Serial.println(NODE_ZONE);


    Serial.println("CONNECTING TO COLLEGE WI-FI");

    Serial.println("WPA2-ENTERPRISE / PEAP");


    WiFi.mode(WIFI_STA);


    Serial.println(
        "Starting WPA2 Enterprise..."
    );


    WiFi.begin(
        WIFI_SSID,
        WPA2_AUTH_PEAP,
        EAP_IDENTITY,
        EAP_USERNAME,
        EAP_PASSWORD
    );


    Serial.print("Connecting");


    unsigned long startAttempt =
        millis();


    while (
        WiFi.status() != WL_CONNECTED &&
        millis() - startAttempt < 30000
    ) {

        delay(500);

        Serial.print(".");
    }


    Serial.println();


    if (WiFi.status() == WL_CONNECTED) {

        Serial.println(
            "COLLEGE WI-FI CONNECTED"
        );


        Serial.print("Node IP: ");

        Serial.println(
            WiFi.localIP()
        );


        Serial.print("Gateway: ");

        Serial.println(
            WiFi.gatewayIP()
        );


        Serial.print("RSSI: ");

        Serial.println(
            WiFi.RSSI()
        );


        Serial.print(
            "Hawkeye Server: "
        );

        Serial.print(SERVER_IP);

        Serial.print(":");

        Serial.println(SERVER_PORT);

    } else {

        Serial.println(
            "WI-FI CONNECTION FAILED"
        );


        Serial.print(
            "WiFi status code: "
        );


        Serial.println(
            WiFi.status()
        );


        Serial.println(
            "Restarting in 5 seconds..."
        );


        delay(5000);

        ESP.restart();
    }
}


// ============================================================
// BLE SETUP
// ============================================================

void startBLE() {

    Serial.println();

    Serial.println("================================");

    Serial.println(
        "STARTING HAWKEYE BLE"
    );


    // --------------------------------------------------------
    // INITIALIZE
    // --------------------------------------------------------

    NimBLEDevice::init(
        "HAWKEYE-HWK-N02"
    );


    // --------------------------------------------------------
    // CREATE SERVER
    // --------------------------------------------------------

    NimBLEServer* server =
        NimBLEDevice::createServer();


    // --------------------------------------------------------
    // CREATE SERVICE
    // --------------------------------------------------------

    NimBLEService* service =
        server->createService(
            SERVICE_UUID
        );


    // --------------------------------------------------------
    // CREATE CHARACTERISTIC
    // --------------------------------------------------------

    hawkeyeCharacteristic =
        service->createCharacteristic(

            CHARACTERISTIC_UUID,

            NIMBLE_PROPERTY::WRITE |
            NIMBLE_PROPERTY::WRITE_NR |
            NIMBLE_PROPERTY::READ |
            NIMBLE_PROPERTY::NOTIFY
        );


    // --------------------------------------------------------
    // CALLBACK
    // --------------------------------------------------------

    hawkeyeCharacteristic->setCallbacks(
        new HawkeyeCallback()
    );


    // --------------------------------------------------------
    // INITIAL VALUE
    // --------------------------------------------------------

    hawkeyeCharacteristic->setValue(
        "HAWKEYE_READY"
    );


    // --------------------------------------------------------
    // START SERVICE
    // --------------------------------------------------------

    service->start();


    // --------------------------------------------------------
    // ADVERTISE
    // --------------------------------------------------------

    NimBLEAdvertising* advertising =
        NimBLEDevice::getAdvertising();


    advertising->setName(
        "HAWKEYE-HWK-N03"
    );


    advertising->addServiceUUID(
        SERVICE_UUID
    );


    advertising->enableScanResponse(true);


    advertising->start();


    Serial.println(
        "BLE ADVERTISING STARTED"
    );


    Serial.println(
        "BLE Name: HAWKEYE-HWK-N03"
    );


    Serial.println(
        "BLE Service UUID: " +
        String(SERVICE_UUID)
    );


    // --------------------------------------------------------
    // READY
    // --------------------------------------------------------

    Serial.println();

    Serial.println(
        "BLE READY"
    );


    Serial.println(
        "BLE Name: HAWKEYE-HWK-N03"
    );


    Serial.println(
        "BLE Service UUID:"
    );


    Serial.println(
        SERVICE_UUID
    );


    Serial.println(
        "BLE Characteristic UUID:"
    );


    Serial.println(
        CHARACTERISTIC_UUID
    );


    Serial.println(
        "Waiting for Android..."
    );


    Serial.println(
        "================================"
    );
}


// ============================================================
// SETUP
// ============================================================

void setup() {

    Serial.begin(115200);

    delay(1000);


    connectWiFi();


    startBLE();


    sendHeartbeat();
}


// ============================================================
// LOOP
// ============================================================

void loop() {

    unsigned long currentTime =
        millis();


    // --------------------------------------------------------
    // HEARTBEAT
    // --------------------------------------------------------

    if (
        currentTime - lastHeartbeat >=
        HEARTBEAT_INTERVAL
    ) {

        lastHeartbeat =
            currentTime;


        sendHeartbeat();
    }


    // --------------------------------------------------------
    // SERVER -> BLE CHAT POLLING
    // --------------------------------------------------------

    if (
        currentTime - lastMessagePoll >=
        MESSAGE_POLL_INTERVAL
    ) {

        lastMessagePoll =
            currentTime;


        pollServerMessages();
    }


    delay(50);
}