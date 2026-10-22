package com.example.projecthawkeye;

import android.Manifest;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;


@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    private static MainActivity getActiveMainActivity;
    // ============================================================
    // CONFIGURATION
    // ============================================================

    private static final int REQUEST_BLUETOOTH = 100;
    private static final int REQUEST_LOCATION = 200;

    private static final long SCAN_DURATION = 5000;
    private static final long LOCATION_INTERVAL = 5000;

    /*
     * Hawkeye BLE Service
     */
    private static final UUID SERVICE_UUID =
            UUID.fromString(
                    "7b7b0001-0000-4a57-8a10-000000000001"
            );

    /*
     * Hawkeye BLE Characteristic
     *
     * Current ESP32 firmware uses this characteristic for:
     *
     * WRITE
     * READ
     * NOTIFY
     */
    private static final UUID CHARACTERISTIC_UUID =
            UUID.fromString(
                    "7b7b0002-0000-4a57-8a10-000000000001"
            );

    /*
     * Client Characteristic Configuration Descriptor
     */
    private static final UUID CLIENT_CONFIG_DESCRIPTOR_UUID =
            UUID.fromString(
                    "00002902-0000-1000-8000-00805f9b34fb"
            );


    // ============================================================
    // TOURIST
    // ============================================================

    /*
     * Current Android tourist.
     */
    public static String HAWKEYE_ID;


    // ============================================================
    // SERVER / PROTOCOL
    // ============================================================

    /*
     * The laptop/server is currently:
     *
     * 10.160.81.20
     *
     * MainActivity itself does NOT need to use HTTP for normal
     * BLE communication.
     *
     * ESP32 acts as the BLE -> Wi-Fi bridge.
     */
    public static final String SERVER_URL =
            "http://10.160.81.20:8000";


    // ============================================================
    // BROADCAST ACTIONS
    // ============================================================

    /*
     * These broadcasts allow MessagesActivity / ChatActivity
     * to receive BLE events from MainActivity.
     */

    public static final String ACTION_CHAT_REQUEST =
            "com.example.projecthawkeye.CHAT_REQUEST";

    public static final String ACTION_CHAT_ACCEPTED =
            "com.example.projecthawkeye.CHAT_ACCEPTED";

    public static final String ACTION_CHAT_DECLINED =
            "com.example.projecthawkeye.CHAT_DECLINED";

    public static final String ACTION_CHAT_MESSAGE =
            "com.example.projecthawkeye.CHAT_MESSAGE";

    public static final String ACTION_BLE_STATUS =
            "com.example.projecthawkeye.BLE_STATUS";


    // ============================================================
    // INTENT EXTRA KEYS
    // ============================================================

    public static final String EXTRA_REQUEST_ID =
            "request_id";

    public static final String EXTRA_CONVERSATION_ID =
            "conversation_id";

    public static final String EXTRA_SENDER_ID =
            "sender_id";

    public static final String EXTRA_RECEIVER_ID =
            "receiver_id";

    public static final String EXTRA_MESSAGE =
            "message";


    // ============================================================
    // SELECTED NODE
    // ============================================================

    private String selectedNodeId = null;
    private String selectedNodeZone = null;


    // ============================================================
    // BLUETOOTH
    // ============================================================

    private BluetoothAdapter bluetoothAdapter;

    private BluetoothLeScanner bluetoothLeScanner;

    private BluetoothDevice hawkeyeDevice;

    private BluetoothGatt bluetoothGatt;

    /*
     * Characteristic used for outgoing messages.
     */
    private BluetoothGattCharacteristic writeCharacteristic;

    /*
     * Characteristic used for incoming notifications.
     */
    private BluetoothGattCharacteristic notifyCharacteristic;


    // ============================================================
    // DISCOVERED NODES
    // ============================================================

    /*
     * Example:
     *
     * HWK-N01 -> BluetoothDevice
     * HWK-N02 -> BluetoothDevice
     * HWK-N03 -> BluetoothDevice
     */
    private final Map<String, BluetoothDevice> discoveredNodes =
            new HashMap<>();


    // ============================================================
    // LOCATION
    // ============================================================

    private LocationManager locationManager;

    private Location latestLocation;

    private boolean locationTracking = false;


    // ============================================================
    // UI
    // ============================================================

    private TextView statusText;
    private TextView nodeText;
    private TextView logText;

    private Button scanButton;
    private Button connectButton;
    private Button locationButton;
    private Button sosButton;


    // ============================================================
    // STATE
    // ============================================================

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean scanning = false;

    private boolean gattWriteInProgress = false;


    // ============================================================
    // ACTIVITY
    // ============================================================
    @Override
    protected void onStart() {
        super.onStart();
        activeMainActivity = this;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        HAWKEYE_ID = getOrCreateHawkeyeId();

        setContentView(R.layout.activity_main);


        // ========================================================
        // UI
        // ========================================================

        statusText = findViewById(R.id.statusText);
        nodeText = findViewById(R.id.nodeText);
        logText = findViewById(R.id.logText);

        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        locationButton = findViewById(R.id.locationButton);
        sosButton = findViewById(R.id.sosButton);

        Button messagesButton = findViewById(R.id.messagesButton);

        messagesButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MessagesActivity.class);
            startActivity(intent);
        });
        // ========================================================
        // INITIAL UI STATE
        // ========================================================

        if (statusText != null) {
            statusText.setText(
                    "Status: Ready"
            );
        }

        if (nodeText != null) {
            nodeText.setText(
                    "No Hawkeye node selected."
            );
        }

        if (connectButton != null) {
            connectButton.setEnabled(false);
        }

        if (locationButton != null) {
            locationButton.setEnabled(false);
        }

        if (sosButton != null) {
            sosButton.setEnabled(false);
        }


        // ========================================================
        // BLUETOOTH MANAGER
        // ========================================================

        BluetoothManager bluetoothManager =
                (BluetoothManager)
                        getSystemService(
                                Context.BLUETOOTH_SERVICE
                        );

        if (bluetoothManager != null) {

            bluetoothAdapter =
                    bluetoothManager.getAdapter();
        }


        // ========================================================
        // LOCATION MANAGER
        // ========================================================

        locationManager =
                (LocationManager)
                        getSystemService(
                                Context.LOCATION_SERVICE
                        );


        // ========================================================
        // BUTTONS
        // ========================================================

        if (scanButton != null) {

            scanButton.setOnClickListener(
                    v -> startScan()
            );
        }


        if (connectButton != null) {

            connectButton.setOnClickListener(
                    v -> connectToNode()
            );
        }


        if (locationButton != null) {

            locationButton.setOnClickListener(
                    v -> {

                        if (!locationTracking) {

                            startLocationTracking();

                        } else {

                            stopLocationTracking();
                        }
                    }
            );
        }


        if (sosButton != null) {

            sosButton.setOnClickListener(
                    v -> sendSOS()
            );
        }


        // ========================================================
        // PERMISSIONS
        // ========================================================

        requestBluetoothPermissions();

        requestLocationPermission();


        log("================================");
        log("PROJECT HAWKEYE");
        log("Android Tourist Client");
        log("Tourist ID: " + HAWKEYE_ID);
        log("System ready.");
    }


    // ============================================================
    // BLUETOOTH PERMISSIONS
    // ============================================================

    private void requestBluetoothPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            boolean scanPermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED;


            boolean connectPermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED;


            if (!scanPermission || !connectPermission) {

                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                        },
                        REQUEST_BLUETOOTH
                );
            }
        }
    }


    private boolean hasBluetoothPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            return ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

                    &&

                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }


    // ============================================================
    // LOCATION PERMISSION
    // ============================================================

    private void requestLocationPermission() {

        boolean finePermission =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;


        boolean coarsePermission =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;


        if (!finePermission && !coarsePermission) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_LOCATION
            );
        }
    }


    private boolean hasLocationPermission() {

        boolean finePermission =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;


        boolean coarsePermission =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;


        return finePermission || coarsePermission;
    }


    // ============================================================
    // START BLE SCAN
    // ============================================================

    private void startScan() {

        if (!hasBluetoothPermission()) {

            log("Bluetooth permission not granted.");

            requestBluetoothPermissions();

            return;
        }


        if (bluetoothAdapter == null) {

            log("Bluetooth unavailable.");

            return;
        }


        if (!bluetoothAdapter.isEnabled()) {

            log("Please turn Bluetooth ON.");

            setStatus(
                    "Status: Turn Bluetooth ON"
            );

            return;
        }


        bluetoothLeScanner =
                bluetoothAdapter.getBluetoothLeScanner();


        if (bluetoothLeScanner == null) {

            log("BLE scanner unavailable.");

            return;
        }


        // ========================================================
        // RESET DISCOVERY
        // ========================================================

        discoveredNodes.clear();

        hawkeyeDevice = null;

        selectedNodeId = null;

        selectedNodeZone = null;


        if (connectButton != null) {
            connectButton.setEnabled(false);
        }

        if (locationButton != null) {
            locationButton.setEnabled(false);
        }

        if (sosButton != null) {
            sosButton.setEnabled(false);
        }


        if (nodeText != null) {

            nodeText.setText(
                    "Scanning for Hawkeye nodes..."
            );
        }


        setStatus(
                "Status: Scanning"
        );


        log("================================");
        log("STARTING HAWKEYE BLE SCAN");
        log("Service UUID: " + SERVICE_UUID);


        scanning = true;


        bluetoothLeScanner.startScan(
                null,
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings.SCAN_MODE_LOW_LATENCY
                        )
                        .build(),
                scanCallback
        );


        // ========================================================
        // STOP AFTER 5 SECONDS
        // ========================================================

        handler.postDelayed(
                () -> {

                    if (scanning) {

                        stopScan();

                        showNodeSelectionDialog();
                    }

                },
                SCAN_DURATION
        );
    }


    // ============================================================
    // STOP BLE SCAN
    // ============================================================

    private void stopScan() {

        if (!hasBluetoothPermission()) {
            return;
        }


        if (
                bluetoothLeScanner != null
                        &&
                        scanning
        ) {

            bluetoothLeScanner.stopScan(
                    scanCallback
            );

            scanning = false;

            log("BLE scan stopped.");
        }
    }


    // ============================================================
    // BLE SCAN CALLBACK
    // ============================================================

    private final ScanCallback scanCallback =
            new ScanCallback() {

                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result
                ) {

                    BluetoothDevice device =
                            result.getDevice();


                    ScanRecord record =
                            result.getScanRecord();


                    if (record == null) {
                        return;
                    }


                    // ====================================================
                    // CHECK SERVICE UUID
                    // ====================================================

                    List<ParcelUuid> serviceUuids =
                            record.getServiceUuids();


                    if (serviceUuids == null) {
                        return;
                    }


                    boolean hawkeyeFound = false;


                    for (ParcelUuid uuid : serviceUuids) {

                        if (
                                SERVICE_UUID.equals(
                                        uuid.getUuid()
                                )
                        ) {

                            hawkeyeFound = true;

                            break;
                        }
                    }


                    if (!hawkeyeFound) {
                        return;
                    }


                    // ====================================================
                    // DEVICE NAME
                    // ====================================================

                    String deviceName =
                            record.getDeviceName();


                    if (
                            deviceName == null
                                    ||
                                    deviceName.trim().isEmpty()
                    ) {

                        try {

                            deviceName =
                                    device.getName();

                        } catch (Exception ignored) {
                        }
                    }


                    if (
                            deviceName == null
                                    ||
                                    deviceName.trim().isEmpty()
                    ) {

                        deviceName =
                                "HAWKEYE NODE";
                    }


                    // ====================================================
                    // NODE ID
                    // ====================================================

                    String nodeId =
                            extractNodeId(deviceName);


                    if (nodeId == null) {

                        log(
                                "Hawkeye service detected but " +
                                        "node ID could not be extracted."
                        );

                        return;
                    }


                    // ====================================================
                    // ZONE
                    // ====================================================

                    String zone =
                            getZoneForNode(nodeId);


                    // ====================================================
                    // STORE
                    // ====================================================

                    if (!discoveredNodes.containsKey(nodeId)) {

                        discoveredNodes.put(
                                nodeId,
                                device
                        );


                        log("================================");
                        log("HAWKEYE NODE FOUND");
                        log("Node ID: " + nodeId);
                        log("Zone: " + zone);
                        log("BLE Name: " + deviceName);
                        log("Address: " + device.getAddress());
                        log("RSSI: " + result.getRssi() + " dBm");
                    }
                }


                @Override
                public void onScanFailed(
                        int errorCode
                ) {

                    scanning = false;


                    runOnUiThread(
                            () -> {

                                setStatus(
                                        "Status: Scan failed"
                                );

                                log(
                                        "BLE scan failed: " +
                                                errorCode
                                );
                            }
                    );
                }
            };


    // ============================================================
    // EXTRACT NODE ID
    // ============================================================

    private String extractNodeId(
            String deviceName
    ) {

        if (deviceName == null) {
            return null;
        }


        if (deviceName.contains("HWK-N01")) {
            return "HWK-N01";
        }


        if (deviceName.contains("HWK-N02")) {
            return "HWK-N02";
        }


        if (deviceName.contains("HWK-N03")) {
            return "HWK-N03";
        }


        return null;
    }


    // ============================================================
    // NODE -> ZONE
    // ============================================================

    private String getZoneForNode(
            String nodeId
    ) {

        if (nodeId == null) {
            return "Unknown Zone";
        }


        switch (nodeId) {

            case "HWK-N01":
                return "Harshvardhan";

            case "HWK-N02":
                return "Anshul";

            case "HWK-N03":
                return "Leesa";

            default:
                return "Unknown Zone";
        }
    }


    // ============================================================
    // NODE SELECTION
    // ============================================================

    private void showNodeSelectionDialog() {

        runOnUiThread(
                () -> {

                    if (discoveredNodes.isEmpty()) {

                        setStatus(
                                "Status: No Hawkeye nodes found"
                        );


                        if (nodeText != null) {

                            nodeText.setText(
                                    "No Hawkeye nodes detected."
                            );
                        }


                        log(
                                "No Hawkeye nodes found."
                        );

                        return;
                    }


                    List<String> nodeIds =
                            new ArrayList<>(
                                    discoveredNodes.keySet()
                            );


                    nodeIds.sort(
                            String::compareTo
                    );


                    String[] displayItems =
                            new String[nodeIds.size()];


                    for (
                            int i = 0;
                            i < nodeIds.size();
                            i++
                    ) {

                        String nodeId =
                                nodeIds.get(i);

                        String zone =
                                getZoneForNode(nodeId);


                        displayItems[i] =
                                nodeId +
                                        "\n" +
                                        zone;
                    }


                    new AlertDialog.Builder(this)

                            .setTitle(
                                    "Select Hawkeye Node"
                            )

                            .setItems(
                                    displayItems,
                                    (dialog, which) -> {

                                        String nodeId =
                                                nodeIds.get(which);

                                        selectNode(nodeId);
                                    }
                            )

                            .setNegativeButton(
                                    "Cancel",
                                    null
                            )

                            .show();
                }
        );
    }


    // ============================================================
    // SELECT NODE
    // ============================================================

    private void selectNode(
            String nodeId
    ) {

        selectedNodeId = nodeId;

        selectedNodeZone =
                getZoneForNode(nodeId);

        hawkeyeDevice =
                discoveredNodes.get(nodeId);


        if (hawkeyeDevice == null) {

            log(
                    "Selected node device unavailable."
            );

            return;
        }


        if (nodeText != null) {

            nodeText.setText(
                    "Selected Node:\n" +
                            selectedNodeId +
                            " | " +
                            selectedNodeZone +
                            "\n\nAddress:\n" +
                            hawkeyeDevice.getAddress()
            );
        }


        setStatus(
                "Status: Node selected"
        );


        if (connectButton != null) {

            connectButton.setEnabled(true);
        }


        log("================================");
        log("NODE SELECTED");
        log("Node ID: " + selectedNodeId);
        log("Zone: " + selectedNodeZone);
        log("Ready to connect.");
    }


    // ============================================================
    // CONNECT TO NODE
    // ============================================================

    private void connectToNode() {

        if (hawkeyeDevice == null) {

            log(
                    "No Hawkeye node selected."
            );

            return;
        }


        if (selectedNodeId == null) {

            log(
                    "Please select a Hawkeye node first."
            );

            return;
        }


        if (!hasBluetoothPermission()) {

            requestBluetoothPermissions();

            return;
        }


        setStatus(
                "Status: Connecting..."
        );


        if (connectButton != null) {
            connectButton.setEnabled(false);
        }


        log("================================");
        log(
                "CONNECTING TO " +
                        selectedNodeId
        );

        log(
                "ZONE: " +
                        selectedNodeZone
        );


        // ========================================================
        // CLOSE OLD GATT
        // ========================================================

        if (bluetoothGatt != null) {

            try {

                bluetoothGatt.disconnect();

            } catch (Exception ignored) {
            }


            try {

                bluetoothGatt.close();

            } catch (Exception ignored) {
            }


            bluetoothGatt = null;
        }


        // ========================================================
        // CONNECT
        // ========================================================

        bluetoothGatt =
                hawkeyeDevice.connectGatt(
                        this,
                        false,
                        gattCallback
                );
    }


    // ============================================================
    // GATT CALLBACK
    // ============================================================

    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {


                // ========================================================
                // CONNECTION STATE
                // ========================================================

                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt,
                        int status,
                        int newState
                ) {

                    super.onConnectionStateChange(
                            gatt,
                            status,
                            newState
                    );


                    if (
                            newState ==
                                    BluetoothProfile.STATE_CONNECTED
                    ) {

                        bluetoothGatt = gatt;


                        runOnUiThread(
                                () -> {

                                    setStatus(
                                            "Status: Connected"
                                    );

                                    log("================================");
                                    log("BLE CONNECTED.");
                                    log(
                                            "Connected Node: " +
                                                    selectedNodeId
                                    );

                                    log(
                                            "Zone: " +
                                                    selectedNodeZone
                                    );

                                    log(
                                            "Requesting larger MTU..."
                                    );
                                }
                        );


                        boolean mtuRequested =
                                gatt.requestMtu(247);


                        if (!mtuRequested) {

                            runOnUiThread(
                                    () -> log(
                                            "MTU request could not be started."
                                    )
                            );


                            gatt.discoverServices();
                        }

                    } else if (
                            newState ==
                                    BluetoothProfile.STATE_DISCONNECTED
                    ) {

                        runOnUiThread(
                                () -> {

                                    setStatus(
                                            "Status: Disconnected"
                                    );


                                    if (locationButton != null) {
                                        locationButton.setEnabled(false);
                                    }


                                    if (sosButton != null) {
                                        sosButton.setEnabled(false);
                                    }


                                    if (connectButton != null) {
                                        connectButton.setEnabled(true);
                                    }


                                    gattWriteInProgress = false;

                                    notifyCharacteristic = null;

                                    writeCharacteristic = null;


                                    log(
                                            "BLE DISCONNECTED."
                                    );

                                    log(
                                            "GATT status: " +
                                                    status
                                    );
                                }
                        );
                    }
                }


                // ========================================================
                // MTU
                // ========================================================

                @Override
                public void onMtuChanged(
                        BluetoothGatt gatt,
                        int mtu,
                        int status
                ) {

                    super.onMtuChanged(
                            gatt,
                            mtu,
                            status
                    );


                    runOnUiThread(
                            () -> {

                                if (
                                        status ==
                                                BluetoothGatt.GATT_SUCCESS
                                ) {

                                    log(
                                            "MTU negotiated: " +
                                                    mtu
                                    );

                                } else {

                                    log(
                                            "MTU negotiation failed: " +
                                                    status
                                    );
                                }


                                log(
                                        "Discovering services..."
                                );
                            }
                    );


                    gatt.discoverServices();
                }


                // ========================================================
                // SERVICES DISCOVERED
                // ========================================================

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt,
                        int status
                ) {

                    super.onServicesDiscovered(
                            gatt,
                            status
                    );


                    if (
                            status !=
                                    BluetoothGatt.GATT_SUCCESS
                    ) {

                        runOnUiThread(
                                () -> log(
                                        "Service discovery FAILED: " +
                                                status
                                )
                        );

                        return;
                    }


                    BluetoothGattService service =
                            gatt.getService(
                                    SERVICE_UUID
                            );


                    if (service == null) {

                        runOnUiThread(
                                () -> {

                                    setStatus(
                                            "Status: Service not found"
                                    );

                                    log(
                                            "HAWKEYE SERVICE NOT FOUND"
                                    );
                                }
                        );

                        return;
                    }


                    writeCharacteristic =
                            service.getCharacteristic(
                                    CHARACTERISTIC_UUID
                            );


                    if (writeCharacteristic == null) {

                        runOnUiThread(
                                () -> {

                                    setStatus(
                                            "Status: Characteristic not found"
                                    );

                                    log(
                                            "HAWKEYE CHARACTERISTIC NOT FOUND"
                                    );
                                }
                        );

                        return;
                    }


                    /*
                     * Same characteristic handles WRITE + NOTIFY.
                     */
                    notifyCharacteristic =
                            writeCharacteristic;


                    runOnUiThread(
                            () -> {

                                setStatus(
                                        "Status: Enabling BLE notifications..."
                                );

                                log("================================");
                                log("HAWKEYE SERVICE FOUND");
                                log("BLE CHARACTERISTIC FOUND");
                                log("Enabling BLE notifications...");
                            }
                    );


                    // ====================================================
                    // ENABLE LOCAL NOTIFICATION
                    // ====================================================

                    boolean enabled =
                            gatt.setCharacteristicNotification(
                                    notifyCharacteristic,
                                    true
                            );


                    if (!enabled) {

                        runOnUiThread(
                                () -> log(
                                        "FAILED to enable BLE notifications."
                                )
                        );

                        return;
                    }


                    // ====================================================
                    // FIND CCCD
                    // ====================================================

                    BluetoothGattDescriptor descriptor =
                            notifyCharacteristic.getDescriptor(
                                    CLIENT_CONFIG_DESCRIPTOR_UUID
                            );


                    if (descriptor == null) {

                        runOnUiThread(
                                () -> log(
                                        "BLE notification descriptor NOT FOUND."
                                )
                        );

                        return;
                    }


                    // ====================================================
                    // ENABLE NOTIFICATIONS
                    // ====================================================

                    if (
                            Build.VERSION.SDK_INT >=
                                    Build.VERSION_CODES.TIRAMISU
                    ) {

                        gatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor
                                        .ENABLE_NOTIFICATION_VALUE
                        );

                    } else {

                        descriptor.setValue(
                                BluetoothGattDescriptor
                                        .ENABLE_NOTIFICATION_VALUE
                        );

                        gatt.writeDescriptor(
                                descriptor
                        );
                    }
                }


                // ========================================================
                // DESCRIPTOR WRITE
                // ========================================================

                @Override
                public void onDescriptorWrite(
                        BluetoothGatt gatt,
                        BluetoothGattDescriptor descriptor,
                        int status
                ) {

                    super.onDescriptorWrite(
                            gatt,
                            descriptor,
                            status
                    );


                    if (
                            status !=
                                    BluetoothGatt.GATT_SUCCESS
                    ) {

                        runOnUiThread(
                                () -> log(
                                        "BLE notification setup FAILED: " +
                                                status
                                )
                        );

                        return;
                    }


                    runOnUiThread(
                            () -> {

                                setStatus(
                                        "Status: BLE Ready"
                                );


                                if (locationButton != null) {
                                    locationButton.setEnabled(true);
                                }


                                if (sosButton != null) {
                                    sosButton.setEnabled(true);
                                }


                                log("================================");
                                log("BLE NOTIFICATIONS ENABLED");
                                log("HAWKEYE BLE COMMUNICATION READY");
                                log("Registering tourist...");
                            }
                    );


                    // ====================================================
                    // REGISTER TOURIST
                    // ====================================================

                    sendMessage(
                            "REGISTER," +
                                    HAWKEYE_ID
                    );


                    broadcastBleStatus(
                            "READY"
                    );
                }


                // ========================================================
                // CHARACTERISTIC WRITE COMPLETE
                // ========================================================

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int status
                ) {

                    super.onCharacteristicWrite(
                            gatt,
                            characteristic,
                            status
                    );


                    gattWriteInProgress = false;


                    runOnUiThread(
                            () -> {

                                if (
                                        status ==
                                                BluetoothGatt.GATT_SUCCESS
                                ) {

                                    log(
                                            "BLE WRITE CONFIRMED."
                                    );

                                } else {

                                    log(
                                            "BLE WRITE FAILED."
                                    );

                                    log(
                                            "GATT status: " +
                                                    status
                                    );
                                }
                            }
                    );
                }


                // ========================================================
                // BLE NOTIFICATION
                // ========================================================

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic
                ) {

                    super.onCharacteristicChanged(
                            gatt,
                            characteristic
                    );


                    if (
                            !CHARACTERISTIC_UUID.equals(
                                    characteristic.getUuid()
                            )
                    ) {

                        return;
                    }


                    byte[] data =
                            characteristic.getValue();


                    if (
                            data == null ||
                                    data.length == 0
                    ) {

                        return;
                    }


                    String message =
                            new String(
                                    data,
                                    StandardCharsets.UTF_8
                            );


                    handleIncomingPacket(message);
                }


                // ========================================================
                // ANDROID 13+ NOTIFICATION API
                // ========================================================

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value
                ) {

                    super.onCharacteristicChanged(
                            gatt,
                            characteristic,
                            value
                    );


                    if (
                            !CHARACTERISTIC_UUID.equals(
                                    characteristic.getUuid()
                            )
                    ) {

                        return;
                    }


                    if (
                            value == null ||
                                    value.length == 0
                    ) {

                        return;
                    }


                    String message =
                            new String(
                                    value,
                                    StandardCharsets.UTF_8
                            );


                    handleIncomingPacket(message);
                }
            };


    // ============================================================
    // HANDLE INCOMING BLE PACKET
    // ============================================================

    private void handleIncomingPacket(
            String packet
    ) {

        if (packet == null) {
            return;
        }


        packet = packet.trim();


        if (packet.isEmpty()) {
            return;
        }


        final String incomingPacket =
                packet;


        runOnUiThread(
                () -> {

                    log("================================");

                    log(
                            "BLE RX <- " +
                                    selectedNodeId
                    );

                    log(incomingPacket);


                    /*
                     * Split only the first 3 commas.
                     *
                     * This preserves commas inside chat messages.
                     */
                    String[] parts =
                            incomingPacket.split(
                                    ",",
                                    4
                            );


                    if (parts.length == 0) {
                        return;
                    }


                    String type =
                            parts[0].trim();


                    switch (type) {

                        case "CHAT_REQUEST":

                            handleChatRequest(parts);

                            break;


                        case "CHAT_ACCEPTED":

                            handleChatAccepted(parts);

                            break;


                        case "CHAT_DECLINED":

                            handleChatDeclined(parts);

                            break;


                        case "CHAT":

                            handleIncomingChat(parts);

                            break;


                        default:

                            log(
                                    "Unknown BLE packet type: " +
                                            type
                            );

                            break;
                    }
                }
        );
    }


    // ============================================================
    // CHAT REQUEST
    // ============================================================

    private void handleChatRequest(
            String[] parts
    ) {

        /*
         * Expected:
         *
         * CHAT_REQUEST,
         * request_id,
         * sender_id,
         * receiver_id
         */

        if (parts.length < 4) {

            log(
                    "Invalid CHAT_REQUEST packet."
            );

            return;
        }


        String requestId =
                parts[1].trim();

        String senderId =
                parts[2].trim();

        String receiverId =
                parts[3].trim();


        log("CHAT REQUEST RECEIVED");
        log("Request ID: " + requestId);
        log("From: " + senderId);
        log("To: " + receiverId);


        if (!HAWKEYE_ID.equals(receiverId)) {

            log(
                    "Request is not for this tourist."
            );

            return;
        }


        // ========================================================
        // BROADCAST TO MESSAGES ACTIVITY
        // ========================================================

        Intent intent =
                new Intent(
                        ACTION_CHAT_REQUEST
                );

        intent.putExtra(
                EXTRA_REQUEST_ID,
                requestId
        );

        intent.putExtra(
                EXTRA_SENDER_ID,
                senderId
        );

        intent.putExtra(
                EXTRA_RECEIVER_ID,
                receiverId
        );

        sendBroadcast(intent);


        // ========================================================
        // TEMPORARY FALLBACK DIALOG
        // ========================================================

        new AlertDialog.Builder(this)

                .setTitle(
                        "New Chat Request"
                )

                .setMessage(
                        senderId +
                                " wants to chat with you."
                )

                .setPositiveButton(
                        "Accept",
                        (dialog, which) -> {

                            sendChatAccepted(
                                    requestId,
                                    senderId,
                                    receiverId
                            );
                        }
                )

                .setNegativeButton(
                        "Decline",
                        (dialog, which) -> {

                            sendChatDeclined(
                                    requestId,
                                    senderId,
                                    receiverId
                            );
                        }
                )

                .show();
    }


    // ============================================================
    // ACCEPT CHAT
    // ============================================================

    public void sendChatAccepted(
            String requestId,
            String senderId,
            String receiverId
    ) {

        if (
                requestId == null ||
                        senderId == null ||
                        receiverId == null
        ) {

            log(
                    "Invalid chat accept parameters."
            );

            return;
        }


        String packet =
                "CHAT_ACCEPTED," +
                        requestId +
                        "," +
                        senderId +
                        "," +
                        receiverId;


        log("================================");
        log("ACCEPTING CHAT REQUEST");
        log(packet);


        sendMessage(packet);
    }


    // ============================================================
    // DECLINE CHAT
    // ============================================================

    public void sendChatDeclined(
            String requestId,
            String senderId,
            String receiverId
    ) {

        if (
                requestId == null ||
                        senderId == null ||
                        receiverId == null
        ) {

            log(
                    "Invalid chat decline parameters."
            );

            return;
        }


        String packet =
                "CHAT_DECLINED," +
                        requestId +
                        "," +
                        senderId +
                        "," +
                        receiverId;


        log("================================");
        log("DECLINING CHAT REQUEST");
        log(packet);


        sendMessage(packet);
    }


    // ============================================================
    // CHAT ACCEPTED
    // ============================================================

    private void handleChatAccepted(
            String[] parts
    ) {

        /*
         * Expected:
         *
         * CHAT_ACCEPTED,
         * conversation_id,
         * sender_id,
         * receiver_id
         */

        if (parts.length < 4) {

            log(
                    "Invalid CHAT_ACCEPTED packet."
            );

            return;
        }


        String conversationId =
                parts[1].trim();

        String senderId =
                parts[2].trim();

        String receiverId =
                parts[3].trim();


        log("CHAT REQUEST ACCEPTED");

        log(
                "Conversation ID: " +
                        conversationId
        );

        log(
                "Participants: " +
                        senderId +
                        " <-> " +
                        receiverId
        );


        setStatus(
                "Status: Chat connected"
        );


        Intent intent =
                new Intent(
                        ACTION_CHAT_ACCEPTED
                );

        intent.putExtra(
                EXTRA_CONVERSATION_ID,
                conversationId
        );

        intent.putExtra(
                EXTRA_SENDER_ID,
                senderId
        );

        intent.putExtra(
                EXTRA_RECEIVER_ID,
                receiverId
        );

        sendBroadcast(intent);


        // ========================================================
        // TEMPORARY FALLBACK
        // ========================================================

        new AlertDialog.Builder(this)

                .setTitle(
                        "Chat Accepted"
                )

                .setMessage(
                        "Conversation " +
                                conversationId +
                                " is now active."
                )

                .setPositiveButton(
                        "OK",
                        null
                )

                .show();
    }


    // ============================================================
    // CHAT DECLINED
    // ============================================================

    private void handleChatDeclined(
            String[] parts
    ) {

        /*
         * Expected:
         *
         * CHAT_DECLINED,
         * request_id,
         * sender_id,
         * receiver_id
         */

        if (parts.length < 4) {

            log(
                    "Invalid CHAT_DECLINED packet."
            );

            return;
        }


        String requestId =
                parts[1].trim();

        String senderId =
                parts[2].trim();

        String receiverId =
                parts[3].trim();


        log("CHAT REQUEST DECLINED");

        log(
                "Request ID: " +
                        requestId
        );

        log(
                "From: " +
                        senderId
        );

        log(
                "To: " +
                        receiverId
        );


        setStatus(
                "Status: Chat request declined"
        );


        Intent intent =
                new Intent(
                        ACTION_CHAT_DECLINED
                );

        intent.putExtra(
                EXTRA_REQUEST_ID,
                requestId
        );

        intent.putExtra(
                EXTRA_SENDER_ID,
                senderId
        );

        intent.putExtra(
                EXTRA_RECEIVER_ID,
                receiverId
        );

        sendBroadcast(intent);


        // ========================================================
        // TEMPORARY FALLBACK
        // ========================================================

        new AlertDialog.Builder(this)

                .setTitle(
                        "Chat Request Declined"
                )

                .setMessage(
                        receiverId +
                                " declined your chat request."
                )

                .setPositiveButton(
                        "OK",
                        null
                )

                .show();
    }


    // ============================================================
    // INCOMING CHAT
    // ============================================================

    private void handleIncomingChat(
            String[] parts
    ) {

        /*
         * Expected:
         *
         * CHAT,
         * sender_id,
         * receiver_id,
         * message
         */

        if (parts.length < 4) {

            log(
                    "Invalid CHAT packet."
            );

            return;
        }


        String senderId =
                parts[1].trim();

        String receiverId =
                parts[2].trim();

        String message =
                parts[3];


        log("INCOMING CHAT");

        log(
                "From: " +
                        senderId
        );

        log(
                "To: " +
                        receiverId
        );

        log(
                "Message: " +
                        message
        );


        if (!HAWKEYE_ID.equals(receiverId)) {

            log(
                    "Chat message is not for this tourist."
            );

            return;
        }


        // ========================================================
        // BROADCAST
        // ========================================================

        Intent intent =
                new Intent(
                        ACTION_CHAT_MESSAGE
                );

        intent.putExtra(
                EXTRA_SENDER_ID,
                senderId
        );

        intent.putExtra(
                EXTRA_RECEIVER_ID,
                receiverId
        );

        intent.putExtra(
                EXTRA_MESSAGE,
                message
        );

        sendBroadcast(intent);


        // ========================================================
        // FALLBACK NOTIFICATION DIALOG
        // ========================================================

        new AlertDialog.Builder(this)

                .setTitle(
                        "Message from " +
                                senderId
                )

                .setMessage(
                        message
                )

                .setPositiveButton(
                        "OK",
                        null
                )

                .show();
    }


    // ============================================================
    // START LIVE GPS
    // ============================================================

    private void startLocationTracking() {

        if (!hasLocationPermission()) {

            log(
                    "Location permission not granted."
            );

            requestLocationPermission();

            return;
        }


        if (bluetoothGatt == null) {

            log(
                    "Connect to a Hawkeye node first."
            );

            return;
        }


        if (writeCharacteristic == null) {

            log(
                    "BLE communication is not ready."
            );

            return;
        }


        if (locationManager == null) {

            log(
                    "Location Manager unavailable."
            );

            return;
        }


        boolean gpsEnabled =
                locationManager.isProviderEnabled(
                        LocationManager.GPS_PROVIDER
                );


        if (!gpsEnabled) {

            log(
                    "GPS is turned OFF."
            );

            setStatus(
                    "Status: Turn GPS ON"
            );

            return;
        }


        // ========================================================
        // START
        // ========================================================

        locationTracking = true;


        if (locationButton != null) {

            locationButton.setText(
                    "STOP LIVE GPS"
            );
        }


        setStatus(
                "Status: Live GPS Active"
        );


        log("================================");
        log("STARTING LIVE GPS TRACKING");
        log("Update interval: 5 seconds");


        // ========================================================
        // GPS PROVIDER
        // ========================================================

        try {

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL,
                    0,
                    locationListener
            );

        } catch (SecurityException e) {

            log(
                    "GPS permission error."
            );

            return;

        } catch (Exception e) {

            log(
                    "GPS provider error: " +
                            e.getMessage()
            );
        }


        // ========================================================
        // NETWORK PROVIDER
        // ========================================================

        if (
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        ) {

            try {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_INTERVAL,
                        0,
                        locationListener
                );

            } catch (Exception ignored) {
            }
        }


        // ========================================================
        // LAST KNOWN LOCATION
        // ========================================================

        try {

            Location lastGpsLocation =
                    locationManager.getLastKnownLocation(
                            LocationManager.GPS_PROVIDER
                    );


            if (lastGpsLocation != null) {

                latestLocation =
                        lastGpsLocation;


                sendLiveLocation(
                        lastGpsLocation.getLatitude(),
                        lastGpsLocation.getLongitude()
                );
            }

        } catch (Exception ignored) {
        }
    }


    // ============================================================
    // STOP GPS
    // ============================================================

    private void stopLocationTracking() {

        if (locationManager != null) {

            try {

                locationManager.removeUpdates(
                        locationListener
                );

            } catch (Exception ignored) {
            }
        }


        locationTracking = false;


        if (locationButton != null) {

            locationButton.setText(
                    "START LIVE GPS"
            );
        }


        if (statusText != null) {

            statusText.setText(
                    "Status: BLE Ready"
            );
        }


        log(
                "LIVE GPS TRACKING STOPPED"
        );
    }


    // ============================================================
    // LOCATION LISTENER
    // ============================================================

    private final LocationListener locationListener =
            new LocationListener() {

                @Override
                public void onLocationChanged(
                        Location location
                ) {

                    if (location == null) {
                        return;
                    }


                    latestLocation =
                            location;


                    double latitude =
                            location.getLatitude();

                    double longitude =
                            location.getLongitude();

                    float accuracy =
                            location.getAccuracy();


                    log("================================");
                    log("GPS UPDATE");

                    log(
                            "Latitude: " +
                                    latitude
                    );

                    log(
                            "Longitude: " +
                                    longitude
                    );

                    log(
                            "Accuracy: " +
                                    accuracy +
                                    " m"
                    );


                    // ====================================================
                    // SEND THROUGH BLE
                    // ====================================================

                    sendLiveLocation(
                            latitude,
                            longitude
                    );
                }


                @Override
                public void onProviderEnabled(
                        String provider
                ) {

                    log(
                            "GPS provider enabled: " +
                                    provider
                    );
                }


                @Override
                public void onProviderDisabled(
                        String provider
                ) {

                    log(
                            "GPS provider disabled: " +
                                    provider
                    );
                }
            };


    // ============================================================
    // SEND LIVE LOCATION
    // ============================================================

    private void sendLiveLocation(
            double latitude,
            double longitude
    ) {

        if (selectedNodeId == null) {

            log(
                    "No Hawkeye node selected."
            );

            return;
        }


        if (bluetoothGatt == null) {

            log(
                    "BLE connection not ready."
            );

            return;
        }


        if (writeCharacteristic == null) {

            log(
                    "BLE write characteristic unavailable."
            );

            return;
        }


        String message =
                String.format(
                        Locale.US,
                        "LOCATION,%s,%.6f,%.6f",
                        HAWKEYE_ID,
                        latitude,
                        longitude
                );


        sendMessage(message);
    }


    // ============================================================
    // SEND SOS
    // ============================================================

    private void sendSOS() {

        if (latestLocation == null) {

            log(
                    "No GPS location available yet."
            );

            log(
                    "Start LIVE GPS first."
            );

            return;
        }


        double latitude =
                latestLocation.getLatitude();

        double longitude =
                latestLocation.getLongitude();


        String message =
                String.format(
                        Locale.US,
                        "SOS,%s,%.6f,%.6f",
                        HAWKEYE_ID,
                        latitude,
                        longitude
                );


        log("================================");
        log("🚨 SOS ACTIVATED");

        log(
                "Latitude: " +
                        latitude
        );

        log(
                "Longitude: " +
                        longitude
        );


        sendMessage(message);
    }


    // ============================================================
    // SEND CHAT MESSAGE
    // ============================================================

    /*
     * Packet:
     *
     * CHAT,<sender_id>,<receiver_id>,<message>
     *
     * The ESP forwards this packet to FastAPI.
     */

    public void sendChatMessage(
            String receiverId,
            String message
    ) {

        if (
                receiverId == null ||
                        receiverId.trim().isEmpty()
        ) {

            log(
                    "Chat receiver ID is missing."
            );

            return;
        }


        if (
                message == null ||
                        message.trim().isEmpty()
        ) {

            log(
                    "Chat message is empty."
            );

            return;
        }


        String cleanReceiverId =
                receiverId.trim();


        String cleanMessage =
                message
                        .replace("\n", " ")
                        .replace("\r", " ");


        String packet =
                "CHAT," +
                        HAWKEYE_ID +
                        "," +
                        cleanReceiverId +
                        "," +
                        cleanMessage;


        log("================================");
        log("CHAT MESSAGE");

        log(
                "From: " +
                        HAWKEYE_ID
        );

        log(
                "To: " +
                        cleanReceiverId
        );

        log(
                "Message: " +
                        cleanMessage
        );


        sendMessage(packet);
    }


    // ============================================================
    // PUBLIC CHAT REQUEST
    // ============================================================

    /*
     * This is useful for MessagesActivity.
     *
     * Packet:
     *
     * CHAT_REQUEST,<sender_id>,<receiver_id>
     *
     * NOTE:
     * Your FastAPI server currently uses the HTTP endpoint
     * /chat/request as the authoritative request creator.
     *
     * Therefore this method is kept for BLE protocol compatibility,
     * but MessagesActivity should eventually use the server API
     * for creating requests.
     */

    public void sendChatRequest(
            String receiverId
    ) {

        if (
                receiverId == null ||
                        receiverId.trim().isEmpty()
        ) {

            log(
                    "Receiver ID missing."
            );

            return;
        }


        String packet =
                "CHAT_REQUEST," +
                        HAWKEYE_ID +
                        "," +
                        receiverId.trim();


        log("================================");
        log("CHAT REQUEST");
        log(packet);


        sendMessage(packet);
    }


    // ============================================================
    // LOW LEVEL BLE WRITE
    // ============================================================

    public void sendMessage(
            String message
    ) {

        if (
                message == null ||
                        message.trim().isEmpty()
        ) {

            log(
                    "Cannot send empty BLE packet."
            );

            return;
        }


        if (selectedNodeId == null) {

            log(
                    "No Hawkeye node selected."
            );

            return;
        }


        if (bluetoothGatt == null) {

            log(
                    "BLE connection not ready."
            );

            return;
        }


        if (writeCharacteristic == null) {

            log(
                    "Write characteristic unavailable."
            );

            return;
        }


        if (gattWriteInProgress) {

            log(
                    "Previous BLE write still in progress."
            );

            return;
        }


        if (!hasBluetoothPermission()) {

            requestBluetoothPermissions();

            return;
        }


        byte[] data =
                message.getBytes(
                        StandardCharsets.UTF_8
                );


        log("================================");

        log(
                "TX -> " +
                        selectedNodeId
        );

        log(
                "Zone -> " +
                        selectedNodeZone
        );

        log(message);

        log(
                "Packet size: " +
                        data.length +
                        " bytes"
        );


        // ========================================================
        // SAFETY LIMIT
        // ========================================================

        /*
         * Current MTU is 247.
         *
         * Keeping the prototype packet limit at 200 bytes gives
         * some room for BLE overhead.
         */

        if (data.length > 200) {

            log(
                    "Message too large. Maximum 200 bytes."
            );

            return;
        }


        // ========================================================
        // WRITE
        // ========================================================

        writeCharacteristic.setWriteType(
                BluetoothGattCharacteristic
                        .WRITE_TYPE_DEFAULT
        );


        writeCharacteristic.setValue(
                data
        );


        gattWriteInProgress = true;


        boolean success =
                bluetoothGatt.writeCharacteristic(
                        writeCharacteristic
                );


        if (success) {

            log(
                    "BLE write initiated."
            );

        } else {

            gattWriteInProgress = false;


            log(
                    "BLE write FAILED immediately."
            );
        }
    }


    // ============================================================
    // BLE STATUS BROADCAST
    // ============================================================

    private void broadcastBleStatus(
            String status
    ) {

        Intent intent =
                new Intent(
                        ACTION_BLE_STATUS
                );


        intent.putExtra(
                "status",
                status
        );


        intent.putExtra(
                "node_id",
                selectedNodeId
        );


        intent.putExtra(
                "zone",
                selectedNodeZone
        );


        sendBroadcast(intent);
    }


    // ============================================================
    // STATUS
    // ============================================================

    private void setStatus(
            String status
    ) {

        runOnUiThread(
                () -> {

                    if (statusText != null) {

                        statusText.setText(
                                status
                        );
                    }
                }
        );
    }


    // ============================================================
    // LOG
    // ============================================================

    private void log(
            String message
    ) {

        runOnUiThread(
                () -> {

                    if (logText == null) {
                        return;
                    }


                    String current =
                            logText
                                    .getText()
                                    .toString();


                    /*
                     * Keep the UI log from becoming enormous.
                     */
                    if (current.length() > 12000) {

                        current =
                                current.substring(
                                        Math.max(
                                                0,
                                                current.length() - 8000
                                        )
                                );
                    }


                    logText.setText(
                            current +
                                    "\n" +
                                    message
                    );
                }
        );
    }


    // ============================================================
    // ACTIVITY RESULT / PERMISSIONS
    // ============================================================

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );


        if (requestCode == REQUEST_BLUETOOTH) {

            if (hasBluetoothPermission()) {

                log(
                        "Bluetooth permissions granted."
                );

            } else {

                log(
                        "Bluetooth permissions denied."
                );
            }
        }


        if (requestCode == REQUEST_LOCATION) {

            if (hasLocationPermission()) {

                log(
                        "Location permissions granted."
                );

            } else {

                log(
                        "Location permissions denied."
                );
            }
        }
    }


    // ============================================================
    // RESUME
    // ============================================================

    @Override
    protected void onResume() {

        super.onResume();


        if (
                bluetoothAdapter != null &&
                        hasBluetoothPermission()
        ) {

            if (bluetoothAdapter.isEnabled()) {

                log(
                        "Bluetooth is ON."
                );
            }
        }
    }


    // ============================================================
    // PAUSE
    // ============================================================

    @Override
    protected void onPause() {

        super.onPause();

        /*
         * We intentionally keep BLE/GPS alive while moving
         * between Activities during the prototype.
         */
    }


    // ============================================================
    // DESTROY
    // ============================================================

    @Override
    protected void onDestroy() {

        // ========================================================
        // STOP GPS
        // ========================================================

        stopLocationTracking();


        // ========================================================
        // STOP SCAN
        // ========================================================

        if (scanning) {

            stopScan();
        }


        // ========================================================
        // CANCEL PENDING CALLBACKS
        // ========================================================

        handler.removeCallbacksAndMessages(null);


        // ========================================================
        // CLOSE GATT
        // ========================================================

        if (bluetoothGatt != null) {

            if (hasBluetoothPermission()) {

                try {

                    bluetoothGatt.disconnect();

                } catch (Exception ignored) {
                }


                try {

                    bluetoothGatt.close();

                } catch (Exception ignored) {
                }
            }


            bluetoothGatt = null;
        }


        // ========================================================
        // RESET STATE
        // ========================================================

        notifyCharacteristic = null;

        writeCharacteristic = null;

        hawkeyeDevice = null;

        gattWriteInProgress = false;

        scanning = false;


        super.onDestroy();
    }
    // ============================================================
    // ACTIVITY BRIDGE METHODS
    // ============================================================
    //
    // These methods allow MessagesActivity and ChatActivity
    // to use the BLE connection owned by MainActivity.
    //
    // IMPORTANT:
    // MainActivity remains the single BLE controller.
    // We do NOT create another BLE connection in the chat screens.
    // ============================================================


    /**
     * Called by ChatActivity when the user sends a message.
     */
    public static void sendChatFromActivity(
            ChatActivity activity,
            String receiverId,
            String message
    ) {

        if (activity == null) {
            return;
        }


        /*
         * ChatActivity extends Activity.
         *
         * Get the currently running MainActivity instance
         * through the application activity reference.
         */
        MainActivity mainActivity =
                getMainActivity(activity);


        if (mainActivity == null) {

            android.widget.Toast.makeText(
                    activity,
                    "Hawkeye connection is not available.",
                    android.widget.Toast.LENGTH_SHORT
            ).show();

            return;
        }


        mainActivity.sendChatMessage(
                receiverId,
                message
        );
    }


    /**
     * Called by MessagesActivity when the tourist wants
     * to send a chat request.
     */

    /**
     * Called by MessagesActivity when a request is accepted.
     */


    /**
     * Called by MessagesActivity when a request is declined.



    /**
     * Find the currently running MainActivity.
     *
     * This prototype keeps MainActivity alive while the user
     * moves between the messaging screens.
     */
    private static MainActivity getMainActivity(
            Activity activity
    ) {

        if (activity instanceof MainActivity) {

            return (MainActivity) activity;
        }


        /*
         * The chat Activities are separate Activities, so
         * we cannot directly cast them to MainActivity.
         *
         * For the current prototype, use the shared static
         * reference maintained by MainActivity.
         */
        return activeMainActivity;
    }


    // ============================================================
    // ACTIVE MAIN ACTIVITY
    // ============================================================

    private static MainActivity activeMainActivity;



    @Override
    protected void onStop() {

        if (activeMainActivity == this) {

            /*
             * Do not immediately destroy the reference here.
             *
             * MessagesActivity / ChatActivity may be opened
             * while MainActivity is temporarily stopped.
             *
             * BLE remains owned by MainActivity.
             */
        }


        super.onStop();
    }
    // ============================================================
// ACTIVITY BRIDGE: SEND CHAT
// ============================================================



// ============================================================
// ACTIVITY BRIDGE: CHAT REQUEST
// ============================================================

    public static void sendChatRequestFromActivity(
            MessagesActivity activity,
            String receiverId
    ) {

        if (activeMainActivity == null) {

            Toast.makeText(
                    activity,
                    "Hawkeye connection is not available.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        activeMainActivity.sendChatRequest(
                receiverId
        );
    }


// ============================================================
// ACTIVITY BRIDGE: ACCEPT
// ============================================================

    public static void acceptChatRequestFromActivity(
            MessagesActivity activity,
            String requestId,
            String senderId,
            String receiverId
    ) {

        if (activeMainActivity == null) {

            Toast.makeText(
                    activity,
                    "Hawkeye connection is not available.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        activeMainActivity.sendChatAccepted(
                requestId,
                senderId,
                receiverId
        );
    }


// ============================================================
// ACTIVITY BRIDGE: DECLINE
// ============================================================

    public static void declineChatRequestFromActivity(
            MessagesActivity activity,
            String requestId,
            String senderId,
            String receiverId
    ) {

        if (activeMainActivity == null) {

            Toast.makeText(
                    activity,
                    "Hawkeye connection is not available.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        activeMainActivity.sendChatDeclined(
                requestId,
                senderId,
                receiverId
        );
    }
    private String getOrCreateHawkeyeId() {

        SharedPreferences prefs =
                getSharedPreferences("hawkeye_prefs", MODE_PRIVATE);

        String savedId =
                prefs.getString("hawkeye_id", null);

        if (savedId != null && !savedId.isEmpty()) {
            return savedId;
        }

        String androidId =
                android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID
                );

        String newId;

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            androidId.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder hex =
                    new StringBuilder();

            for (int i = 0; i < 4; i++) {
                hex.append(
                        String.format(
                                Locale.US,
                                "%02X",
                                hash[i]
                        )
                );
            }

            newId = "HWK-U" + hex;

        } catch (NoSuchAlgorithmException e) {

            newId =
                    "HWK-U" +
                            UUID.randomUUID()
                                    .toString()
                                    .substring(0, 8)
                                    .toUpperCase(Locale.US);
        }

        prefs.edit()
                .putString("hawkeye_id", newId)
                .apply();

        return newId;
    }
}