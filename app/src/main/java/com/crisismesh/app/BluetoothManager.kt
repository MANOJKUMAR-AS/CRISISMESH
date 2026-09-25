package com.crisismesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * CrisisMesh peer communication layer.
 *
 * Every phone is BOTH:
 *  1. GATT SERVER -> can receive SOS from another phone.
 *  2. GATT CLIENT -> can scan/connect and send SOS to another phone.
 *
 * We intentionally use a fresh outbound connection for sending instead
 * of depending on the direction of an existing GATT connection. This
 * keeps every phone symmetric:
 *
 *     A <-> B <-> C <-> D
 */
class BluetoothManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatusChanged(status: String)
        fun onDeviceFound(deviceName: String)
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onMessageReceived(packet: MeshPacket)
        fun onError(message: String)
    }

    companion object {

        val SERVICE_UUID: UUID =
            UUID.fromString(
                "12345678-1234-5678-1234-56789abcdef0"
            )

        val SOS_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(
                "12345678-1234-5678-1234-56789abcdef1"
            )

        private const val PACKET_SIZE = 20
        private const val HEADER_SIZE = 4
        private const val PAYLOAD_SIZE =
            PACKET_SIZE - HEADER_SIZE

        private const val MAGIC: Byte = 0x43

        private const val RELAY_DELAY_MS = 600L
        private const val RETRY_SCAN_DELAY_MS = 1200L
        private const val MESSAGE_ID_MEMORY_LIMIT = 500
    }

    private val androidBluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as AndroidBluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = androidBluetoothManager.adapter

    private val handler =
        Handler(Looper.getMainLooper())

    // =========================================================
    // ADAPTIVE RANGE DISCOVERY (PHY ESCALATION)
    // =========================================================

    private enum class RangePhyState {
        PHY_1M,
        PHY_CODED_S2,
        PHY_CODED_S8
    }

    private var currentRangeState = RangePhyState.PHY_1M
    private var scanTimeoutRunnable: Runnable? = null

    private fun isCodedPhySupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                bluetoothAdapter?.isLeCodedPhySupported == true
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
    }

    private fun escalateRangeState() {
        if (!isCodedPhySupported()) {
            Log.d("CrisisMesh-Range", "[Range] Coded PHY not supported by this device")
            currentRangeState = RangePhyState.PHY_1M
            handler.postDelayed({
                if (pendingMeshPacket != null) {
                    startRelayScan()
                }
            }, RETRY_SCAN_DELAY_MS)
            return
        }

        when (currentRangeState) {
            RangePhyState.PHY_1M -> {
                currentRangeState = RangePhyState.PHY_CODED_S2
                Log.d("CrisisMesh-Range", "[Range] Escalating to Coded S=2")
            }
            RangePhyState.PHY_CODED_S2 -> {
                currentRangeState = RangePhyState.PHY_CODED_S8
                Log.d("CrisisMesh-Range", "[Range] Escalating to Coded S=8")
            }
            RangePhyState.PHY_CODED_S8 -> {
                currentRangeState = RangePhyState.PHY_1M
                Log.d("CrisisMesh-Range", "[Range] Maximum range reached (S=8), retrying from 1M")
            }
        }

        if (pendingMeshPacket != null) {
            startRelayScan()
        }
    }

    // =========================================================
    // GATT SERVER - RECEIVING SIDE
    // =========================================================

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // =========================================================
    // GATT CLIENT - SENDING SIDE
    // =========================================================

    private var bluetoothGatt: BluetoothGatt? = null
    private var remoteSosCharacteristic:
            BluetoothGattCharacteristic? = null
    private var clientReady = false

    // =========================================================
    // SCANNING
    // =========================================================

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    // Do not immediately send a received SOS back to the device
    // that just gave it to us.
    private var relayExcludeAddress: String? = null

    // Do not send the SOS back to the original sender if we discover it.
    private var pendingOriginDeviceId: String? = null

    // =========================================================
    // PENDING SEND / RELAY
    // =========================================================

    private var pendingMeshPacket: String? = null

    // =========================================================
    // BLE CHUNKING
    // =========================================================

    private var outgoingChunks: List<ByteArray> = emptyList()
    private var outgoingIndex = 0
    private var messageCounter = 0

    private var incomingMessage: IncomingMessage? = null

    private data class IncomingMessage(
        val wireMessageId: Int,
        val totalPackets: Int,
        val packets: MutableMap<Int, ByteArray>
    )

    // =========================================================
    // MESH DEDUPLICATION
    // =========================================================

    private val receivedMeshMessageIds =
        LinkedHashSet<String>()

    // =========================================================
    // PERMISSIONS
    // =========================================================

    private fun hasBluetoothPermissions(): Boolean {

        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADVERTISE
                    ) == PackageManager.PERMISSION_GRANTED &&

                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

        } else {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // =========================================================
    // START
    // =========================================================

    @SuppressLint("MissingPermission")
    fun start() {

        if (!hasBluetoothPermissions()) {
            listener.onError(
                "Bluetooth permissions not granted"
            )
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null) {
            listener.onError(
                "Bluetooth not available"
            )
            return
        }

        if (!adapter.isEnabled) {
            listener.onError(
                "Please turn Bluetooth ON"
            )
            return
        }

        listener.onStatusChanged(
            "CrisisMesh node active — SEND + RECEIVE enabled"
        )

        startGattServer()
    }

    // =========================================================
    // GATT SERVER
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun startGattServer() {

        gattServer?.close()

        gattServer =
            androidBluetoothManager.openGattServer(
                context,
                gattServerCallback
            )

        if (gattServer == null) {
            listener.onError(
                "Could not start GATT server"
            )
            return
        }

        val service =
            BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

        /*
         * WRITE is enough for the receiving side:
         * another phone connects as GATT client and writes
         * the SOS packets to this characteristic.
         *
         * Every phone also runs its own GATT client, so a
         * different phone can initiate an outbound connection
         * whenever it needs to send.
         */
        val characteristic =
            BluetoothGattCharacteristic(
                SOS_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

        service.addCharacteristic(characteristic)

        val success =
            gattServer?.addService(service) ?: false

        if (!success) {
            listener.onError(
                "Could not add CrisisMesh service"
            )
        }
    }

    // =========================================================
    // GATT SERVER CALLBACK
    // =========================================================

    @SuppressLint("MissingPermission")
    private val gattServerCallback =
        object : BluetoothGattServerCallback() {

            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService
            ) {

                if (
                    status == BluetoothGatt.GATT_SUCCESS
                ) {

                    listener.onStatusChanged(
                        "GATT server ready"
                    )

                    startAdvertising()

                } else {

                    listener.onError(
                        "GATT service failed: $status"
                    )
                }
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {

                if (
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    listener.onStatusChanged(
                        "Peer connected: ${safeDeviceName(device)}"
                    )

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    listener.onStatusChanged(
                        "Peer disconnected"
                    )
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {

                if (
                    characteristic.uuid !=
                    SOS_CHARACTERISTIC_UUID
                ) {

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                            offset,
                            null
                        )
                    }
                    return
                }

                if (responseNeeded) {

                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }

                processIncomingPacket(
                    packet = value,
                    sourceAddress = device.address
                )
            }
        }

    // =========================================================
    // RECEIVE / REASSEMBLE BLE CHUNKS
    // =========================================================

    private fun processIncomingPacket(
        packet: ByteArray,
        sourceAddress: String
    ) {

        if (packet.size < HEADER_SIZE) {
            return
        }

        if (packet[0] != MAGIC) {
            return
        }

        val wireMessageId =
            packet[1].toInt() and 0xFF

        val sequence =
            packet[2].toInt() and 0xFF

        val totalPackets =
            packet[3].toInt() and 0xFF

        if (
            totalPackets <= 0 ||
            sequence >= totalPackets
        ) {
            return
        }

        /*
         * For this prototype one active incoming transfer is enough.
         * A later production version can keep separate buffers per
         * peer/message pair.
         */
        if (
            incomingMessage == null ||
            incomingMessage!!.wireMessageId != wireMessageId
        ) {

            incomingMessage =
                IncomingMessage(
                    wireMessageId = wireMessageId,
                    totalPackets = totalPackets,
                    packets = mutableMapOf()
                )
        }

        val current =
            incomingMessage ?: return

        current.packets[sequence] =
            packet.copyOfRange(
                HEADER_SIZE,
                packet.size
            )

        listener.onStatusChanged(
            "Receiving SOS ${current.packets.size}/$totalPackets"
        )

        if (
            current.packets.size !=
            current.totalPackets
        ) {
            return
        }

        val output =
            ByteArrayOutputStream()

        for (
        index in 0 until current.totalPackets
        ) {

            val chunk =
                current.packets[index]
                    ?: return

            output.write(chunk)
        }

        incomingMessage = null

        val completeMessage =
            output.toByteArray()
                .toString(Charsets.UTF_8)

        handleCompleteMeshMessage(
            rawMessage = completeMessage,
            sourceAddress = sourceAddress
        )
    }

    // =========================================================
    // HANDLE COMPLETE MESH MESSAGE
    // =========================================================

    private fun handleCompleteMeshMessage(
        rawMessage: String,
        sourceAddress: String
    ) {

        val meshPacket =
            MeshPacket.parse(rawMessage)

        /*
         * Legacy messages are still displayed so the old
         * A -> B prototype remains compatible.
         */
        if (meshPacket == null) {

            listener.onMessageReceived(
                MeshPacket.create(rawMessage)
            )

            return
        }

        val messageId =
            meshPacket.messageId

        synchronized(receivedMeshMessageIds) {

            if (
                receivedMeshMessageIds.contains(
                    messageId
                )
            ) {

                listener.onStatusChanged(
                    "Duplicate SOS ignored: $messageId"
                )

                Log.d("CRISIS_MESH_SOUND", "Duplicate message, buzzer suppressed: $messageId")

                return
            }

            receivedMeshMessageIds.add(
                messageId
            )

            Log.d("CRISIS_MESH_SOUND", "Playing emergency alert for messageId=$messageId")
            EmergencyAlertSound.play()

            while (
                receivedMeshMessageIds.size >
                MESSAGE_ID_MEMORY_LIMIT
            ) {
                val first =
                    receivedMeshMessageIds.firstOrNull()

                if (first != null) {
                    receivedMeshMessageIds.remove(first)
                } else {
                    break
                }
            }
        }

        listener.onStatusChanged(
            "SOS $messageId received"
        )

        /*
         * Always show the emergency information on this device.
         */
        listener.onMessageReceived(
            meshPacket
        )

        /*
         * TTL decides whether this node may forward the SOS.
         */
        if (!meshPacket.canRelay()) {

            listener.onStatusChanged(
                "SOS $messageId stopped — TTL exhausted"
            )

            return
        }

        val relayPacket =
            meshPacket.createRelayPacket()

        if (!relayPacket.canRelay()) {

            listener.onStatusChanged(
                "SOS $messageId stopped — no relay hops remaining"
            )

            return
        }

        pendingMeshPacket =
            relayPacket.serialize()

        pendingOriginDeviceId =
            meshPacket.originDeviceId

        /*
         * Don't immediately send the message back to the
         * phone that just delivered it to us.
         */
        relayExcludeAddress =
            sourceAddress

        listener.onStatusChanged(
            "SOS $messageId queued for automatic relay"
        )

        handler.postDelayed(
            {
                attemptPendingRelay()
            },
            RELAY_DELAY_MS
        )
    }

    // =========================================================
    // ADVERTISING
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {

        if (!hasBluetoothPermissions()) {
            return
        }

        val adapter = bluetoothAdapter

        if (adapter == null) {
            listener.onError(
                "Bluetooth adapter unavailable"
            )
            return
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            listener.onError(
                "BLE advertising not supported"
            )
            return
        }

        advertiser =
            adapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            listener.onError(
                "BLE advertiser unavailable"
            )
            return
        }

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                )
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                )
                .setConnectable(true)
                .build()

        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(
                    ParcelUuid(SERVICE_UUID)
                )
                .build()

        val scanResponse =
            AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

        try {

            advertiser?.startAdvertising(
                settings,
                data,
                scanResponse,
                advertiseCallback
            )

        } catch (
            error: Exception
        ) {

            listener.onError(
                "Advertising exception: ${error.message}"
            )
        }
    }

    private val advertiseCallback =
        object : AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect: AdvertiseSettings?
            ) {

                listener.onStatusChanged(
                    "CrisisMesh advertising"
                )
            }

            override fun onStartFailure(
                errorCode: Int
            ) {

                listener.onError(
                    "Advertising failed: $errorCode"
                )
            }
        }

    // =========================================================
    // MANUAL SCAN
    // =========================================================

    @SuppressLint("MissingPermission")
    fun startScan() {

        /*
         * Manual scan is allowed to choose any peer.
         */
        relayExcludeAddress = null

        startScanInternal(
            "Scanning for CrisisMesh peers..."
        )
    }

    // =========================================================
    // AUTOMATIC RELAY / QUEUED SEND
    // =========================================================

    private fun attemptPendingRelay() {

        val packet =
            pendingMeshPacket

        if (packet == null) {
            return
        }

        /*
         * A pending packet may be sent immediately if there is
         * already a ready outgoing connection and it is not the
         * source peer.
         */
        val currentClientAddress =
            bluetoothGatt?.device?.address

        if (
            clientReady &&
            bluetoothGatt != null &&
            remoteSosCharacteristic != null &&
            currentClientAddress != null &&
            currentClientAddress != relayExcludeAddress
        ) {

            pendingMeshPacket = null

            listener.onStatusChanged(
                "Relaying SOS through connected peer..."
            )

            sendMeshPacket(packet)

            return
        }

        /*
         * Otherwise discover a new peer automatically.
         */
        startRelayScan()
    }

    @SuppressLint("MissingPermission")
    private fun startRelayScan() {

        if (pendingMeshPacket == null) {
            return
        }

        Log.d("CrisisMesh-Range", "[Range] Starting SOS peer discovery")
        Log.d("CrisisMesh-Range", "[Range] Current PHY: $currentRangeState")

        listener.onStatusChanged(
            "Searching for next CrisisMesh peer ($currentRangeState)..."
        )

        startScanInternal(
            "Searching for next relay peer..."
        )
    }

    // =========================================================
    // INTERNAL SCANNER
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun startScanInternal(
        statusMessage: String
    ) {

        if (!hasBluetoothPermissions()) {
            listener.onError(
                "Bluetooth permissions not granted"
            )
            return
        }

        val adapter = bluetoothAdapter

        if (
            adapter == null ||
            !adapter.isEnabled
        ) {
            listener.onError(
                "Bluetooth is OFF"
            )
            return
        }

        if (scanning) {
            return
        }

        /*
         * Don't start a new scan if we are already in the process
         * of connecting to a peer.
         */
        if (bluetoothGatt != null && !clientReady) {
            return
        }

        scanner =
            adapter.bluetoothLeScanner

        if (scanner == null) {
            listener.onError(
                "BLE scanner unavailable"
            )
            return
        }

        listener.onStatusChanged(
            statusMessage
        )

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid(SERVICE_UUID)
                )
                .build()

        val settingsBuilder =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )

        if (isCodedPhySupported()) {
            when (currentRangeState) {
                RangePhyState.PHY_1M -> {
                    Log.d("CrisisMesh-Range", "[Range] Current PHY: 1M")
                }
                RangePhyState.PHY_CODED_S2 -> {
                    Log.d("CrisisMesh-Range", "[Range] Current PHY: Coded S=2")
                    try {
                        settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    } catch (_: Exception) {}
                }
                RangePhyState.PHY_CODED_S8 -> {
                    Log.d("CrisisMesh-Range", "[Range] Current PHY: Coded S=8")
                    try {
                        settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    } catch (_: Exception) {}
                }
            }
        } else if (!isCodedPhySupported() && currentRangeState != RangePhyState.PHY_1M) {
            Log.d("CrisisMesh-Range", "[Range] Coded PHY not supported by this device")
            currentRangeState = RangePhyState.PHY_1M
        }

        val settings = settingsBuilder.build()

        scanning = true

        scanTimeoutRunnable = Runnable {
            if (scanning) {
                Log.d("CrisisMesh-Range", "[Range] No suitable peer found on $currentRangeState")
                stopScan()
                escalateRangeState()
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, 3500L)

        try {

            scanner?.startScan(
                listOf(filter),
                settings,
                scanCallback
            )

        } catch (
            error: Exception
        ) {

            scanning = false

            listener.onError(
                "Scan exception: ${error.message}"
            )
        }
    }

    // =========================================================
    // SCAN CALLBACK
    // =========================================================

    @SuppressLint("MissingPermission")
    private val scanCallback =
        object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                val device =
                    result.device

                /*
                 * For automatic relay, don't immediately return
                 * the SOS to the peer that just sent it.
                 */
                if (
                    relayExcludeAddress != null &&
                    device.address == relayExcludeAddress
                ) {
                    return
                }

                val name =
                    safeDeviceName(device)

                /*
                 * Prevent relaying back to the original sender.
                 */
                if (
                    pendingOriginDeviceId != null &&
                    name == pendingOriginDeviceId
                ) {
                    return
                }

                listener.onDeviceFound(
                    name
                )

                Log.d("CrisisMesh-Range", "[Range] Peer discovered: $name")

                stopScan()

                currentRangeState = RangePhyState.PHY_1M

                connectToDevice(device)
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                scanning = false

                listener.onError(
                    "Scan failed: $errorCode"
                )

                if (pendingMeshPacket != null) {

                    handler.postDelayed(
                        {
                            attemptPendingRelay()
                        },
                        RETRY_SCAN_DELAY_MS
                    )
                }
            }
        }

    // =========================================================
    // STOP SCAN
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null

        if (!scanning) {
            return
        }

        try {
            scanner?.stopScan(
                scanCallback
            )
        } catch (_: Exception) {
        }

        scanning = false
    }

    // =========================================================
    // CONNECT OUTBOUND
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun connectToDevice(
        device: BluetoothDevice
    ) {

        if (
            relayExcludeAddress != null &&
            device.address == relayExcludeAddress
        ) {
            return
        }

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()

        bluetoothGatt = null
        remoteSosCharacteristic = null
        clientReady = false

        listener.onStatusChanged(
            "Connecting to ${safeDeviceName(device)}..."
        )

        try {

            bluetoothGatt =
                device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )

        } catch (
            error: Exception
        ) {

            listener.onError(
                "Connection exception: ${error.message}"
            )

            if (pendingMeshPacket != null) {
                handler.postDelayed(
                    {
                        attemptPendingRelay()
                    },
                    RETRY_SCAN_DELAY_MS
                )
            }
        }
    }

    // =========================================================
    // GATT CLIENT CALLBACK
    // =========================================================

    @SuppressLint("MissingPermission")
    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {

                    if (isCodedPhySupported()) {
                        val phyOption = when (currentRangeState) {
                            RangePhyState.PHY_CODED_S2 -> BluetoothDevice.PHY_OPTION_S2
                            RangePhyState.PHY_CODED_S8 -> BluetoothDevice.PHY_OPTION_S8
                            else -> BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        }
                        if (phyOption != BluetoothDevice.PHY_OPTION_NO_PREFERRED) {
                            try {
                                gatt.setPreferredPhy(BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK, phyOption)
                            } catch (_: Exception) {}
                        }
                    }

                    listener.onStatusChanged(
                        "Connected — discovering CrisisMesh service..."
                    )

                    try {

                        gatt.requestMtu(517)

                    } catch (_: Exception) {
                    }

                    gatt.discoverServices()

                } else if (
                    newState == BluetoothProfile.STATE_DISCONNECTED
                ) {

                    clientReady = false
                    remoteSosCharacteristic = null

                    listener.onDisconnected()

                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }

                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }

                    if (pendingMeshPacket != null) {

                        listener.onStatusChanged(
                            "Peer connection lost — retrying relay"
                        )

                        handler.postDelayed(
                            {
                                attemptPendingRelay()
                            },
                            RETRY_SCAN_DELAY_MS
                        )
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status != BluetoothGatt.GATT_SUCCESS
                ) {

                    listener.onError(
                        "Service discovery failed: $status"
                    )

                    return
                }

                val service =
                    gatt.getService(
                        SERVICE_UUID
                    )

                if (service == null) {

                    listener.onError(
                        "CrisisMesh service not found"
                    )

                    return
                }

                val characteristic =
                    service.getCharacteristic(
                        SOS_CHARACTERISTIC_UUID
                    )

                if (characteristic == null) {

                    listener.onError(
                        "SOS characteristic not found"
                    )

                    return
                }

                remoteSosCharacteristic =
                    characteristic

                clientReady = true

                listener.onStatusChanged(
                    "CrisisMesh connection ready"
                )

                listener.onConnected(
                    "CrisisMesh peer"
                )

                /*
                 * If a message was waiting for this connection,
                 * send it immediately.
                 */
                val pending =
                    pendingMeshPacket

                if (pending != null) {

                    pendingMeshPacket = null

                    listener.onStatusChanged(
                        "Sending queued SOS to peer..."
                    )

                    sendMeshPacket(
                        pending
                    )
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                if (
                    status == BluetoothGatt.GATT_SUCCESS
                ) {

                    listener.onStatusChanged(
                        "BLE MTU negotiated: $mtu"
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                if (!clientReady) {
                    return
                }

                if (
                    status != BluetoothGatt.GATT_SUCCESS
                ) {

                    clearOutgoingState()

                    listener.onError(
                        "SOS packet failed: $status"
                    )

                    return
                }

                outgoingIndex++

                if (
                    outgoingIndex <
                    outgoingChunks.size
                ) {

                    writeNextChunk()

                } else {

                    clearOutgoingState()

                    Log.d("CrisisMesh-Range", "[Range] SOS transmission acknowledged")
                    Log.d("CrisisMesh-Range", "[Range] Returning to normal discovery state")
                    currentRangeState = RangePhyState.PHY_1M

                    listener.onStatusChanged(
                        "SOS transmitted successfully"
                    )
                }
            }
        }

    // =========================================================
    // SEND SOS
    // =========================================================

    fun sendSOS(
        message: String
    ) {
        val meshPacket = MeshPacket.create(message)
        sendSOS(meshPacket)
    }

    fun sendSOS(
        meshPacket: MeshPacket
    ) {

        synchronized(receivedMeshMessageIds) {
            receivedMeshMessageIds.add(
                meshPacket.messageId
            )
        }

        val serialized =
            meshPacket.serialize()

        listener.onStatusChanged(
            "Created SOS ${meshPacket.messageId}"
        )

        /*
         * If this phone already has a usable outbound
         * client connection, send immediately.
         */
        if (
            clientReady &&
            bluetoothGatt != null &&
            remoteSosCharacteristic != null
        ) {

            sendMeshPacket(
                serialized
            )

            return
        }

        /*
         * Otherwise queue the SOS and discover a peer.
         */
        pendingMeshPacket =
            serialized

        relayExcludeAddress = null
        pendingOriginDeviceId = null

        listener.onStatusChanged(
            "No outbound peer yet — automatically searching..."
        )

        startRelayScan()
    }

    // =========================================================
    // SEND MESH PACKET
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun sendMeshPacket(
        meshMessage: String
    ) {
        Log.d("CrisisMesh-Range", "[Range] SOS transmission started")

        val gatt =
            bluetoothGatt

        val characteristic =
            remoteSosCharacteristic

        if (
            gatt == null ||
            characteristic == null ||
            !clientReady
        ) {

            pendingMeshPacket =
                meshMessage

            clearOutgoingState()

            listener.onStatusChanged(
                "Outbound connection not ready — searching again..."
            )

            startRelayScan()

            return
        }

        if (outgoingChunks.isNotEmpty()) {

            listener.onError(
                "Another SOS is currently transmitting"
            )

            if (pendingMeshPacket == null) {
                pendingMeshPacket = meshMessage
            }

            return
        }

        val bytes =
            meshMessage.toByteArray(
                Charsets.UTF_8
            )

        if (bytes.isEmpty()) {

            listener.onError(
                "Mesh packet is empty"
            )

            return
        }

        val nextCounter =
            (messageCounter + 1) and 0xFF

        messageCounter =
            if (nextCounter == 0) 1 else nextCounter

        val totalPackets =
            (
                    bytes.size + PAYLOAD_SIZE - 1
                    ) / PAYLOAD_SIZE

        if (totalPackets > 255) {

            listener.onError(
                "Mesh message is too large"
            )

            return
        }

        val chunks =
            mutableListOf<ByteArray>()

        for (
        sequence in 0 until totalPackets
        ) {

            val start =
                sequence * PAYLOAD_SIZE

            val end =
                minOf(
                    start + PAYLOAD_SIZE,
                    bytes.size
                )

            val payload =
                bytes.copyOfRange(
                    start,
                    end
                )

            val packet =
                ByteArray(
                    HEADER_SIZE + payload.size
                )

            packet[0] = MAGIC
            packet[1] = messageCounter.toByte()
            packet[2] = sequence.toByte()
            packet[3] = totalPackets.toByte()

            System.arraycopy(
                payload,
                0,
                packet,
                HEADER_SIZE,
                payload.size
            )

            chunks.add(packet)
        }

        outgoingChunks = chunks
        outgoingIndex = 0

        listener.onStatusChanged(
            "Sending SOS 1/$totalPackets..."
        )

        writeNextChunk()
    }

    // =========================================================
    // WRITE NEXT CHUNK
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun writeNextChunk() {

        if (
            outgoingIndex >=
            outgoingChunks.size
        ) {
            return
        }

        val gatt =
            bluetoothGatt

        val characteristic =
            remoteSosCharacteristic

        if (
            gatt == null ||
            characteristic == null ||
            !clientReady
        ) {

            val unsent =
                buildString {
                    // We cannot reconstruct by chunk safely here,
                    // so just fail this transmission and let the
                    // caller retry the original packet on the next send.
                }

            clearOutgoingState()

            listener.onError(
                "Connection lost while sending SOS"
            )

            return
        }

        val packet =
            outgoingChunks[
                outgoingIndex
            ]

        listener.onStatusChanged(
            "Sending SOS ${outgoingIndex + 1}/${outgoingChunks.size}..."
        )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val result =
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )

            if (
                result !=
                android.bluetooth.BluetoothStatusCodes.SUCCESS
            ) {

                clearOutgoingState()

                listener.onError(
                    "Could not send SOS packet: $result"
                )
            }

        } else {

            @Suppress("DEPRECATION")
            characteristic.value = packet

            @Suppress("DEPRECATION")
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            @Suppress("DEPRECATION")
            val success =
                gatt.writeCharacteristic(
                    characteristic
                )

            if (!success) {

                clearOutgoingState()

                listener.onError(
                    "Could not send SOS packet"
                )
            }
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(
        device: BluetoothDevice
    ): String {

        return try {
            device.name ?: "CrisisMesh device"
        } catch (_: SecurityException) {
            "CrisisMesh device"
        }
    }

    private fun clearOutgoingState() {

        outgoingChunks = emptyList()
        outgoingIndex = 0
    }

    // =========================================================
    // STOP
    // =========================================================

    @SuppressLint("MissingPermission")
    fun stop() {

        stopScan()

        handler.removeCallbacksAndMessages(null)

        try {
            advertiser?.stopAdvertising(
                advertiseCallback
            )
        } catch (_: Exception) {
        }

        advertiser = null

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }

        bluetoothGatt = null
        remoteSosCharacteristic = null
        clientReady = false

        try {
            gattServer?.close()
        } catch (_: Exception) {
        }

        gattServer = null
        scanner = null

        pendingMeshPacket = null
        pendingOriginDeviceId = null
        relayExcludeAddress = null
        incomingMessage = null

        clearOutgoingState()

        synchronized(receivedMeshMessageIds) {
            receivedMeshMessageIds.clear()
        }
    }
}
