package com.crisismesh.app

import android.Manifest
import android.R
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var crisisBluetooth: BluetoothManager

    // =========================================================
    // UI COMPONENTS
    // =========================================================

    private lateinit var bluetoothStatus: TextView
    private lateinit var locationStatus: TextView
    private lateinit var sosStatus: TextView
    private lateinit var receivedMessage: TextView

    private lateinit var messageSpinner: Spinner
    private lateinit var sendSosButton: Button
    private lateinit var scanButton: Button

    // =========================================================
    // LOCATION DATA
    // =========================================================

    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    private var currentAccuracy: Float? = null

    private val predefinedMessages = arrayOf(
        "Select Emergency Message",
        "🚨 Medical Emergency",
        "🆘 Person Trapped",
        "🔥 Fire Emergency",
        "💧 Need Water",
        "🍱 Need Food",
        "💊 Need Medicine",
        "👥 Need Rescue Team",
        "📍 Need Evacuation",
        "⚠️ General Emergency"
    )

    // =========================================================
    // BLUETOOTH PERMISSION LAUNCHER
    // =========================================================

    private val bluetoothPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val allGranted =
                permissions.values.all { it }

            if (allGranted) {

                startCrisisMesh()

            } else {

                bluetoothStatus.text =
                    "Bluetooth: Nearby device permission required"

                Toast.makeText(
                    this,
                    "Please allow Nearby Devices permission",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // =========================================================
    // LOCATION PERMISSION LAUNCHER
    // =========================================================

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] == true

            val coarseGranted =
                permissions[
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ] == true

            if (fineGranted || coarseGranted) {

                fetchCurrentLocation()

            } else {

                locationStatus.text =
                    "Location permission denied"

                Toast.makeText(
                    this,
                    "Location permission is required for SOS coordinates",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // =========================================================
    // BLUETOOTH ENABLE LAUNCHER
    // =========================================================

    private val bluetoothEnableLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {

            if (isBluetoothEnabled()) {

                startCrisisMesh()

            } else {

                bluetoothStatus.text =
                    "Bluetooth: OFF"
            }
        }

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        createUI()

        // -----------------------------------------------------
        // CREATE BLUETOOTH MANAGER
        // -----------------------------------------------------

        crisisBluetooth =
            BluetoothManager(
                this,

                object : BluetoothManager.Listener {

                    // =================================================
                    // STATUS CHANGED
                    // =================================================

                    override fun onStatusChanged(
                        status: String
                    ) {

                        runOnUiThread {

                            bluetoothStatus.text =
                                "Bluetooth: $status"

                            /*
                             * The SOS characteristic has been discovered,
                             * so the BLE connection is actually ready.
                             */
                            if (
                                status.contains(
                                    "CrisisMesh connection ready",
                                    ignoreCase = true
                                ) ||
                                status.contains(
                                    "CrisisMesh ready",
                                    ignoreCase = true
                                )
                            ) {

                                sendSosButton.isEnabled = true

                                sosStatus.text =
                                    "SOS ready — CrisisMesh peer connected"

                                scanButton.text =
                                    "CONNECTED TO CRISIS MESH PEER"
                            }
                        }
                    }

                    // =================================================
                    // DEVICE FOUND
                    // =================================================

                    override fun onDeviceFound(
                        deviceName: String
                    ) {

                        runOnUiThread {

                            bluetoothStatus.text =
                                "Bluetooth: Found $deviceName"

                            scanButton.text =
                                "DEVICE FOUND — CONNECTING..."

                            sosStatus.text =
                                "Connecting to $deviceName..."
                        }
                    }

                    // =================================================
                    // CONNECTED
                    // =================================================

                    override fun onConnected(
                        deviceName: String
                    ) {

                        runOnUiThread {

                            bluetoothStatus.text =
                                "Bluetooth: Connected to $deviceName"

                            sosStatus.text =
                                "SOS ready — connected to $deviceName"

                            sendSosButton.isEnabled =
                                true

                            scanButton.text =
                                "CONNECTED TO CRISIS MESH PEER"

                            Toast.makeText(
                                this@MainActivity,
                                "CrisisMesh peer connected",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    // =================================================
                    // DISCONNECTED
                    // =================================================

                    override fun onDisconnected() {

                        runOnUiThread {

                            bluetoothStatus.text =
                                "Bluetooth: Not connected"

                            sosStatus.text =
                                "SOS prepared — scan for a nearby device"

                            /*
                             * A CrisisMesh node is always allowed to
                             * initiate a new SOS. It does not need an
                             * existing outgoing BLE connection.
                             */
                            sendSosButton.isEnabled =
                                true

                            scanButton.text =
                                "SCAN FOR NEARBY DEVICES"
                        }
                    }

                    // =================================================
                    // SOS RECEIVED
                    // =================================================

                    override fun onMessageReceived(
                        packet: MeshPacket
                    ) {

                        runOnUiThread {

                            bluetoothStatus.text =
                                "Bluetooth: SOS received"

                            sosStatus.text =
                                "🚨 SOS RECEIVED"

                            val time =
                                SimpleDateFormat(
                                    "HH:mm:ss",
                                    Locale.getDefault()
                                ).format(
                                    Date()
                                )

                            receivedMessage.text =
                                """
                                From: ${packet.originDeviceId}
                                SOS ID: ${packet.messageId}
                                Hops: ${packet.hopCount}
                                Time: $time
                                
                                ${packet.message}
                                """.trimIndent()

                            Toast.makeText(
                                this@MainActivity,
                                "🚨 SOS RECEIVED from ${packet.originDeviceId}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    // =================================================
                    // ERROR
                    // =================================================

                    override fun onError(
                        message: String
                    ) {

                        runOnUiThread {

                            bluetoothStatus.text =
                                "Bluetooth: $message"

                            /*
                             * Keep SEND SOS available even after a
                             * connection failure. The next SOS attempt
                             * will automatically search for a peer.
                             */
                            sendSosButton.isEnabled =
                                true
                        }
                    }
                }
            )

        // Start Bluetooth
        requestBluetoothPermissions()
    }

    // =========================================================
    // CREATE UI
    // =========================================================

    private fun createUI() {

        val scrollView =
            ScrollView(this)

        val root =
            LinearLayout(this)

        root.orientation =
            LinearLayout.VERTICAL

        root.setPadding(
            32,
            24,
            32,
            40
        )

        root.setBackgroundColor(
            android.graphics.Color.rgb(
                10,
                15,
                20
            )
        )

        // =====================================================
        // TITLE
        // =====================================================

        val title =
            TextView(this)

        title.text =
            "CRISIS MESH"

        title.textSize =
            32f

        title.setTextColor(
            android.graphics.Color.WHITE
        )

        title.setTypeface(
            null,
            android.graphics.Typeface.BOLD
        )

        root.addView(title)

        // =====================================================
        // SUBTITLE
        // =====================================================

        val subtitle =
            TextView(this)

        subtitle.text =
            "Offline Emergency Communication"

        subtitle.textSize =
            18f

        subtitle.setTextColor(
            android.graphics.Color.LTGRAY
        )

        root.addView(subtitle)

        addSpace(
            root,
            25
        )

        // =====================================================
        // NETWORK STATUS
        // =====================================================

        val networkTitle =
            TextView(this)

        networkTitle.text =
            "NETWORK STATUS"

        networkTitle.textSize =
            16f

        networkTitle.setTextColor(
            android.graphics.Color.LTGRAY
        )

        root.addView(networkTitle)

        val networkStatus =
            TextView(this)

        networkStatus.text =
            "●  Offline Mesh Mode"

        networkStatus.textSize =
            20f

        networkStatus.setTextColor(
            android.graphics.Color.rgb(
                70,
                200,
                90
            )
        )

        root.addView(networkStatus)

        // =====================================================
        // BLUETOOTH
        // =====================================================

        bluetoothStatus =
            TextView(this)

        bluetoothStatus.text =
            "Bluetooth: Starting..."

        bluetoothStatus.textSize =
            18f

        bluetoothStatus.setTextColor(
            android.graphics.Color.WHITE
        )

        root.addView(bluetoothStatus)

        addSpace(
            root,
            25
        )

        // =====================================================
        // LOCATION
        // =====================================================

        val locationTitle =
            TextView(this)

        locationTitle.text =
            "YOUR LOCATION"

        locationTitle.textSize =
            16f

        locationTitle.setTextColor(
            android.graphics.Color.LTGRAY
        )

        root.addView(locationTitle)

        locationStatus =
            TextView(this)

        locationStatus.text =
            "Location not acquired"

        locationStatus.textSize =
            18f

        locationStatus.setTextColor(
            android.graphics.Color.WHITE
        )

        root.addView(locationStatus)

        val locationButton =
            Button(this)

        locationButton.text =
            "GET MY LOCATION"

        locationButton.setOnClickListener {

            requestLocationPermission()
        }

        root.addView(locationButton)

        addSpace(
            root,
            20
        )

        // =====================================================
        // EMERGENCY
        // =====================================================

        val emergencyTitle =
            TextView(this)

        emergencyTitle.text =
            "EMERGENCY"

        emergencyTitle.textSize =
            16f

        emergencyTitle.setTextColor(
            android.graphics.Color.LTGRAY
        )

        root.addView(emergencyTitle)

        sosStatus =
            TextView(this)

        sosStatus.text =
            "SOS prepared — scan for a nearby device"

        sosStatus.textSize =
            18f

        sosStatus.setTextColor(
            android.graphics.Color.WHITE
        )

        root.addView(sosStatus)

        addSpace(
            root,
            12
        )

        // =====================================================
        // PREDEFINED MESSAGES
        // =====================================================

        val spinnerTitle =
            TextView(this)

        spinnerTitle.text =
            "SELECT MESSAGE"

        spinnerTitle.textSize =
            14f

        spinnerTitle.setTextColor(
            Color.LTGRAY
        )

        root.addView(spinnerTitle)

        messageSpinner =
            Spinner(this)

        val adapter =
            ArrayAdapter(
                this,
                R.layout.simple_spinner_item,
                predefinedMessages
            )

        adapter.setDropDownViewResource(
            R.layout.simple_spinner_dropdown_item
        )

        messageSpinner.adapter =
            adapter

        messageSpinner.setBackgroundColor(
            Color.WHITE
        )

        root.addView(messageSpinner)

        addSpace(
            root,
            12
        )

        // =====================================================
        // SEND SOS
        // =====================================================

        sendSosButton =
            Button(this)

        sendSosButton.text =
            "🚨  SEND SOS"

        sendSosButton.isEnabled =
            true

        sendSosButton.setOnClickListener {

            sendSOS()
        }

        root.addView(sendSosButton)

        addSpace(
            root,
            8
        )

        // =====================================================
        // SCAN
        // =====================================================

        scanButton =
            Button(this)

        scanButton.text =
            "SCAN FOR NEARBY DEVICES"

        scanButton.setOnClickListener {

            /*
             * Keep SEND SOS available. The BluetoothManager
             * can queue an SOS and establish a connection
             * automatically if needed.
             */
            sendSosButton.isEnabled =
                true

            sosStatus.text =
                "Searching for nearby CrisisMesh devices..."

            crisisBluetooth.startScan()
        }

        root.addView(scanButton)

        addSpace(
            root,
            25
        )

        // =====================================================
        // LAST RECEIVED EMERGENCY
        // =====================================================

        val receivedTitle =
            TextView(this)

        receivedTitle.text =
            "LAST RECEIVED EMERGENCY"

        receivedTitle.textSize =
            16f

        receivedTitle.setTextColor(
            android.graphics.Color.LTGRAY
        )

        root.addView(receivedTitle)

        addSpace(
            root,
            8
        )

        receivedMessage =
            TextView(this)

        receivedMessage.text =
            "No SOS messages received yet."

        receivedMessage.textSize =
            17f

        receivedMessage.setTextColor(
            android.graphics.Color.WHITE
        )

        receivedMessage.setPadding(
            20,
            20,
            20,
            20
        )

        receivedMessage.setBackgroundColor(
            android.graphics.Color.rgb(
                25,
                32,
                40
            )
        )

        root.addView(receivedMessage)

        // =====================================================
        // SET CONTENT
        // =====================================================

        scrollView.addView(root)

        setContentView(scrollView)
    }

    // =========================================================
    // REQUEST LOCATION PERMISSION
    // =========================================================

    private fun requestLocationPermission() {

        val permissions =
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

        val missing =
            permissions.filter {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {

            locationPermissionLauncher.launch(
                missing.toTypedArray()
            )

        } else {

            fetchCurrentLocation()
        }
    }

    // =========================================================
    // FETCH CURRENT LOCATION
    // =========================================================

    private fun fetchCurrentLocation() {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {

            locationStatus.text =
                "Location permission required"

            return
        }

        val locationManager =
            getSystemService(
                LOCATION_SERVICE
            ) as LocationManager

        val gpsEnabled =
            try {
                locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                )
            } catch (_: Exception) {
                false
            }

        val networkEnabled =
            try {
                locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
            } catch (_: Exception) {
                false
            }

        if (!gpsEnabled && !networkEnabled) {

            locationStatus.text =
                "Location is OFF"

            Toast.makeText(
                this,
                "Please turn ON Location",
                Toast.LENGTH_LONG
            ).show()

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    )
                )

            } catch (_: Exception) {
            }

            return
        }

        locationStatus.text =
            "Acquiring location..."

        /*
         * First check cached locations.
         *
         * This is especially useful when GPS needs
         * a few seconds to get a fresh fix.
         */
        val cached =
            getBestLastKnownLocation(
                locationManager
            )

        if (cached != null) {

            handleLocation(cached)

            /*
             * Continue requesting a fresh location
             * below.
             */
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            /*
             * Try GPS first.
             */
            if (gpsEnabled) {

                try {

                    locationManager.getCurrentLocation(
                        LocationManager.GPS_PROVIDER,
                        null,
                        mainExecutor
                    ) { location ->

                        if (location != null) {

                            handleLocation(
                                location
                            )

                        } else {

                            /*
                             * GPS did not provide a fix.
                             * Try network.
                             */
                            if (networkEnabled) {

                                tryNetworkLocation(
                                    locationManager
                                )
                            } else if (cached == null) {

                                locationStatus.text =
                                    "Unable to acquire location"
                            }
                        }
                    }

                    return

                } catch (
                    e: SecurityException
                ) {

                    locationStatus.text =
                        "Location permission error"

                    return
                }
            }

            /*
             * GPS is unavailable.
             * Try network provider.
             */
            if (networkEnabled) {

                tryNetworkLocation(
                    locationManager
                )

            } else if (cached == null) {

                locationStatus.text =
                    "Unable to acquire location"
            }

        } else {

            /*
             * Android versions below API 30.
             */
            if (cached == null) {

                locationStatus.text =
                    "Unable to acquire location"
            }
        }
    }

    // =========================================================
    // NETWORK LOCATION
    // =========================================================

    private fun tryNetworkLocation(
        locationManager: LocationManager
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {

            return
        }

        try {

            locationManager.getCurrentLocation(
                LocationManager.NETWORK_PROVIDER,
                null,
                mainExecutor
            ) { location ->

                if (location != null) {

                    handleLocation(
                        location
                    )

                } else {

                    val cached =
                        getBestLastKnownLocation(
                            locationManager
                        )

                    if (cached != null) {

                        handleLocation(
                            cached
                        )

                    } else {

                        locationStatus.text =
                            "Unable to acquire location"
                    }
                }
            }

        } catch (
            e: SecurityException
        ) {

            locationStatus.text =
                "Location permission error"
        }
    }

    // =========================================================
    // BEST CACHED LOCATION
    // =========================================================

    private fun getBestLastKnownLocation(
        locationManager: LocationManager
    ): Location? {

        val locations =
            mutableListOf<Location>()

        try {

            val gpsLocation =
                locationManager.getLastKnownLocation(
                    LocationManager.GPS_PROVIDER
                )

            if (gpsLocation != null) {
                locations.add(gpsLocation)
            }

        } catch (_: SecurityException) {
        }

        try {

            val networkLocation =
                locationManager.getLastKnownLocation(
                    LocationManager.NETWORK_PROVIDER
                )

            if (networkLocation != null) {
                locations.add(networkLocation)
            }

        } catch (_: SecurityException) {
        }

        try {

            val passiveLocation =
                locationManager.getLastKnownLocation(
                    LocationManager.PASSIVE_PROVIDER
                )

            if (passiveLocation != null) {
                locations.add(passiveLocation)
            }

        } catch (_: SecurityException) {
        }

        return locations.maxByOrNull {
            it.time
        }
    }

    // =========================================================
    // HANDLE LOCATION
    // =========================================================

    private fun handleLocation(
        location: Location
    ) {

        currentLatitude =
            location.latitude

        currentLongitude =
            location.longitude

        currentAccuracy =
            location.accuracy

        locationStatus.text =
            """
            Latitude: %.6f
            Longitude: %.6f
            Accuracy: %.1f m
            """.trimIndent().format(
                Locale.US,
                location.latitude,
                location.longitude,
                location.accuracy
            )

        sosStatus.text =
            "GPS acquired — SOS ready"
    }

    // =========================================================
    // SEND SOS
    // =========================================================

    private fun sendSOS() {

        val selectedMessage =
            messageSpinner.selectedItem.toString()

        val emergencyPrefix =
            if (
                selectedMessage ==
                predefinedMessages[0]
            ) {
                "🚨 CRISIS MESH SOS"
            } else {
                selectedMessage
            }

        val time =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(
                Date()
            )

        val latitude =
            currentLatitude

        val longitude =
            currentLongitude

        val latitudeText =
            latitude?.let {

                String.format(
                    Locale.US,
                    "%.6f",
                    it
                )

            } ?: "UNAVAILABLE"

        val longitudeText =
            longitude?.let {

                String.format(
                    Locale.US,
                    "%.6f",
                    it
                )

            } ?: "UNAVAILABLE"

        val accuracyText =
            currentAccuracy?.let {

                String.format(
                    Locale.US,
                    "%.1f m",
                    it
                )

            } ?: "UNAVAILABLE"

        val message =
            """
            $emergencyPrefix
            
            Emergency assistance required.
            
            Sender: ${Build.MODEL}
            Device ID: ${Build.ID}
            
            Location:
            Latitude: $latitudeText
            Longitude: $longitudeText
            Accuracy: $accuracyText
            
            Time: $time
            
            Priority: HIGH
            """.trimIndent()

        /*
         * Keep the button enabled. BluetoothManager will
         * reject a second simultaneous transmission if
         * one is already in progress.
         */
        sendSosButton.isEnabled =
            true

        sosStatus.text =
            "🚨 SOS TRANSMITTING..."

        crisisBluetooth.sendSOS(
            message
        )
    }

    // =========================================================
    // BLUETOOTH PERMISSIONS
    // =========================================================

    private fun requestBluetoothPermissions() {

        val permissions =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )

            } else {

                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            }

        val missing =
            permissions.filter {

                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {

            bluetoothPermissionLauncher.launch(
                missing.toTypedArray()
            )

        } else {

            startCrisisMesh()
        }
    }

    // =========================================================
    // START CRISIS MESH
    // =========================================================

    private fun startCrisisMesh() {

        if (!isBluetoothEnabled()) {

            val intent =
                Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE
                )

            bluetoothEnableLauncher.launch(
                intent
            )

            return
        }

        crisisBluetooth.start()

        /*
         * Every CrisisMesh phone can both send and receive.
         * SEND SOS does not require a pre-existing connection.
         */
        sendSosButton.isEnabled =
            true

        sosStatus.text =
            "SOS ready — press SEND SOS"
    }

    // =========================================================
    // BLUETOOTH ENABLED?
    // =========================================================

    private fun isBluetoothEnabled(): Boolean {

        val manager =
            getSystemService(
                BLUETOOTH_SERVICE
            ) as AndroidBluetoothManager

        return manager.adapter?.isEnabled == true
    }

    // =========================================================
    // ADD SPACE
    // =========================================================

    private fun addSpace(
        parent: LinearLayout,
        height: Int = 16
    ) {

        val space =
            Space(this)

        space.layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
            )

        parent.addView(space)
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onDestroy() {

        if (
            ::crisisBluetooth.isInitialized
        ) {

            crisisBluetooth.stop()
        }

        super.onDestroy()
    }
}
