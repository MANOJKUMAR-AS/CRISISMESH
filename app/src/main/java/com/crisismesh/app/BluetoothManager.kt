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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID

class BluetoothManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatusChanged(status: String)
        fun onDeviceFound(deviceName: String)
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onMessageReceived(message: String)
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
        private const val PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE
        private const val MAGIC: Byte = 0x43
    }

    private val androidBluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as AndroidBluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = androidBluetoothManager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var gattServer: BluetoothGattServer? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var remoteSosCharacteristic:
            BluetoothGattCharacteristic? = null

    private var scanning = false

    private var clientReady = false
    private var connectedDevice: BluetoothDevice? = null

    private var outgoingChunks: List<ByteArray> =
        emptyList()

    private var outgoingIndex = 0

    private data class IncomingMessage(
        val messageId: Int,
        val totalPackets: Int,
        val packets: MutableMap<Int, ByteArray>
    )

    private var incomingMessage:
            IncomingMessage? = null

    private var messageCounter = 0

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
            "Starting CrisisMesh..."
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

        val sosCharacteristic =
            BluetoothGattCharacteristic(
                SOS_CHARACTERISTIC_UUID,

                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,

                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

        service.addCharacteristic(
            sosCharacteristic
        )

        val success =
            gattServer?.addService(service)
                ?: false

        if (!success) {

            listener.onError(
                "Could not add CrisisMesh service"
            )
        }
    }

    // =========================================================
    // GATT SERVER CALLBACK
    // =========================================================

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

            @SuppressLint("MissingPermission")
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

                processIncomingPacket(value)
            }
        }

    // =========================================================
    // RECEIVE PACKETS
    // =========================================================

    private fun processIncomingPacket(
        packet: ByteArray
    ) {

        if (packet.size < HEADER_SIZE) {
            return
        }

        if (packet[0] != MAGIC) {
            return
        }

        val messageId =
            packet[1].toInt() and 0xFF

        val sequence =
            packet[2].toInt() and 0xFF

        val total =
            packet[3].toInt() and 0xFF

        if (
            total <= 0 ||
            sequence >= total
        ) {
            return
        }

        if (
            incomingMessage == null ||
            incomingMessage!!.messageId != messageId
        ) {

            incomingMessage =
                IncomingMessage(
                    messageId = messageId,
                    totalPackets = total,
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
            "Receiving SOS ${current.packets.size}/$total"
        )

        if (
            current.packets.size ==
            current.totalPackets
        ) {

            val output =
                ByteArrayOutputStream()

            for (
            i in 0 until current.totalPackets
            ) {

                val chunk =
                    current.packets[i]
                        ?: return

                output.write(chunk)
            }

            val completeMessage =
                output
                    .toByteArray()
                    .toString(Charsets.UTF_8)

            incomingMessage = null

            listener.onMessageReceived(
                completeMessage
            )
        }
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
                .setIncludeDeviceName(true)
                .addServiceUuid(
                    ParcelUuid(SERVICE_UUID)
                )
                .build()

        advertiser?.startAdvertising(
            settings,
            data,
            advertiseCallback
        )
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
    // SCANNING
    // =========================================================

    @SuppressLint("MissingPermission")
    fun startScan() {

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

        /*
         * Always clean up an old outgoing connection
         * before starting a new scan.
         */
        disconnectClient()

        if (scanning) {
            stopScan()
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
            "Scanning for CrisisMesh peers..."
        )

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid(SERVICE_UUID)
                )
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .build()

        scanning = true

        scanner?.startScan(
            listOf(filter),
            settings,
            scanCallback
        )
    }

    private val scanCallback =
        object : ScanCallback() {

            @SuppressLint("MissingPermission")
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                val device =
                    result.device

                val name =
                    safeDeviceName(device)

                listener.onDeviceFound(name)

                stopScan()

                connectToDevice(device)
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                scanning = false

                listener.onError(
                    "Scan failed: $errorCode"
                )
            }
        }

    @SuppressLint("MissingPermission")
    private fun stopScan() {

        if (!scanning) {
            return
        }

        scanner?.stopScan(
            scanCallback
        )

        scanning = false
    }

    // =========================================================
    // CONNECT
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun connectToDevice(
        device: BluetoothDevice
    ) {

        disconnectClient()

        remoteSosCharacteristic = null
        clientReady = false

        connectedDevice = device

        listener.onStatusChanged(
            "Connecting to ${safeDeviceName(device)}..."
        )

        bluetoothGatt =
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
    }

    // =========================================================
    // GATT CLIENT CALLBACK
    // =========================================================

    private val gattCallback =
        object : BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    listener.onStatusChanged(
                        "Connected — discovering CrisisMesh service..."
                    )

                    gatt.discoverServices()

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    clientReady = false
                    remoteSosCharacteristic = null

                    listener.onDisconnected()

                    listener.onError(
                        "Disconnected. GATT status: $status"
                    )

                    gatt.close()

                    if (
                        bluetoothGatt === gatt
                    ) {
                        bluetoothGatt = null
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
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
                    "CrisisMesh ready — bidirectional SOS enabled"
                )

                listener.onConnected(
                    connectedDevice?.let {
                        safeDeviceName(it)
                    } ?: "CrisisMesh peer"
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    outgoingIndex++

                    if (
                        outgoingIndex <
                        outgoingChunks.size
                    ) {

                        writeNextChunk()

                    } else {

                        outgoingChunks =
                            emptyList()

                        outgoingIndex = 0

                        listener.onStatusChanged(
                            "SOS transmitted successfully"
                        )
                    }

                } else {

                    outgoingChunks =
                        emptyList()

                    outgoingIndex = 0

                    listener.onError(
                        "SOS packet failed: $status"
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

        if (!clientReady) {

            listener.onError(
                "CrisisMesh connection is not ready"
            )

            return
        }

        val gatt =
            bluetoothGatt

        val characteristic =
            remoteSosCharacteristic

        if (
            gatt == null ||
            characteristic == null
        ) {

            listener.onError(
                "CrisisMesh connection is not ready"
            )

            return
        }

        if (
            outgoingChunks.isNotEmpty()
        ) {

            listener.onError(
                "Another SOS is currently transmitting"
            )

            return
        }

        val messageBytes =
            message.toByteArray(
                Charsets.UTF_8
            )

        if (messageBytes.isEmpty()) {

            listener.onError(
                "SOS message is empty"
            )

            return
        }

        messageCounter =
            (messageCounter + 1) and 0xFF

        if (messageCounter == 0) {
            messageCounter = 1
        }

        val totalPackets =
            (
                    messageBytes.size +
                            PAYLOAD_SIZE - 1
                    ) / PAYLOAD_SIZE

        if (totalPackets > 255) {

            listener.onError(
                "SOS message is too large"
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
                    messageBytes.size
                )

            val payload =
                messageBytes.copyOfRange(
                    start,
                    end
                )

            val packet =
                ByteArray(
                    HEADER_SIZE +
                            payload.size
                )

            packet[0] =
                MAGIC

            packet[1] =
                messageCounter.toByte()

            packet[2] =
                sequence.toByte()

            packet[3] =
                totalPackets.toByte()

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
    // WRITE PACKET
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun writeNextChunk() {

        val gatt =
            bluetoothGatt

        val characteristic =
            remoteSosCharacteristic

        if (
            gatt == null ||
            characteristic == null ||
            !clientReady
        ) {

            outgoingChunks =
                emptyList()

            outgoingIndex = 0

            listener.onError(
                "Connection lost while sending SOS"
            )

            return
        }

        if (
            outgoingIndex >=
            outgoingChunks.size
        ) {
            return
        }

        val packet =
            outgoingChunks[
                outgoingIndex
            ]

        listener.onStatusChanged(
            "Sending SOS ${
                outgoingIndex + 1
            }/${outgoingChunks.size}..."
        )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val result =
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )

            if (
                result !=
                android.bluetooth.BluetoothStatusCodes.SUCCESS
            ) {

                outgoingChunks =
                    emptyList()

                outgoingIndex = 0

                listener.onError(
                    "Could not send SOS packet: $result"
                )
            }

        } else {

            @Suppress("DEPRECATION")

            characteristic.value =
                packet

            @Suppress("DEPRECATION")

            val success =
                gatt.writeCharacteristic(
                    characteristic
                )

            if (!success) {

                outgoingChunks =
                    emptyList()

                outgoingIndex = 0

                listener.onError(
                    "Could not send SOS packet"
                )
            }
        }
    }

    // =========================================================
    // DISCONNECT CLIENT
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun disconnectClient() {

        bluetoothGatt?.let { gatt ->

            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }

            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }

        bluetoothGatt = null
        remoteSosCharacteristic = null
        connectedDevice = null
        clientReady = false

        outgoingChunks =
            emptyList()

        outgoingIndex = 0
    }

    // =========================================================
    // DEVICE NAME
    // =========================================================

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(
        device: BluetoothDevice
    ): String {

        return try {

            device.name
                ?: "CrisisMesh device"

        } catch (
            _: SecurityException
        ) {

            "CrisisMesh device"
        }
    }

    // =========================================================
    // STOP
    // =========================================================

    @SuppressLint("MissingPermission")
    fun stop() {

        stopScan()

        try {
            advertiser?.stopAdvertising(
                advertiseCallback
            )
        } catch (_: Exception) {
        }

        advertiser = null

        disconnectClient()

        gattServer?.close()

        gattServer = null

        incomingMessage = null
    }
}