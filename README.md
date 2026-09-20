# 🚨 CrisisMesh

### Resilient Emergency Communication Beyond Connectivity

> **When the network goes down, people become the network.**

CrisisMesh is an Android-based **offline emergency communication system** designed for situations where conventional cellular and internet connectivity may be unavailable, unreliable, or completely disrupted.

Instead of depending entirely on centralized communication infrastructure, CrisisMesh enables nearby smartphones to communicate directly using **Bluetooth Low Energy (BLE)** and forward emergency messages from device to device until they reach a device capable of synchronizing them with an external network.

---

## 📌 Table of Contents

* [Overview](#-overview)
* [The Problem](#-the-problem)
* [The CrisisMesh Approach](#-the-crisismesh-approach)
* [Core Architecture](#-core-architecture)
* [How the System Works](#-how-the-system-works)
* [Key Features](#-key-features)
* [Emergency Message Flow](#-emergency-message-flow)
* [Mesh Networking](#-mesh-networking)
* [BLE Communication](#-ble-communication)
* [SOS Packet System](#-sos-packet-system)
* [Store-Carry-Forward Relay](#-store-carry-forward-relay)
* [TTL and Duplicate Protection](#-ttl-and-duplicate-protection)
* [Location Support](#-location-support)
* [Gateway Synchronization](#-gateway-synchronization)
* [Offline Map](#-offline-map)
* [Application Architecture](#-application-architecture)
* [Technology Stack](#-technology-stack)
* [Repository Structure](#-repository-structure)
* [Android Configuration](#-android-configuration)
* [Permissions](#-permissions)
* [Getting Started](#-getting-started)
* [Running the Application](#-running-the-application)
* [Testing the Mesh](#-testing-the-mesh)
* [Example Scenario](#-example-scenario)
* [Security and Reliability Considerations](#-security-and-reliability-considerations)
* [Current Implementation](#-current-implementation)
* [Limitations](#-limitations)
* [Future Enhancements](#-future-enhancements)
* [Contributing](#-contributing)
* [License](#-license)

---

# 🌐 Overview

During natural disasters, large-scale accidents, infrastructure failures, or other emergency situations, conventional communication systems can become unavailable.

A typical emergency communication architecture looks like:

```text
Person
  │
  ▼
Mobile Network
  │
  ▼
Internet
  │
  ▼
Cloud Server
  │
  ▼
Responder
```

If the cellular or internet infrastructure fails, the communication chain breaks.

CrisisMesh introduces an alternative:

```text
Victim
  │
  ▼
Nearby Smartphone
  │
  ▼
Relay Smartphone
  │
  ▼
Another Relay
  │
  ▼
Gateway Device
  │
  ▼
Internet / Server
  │
  ▼
Responder
```

The central idea is simple:

> **Connectivity is not assumed to exist. Nearby devices themselves become the communication infrastructure.**

---

# 🚨 The Problem

Emergency communication systems commonly depend on infrastructure such as:

* Cellular towers
* Wi-Fi
* Internet connectivity
* Centralized servers
* GPS/network-assisted services
* Communication infrastructure that may fail during disasters

When infrastructure becomes unavailable, victims may be unable to communicate with rescue teams.

A person may still have:

* A functioning smartphone
* Bluetooth
* Battery power
* Nearby people carrying smartphones

CrisisMesh attempts to use these remaining resources to maintain an emergency communication path.

---

# 🕸️ The CrisisMesh Approach

CrisisMesh transforms participating smartphones into temporary communication nodes.

Each device can act as:

1. **Emergency Source**
2. **Mesh Relay**
3. **Emergency Receiver**
4. **Gateway**
5. **Location-aware emergency node**

The system follows a decentralized communication model:

```text
          ┌───────────────┐
          │   Victim A    │
          └───────┬───────┘
                  │ BLE
                  ▼
          ┌───────────────┐
          │   Relay B     │
          └───────┬───────┘
                  │ BLE
                  ▼
          ┌───────────────┐
          │   Relay C     │
          └───────┬───────┘
                  │ BLE
                  ▼
          ┌───────────────┐
          │   Gateway D   │
          └───────┬───────┘
                  │
                  ▼
             Cloud/Server
```

No single smartphone has to maintain an end-to-end connection to the destination.

---

# 🏗️ Core Architecture

The project can be understood as five major layers.

```text
┌──────────────────────────────────────┐
│          USER INTERFACE              │
│ Emergency Messages / SOS / Map       │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│        CRISIS APPLICATION            │
│ MainActivity / Location / Status     │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│        MESH COMMUNICATION            │
│ BluetoothManager / BLE / GATT        │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│       MESSAGE PROCESSING             │
│ MeshPacket / TTL / Deduplication     │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│       GATEWAY / NETWORK              │
│ GatewayUploader / Retrofit / HTTP    │
└──────────────────────────────────────┘
```

---

# 🔄 How the System Works

A typical SOS operation follows these stages.

### 1. User creates an emergency

The user selects an emergency type such as:

* Medical Emergency
* Person Trapped
* Fire Emergency
* Need Water
* Need Food
* Need Medicine
* Need Rescue Team
* Need Evacuation
* General Emergency
* Custom Message

---

### 2. Location is acquired

The application can request the user's:

* Latitude
* Longitude
* Accuracy

Location data can be associated with emergency communication.

---

### 3. SOS packet is created

The emergency message is converted into a structured `MeshPacket`.

The packet contains information such as:

* Message ID
* Origin device ID
* Emergency message
* Hop count
* Relay information
* TTL-related routing information

---

### 4. Nearby CrisisMesh devices are discovered

The phone uses BLE scanning to find other CrisisMesh nodes.

Devices advertise the CrisisMesh BLE service.

---

### 5. BLE connection is established

The originating phone connects to a nearby CrisisMesh peer using:

```text
BLE
 ↓
GATT Connection
 ↓
Service Discovery
 ↓
SOS Characteristic
```

---

### 6. Message is transmitted

The SOS packet is serialized and transmitted through the BLE characteristic.

Large messages can be divided into smaller BLE chunks.

---

### 7. Relay node receives the message

The receiving node:

1. Reconstructs the message
2. Parses the packet
3. Checks whether it has already seen the message
4. Displays the emergency
5. Determines whether the packet can still be relayed

---

### 8. Relay node forwards the SOS

If the packet is still eligible for forwarding, the relay node searches for another CrisisMesh peer.

```text
A → B → C → D → E
```

This creates multi-hop emergency communication.

---

### 9. Gateway synchronization

When a node capable of network communication is available, the emergency packet can be passed to the gateway uploader.

The gateway can then synchronize the emergency information with a server.

---

# 📡 Emergency Message Flow

The complete conceptual pipeline is:

```text
┌─────────┐
│ Victim  │
└────┬────┘
     │
     │ Create SOS
     ▼
┌─────────┐
│ Device A│
└────┬────┘
     │ BLE
     ▼
┌─────────┐
│ Device B│
│ Relay   │
└────┬────┘
     │ BLE
     ▼
┌─────────┐
│ Device C│
│ Relay   │
└────┬────┘
     │ BLE
     ▼
┌─────────┐
│ Gateway │
└────┬────┘
     │ Internet
     ▼
┌─────────┐
│ Server  │
└────┬────┘
     │
     ▼
┌────────────┐
│ Responders │
└────────────┘
```

---

# 🕸️ Mesh Networking

CrisisMesh implements a peer-to-peer relay model.

Each smartphone can participate as a node.

### Node roles

A single device can dynamically behave as:

```text
                 CrisisMesh Node
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
      Source          Relay         Receiver
                                      │
                                      ▼
                                   Gateway
```

This makes the system suitable for environments where the topology is constantly changing.

---

# 📶 BLE Communication

The project uses **Bluetooth Low Energy** as the primary local communication mechanism.

The implementation includes:

* BLE advertising
* BLE scanning
* Service filtering
* GATT connections
* GATT service discovery
* Characteristic discovery
* Characteristic writes
* MTU negotiation
* Connection state handling
* Automatic reconnection/relay attempts

The application uses a dedicated CrisisMesh BLE service and SOS characteristic.

Conceptually:

```text
CrisisMesh BLE Service
│
└── SOS Characteristic
       │
       ├── Write
       └── Receive
```

The BLE layer is implemented primarily in:

```text
BluetoothManager.kt
```

---

# 📦 SOS Packet System

Emergency messages are represented as structured mesh packets.

The application uses:

```text
MeshPacket.create(...)
MeshPacket.parse(...)
MeshPacket.serialize(...)
MeshPacket.createRelayPacket(...)
MeshPacket.canRelay(...)
```

This allows an emergency message to retain its identity as it moves across multiple devices.

Example conceptual packet:

```json
{
  "messageId": "SOS-12345",
  "originDeviceId": "DEVICE-A",
  "message": "Person trapped",
  "hopCount": 2
}
```

The actual serialized representation is handled by the application's `MeshPacket` implementation.

---

# 🔁 Store-Carry-Forward Relay

CrisisMesh follows a store/carry/forward concept.

A node does not require a permanent connection to the destination.

Instead:

```text
STORE
  ↓
CARRY
  ↓
DISCOVER PEER
  ↓
FORWARD
  ↓
REPEAT
```

For example:

```text
A
│
│ SOS
▼
B
│
│ carry
│
│ discovers C
▼
C
│
│ carry
│
│ discovers D
▼
D
```

This is especially useful when the network is sparse or mobile.

---

# ⏳ TTL and Duplicate Protection

Unlimited forwarding would create message storms.

CrisisMesh therefore includes mechanisms to control propagation.

## TTL / Relay Limit

A packet can determine whether it is still eligible for forwarding:

```text
MeshPacket.canRelay()
```

When a packet is relayed, a new relay packet is created.

```text
Original SOS
     │
     ▼
Relay Packet
     │
     ▼
Reduced remaining relay capacity
```

Once the relay limit is exhausted, the packet is stopped.

---

## Duplicate Detection

Each SOS has a message ID.

When a node receives a packet, it checks whether that ID has already been processed.

Conceptually:

```text
Receive packet
      │
      ▼
Message ID seen?
   /        \
 YES        NO
 │           │
 ▼           ▼
Drop       Process
             │
             ▼
          Store ID
```

The implementation also maintains a bounded in-memory message-ID history to prevent unlimited memory growth.

---

# 🔄 Automatic Relay

One of the important parts of the implementation is automatic forwarding.

When a device receives an SOS:

```text
Receive SOS
     │
     ▼
Parse packet
     │
     ▼
Check duplicate
     │
     ▼
Display emergency
     │
     ▼
Check TTL
     │
 ┌───┴────┐
 │        │
No       Yes
 │        │
 ▼        ▼
Stop    Create relay
          │
          ▼
     Search for peer
          │
          ▼
        Connect
          │
          ▼
        Forward
```

The relay mechanism also attempts to avoid immediately sending the message back to the peer that delivered it.

---

# 📍 Location Support

CrisisMesh includes Android location permission handling.

The application can request:

```text
ACCESS_FINE_LOCATION
ACCESS_COARSE_LOCATION
```

The location layer tracks:

```text
Latitude
Longitude
Accuracy
```

This information can be associated with an emergency event.

Example:

```text
Emergency
    │
    ├── Message
    ├── Device ID
    ├── Timestamp
    └── Location
          ├── Latitude
          ├── Longitude
          └── Accuracy
```

---

# ☁️ Gateway Synchronization

CrisisMesh is designed to bridge disconnected mesh communication with normal network communication when a gateway becomes available.

The Android application includes a `GatewayUploader` component.

When an SOS is received:

```text
BLE
 ↓
MeshPacket
 ↓
Local Processing
 ↓
LocationHub
 ↓
GatewayUploader
 ↓
Network
 ↓
Server
```

The project includes networking dependencies for this layer:

* Retrofit
* Retrofit Gson converter
* OkHttp logging
* Gson

This allows the gateway component to communicate with external HTTP-based infrastructure.

---

# 🗺️ Offline Map

The Android manifest registers:

```text
OfflineMapActivity
```

The application also includes the MapLibre Android SDK.

The intended role of the map component is to provide location visualization without making the emergency communication path itself dependent on continuous internet access.

The map dependency currently used by the project is:

```text
MapLibre Android SDK
```

---

# 📱 Application Interface

The main application interface is generated programmatically inside:

```text
MainActivity.kt
```

The UI includes sections for:

### Network Status

Displays the current communication state.

Example:

```text
● Offline Mesh Mode
```

### Cloud Status

Displays gateway/server synchronization status.

### Bluetooth Status

Displays BLE state including:

* Starting
* Device discovered
* Connecting
* Connected
* Disconnected
* SOS received
* Errors

### Location

Allows the user to request their current location.

### Emergency

Provides the emergency communication controls.

### Message Selection

Predefined emergency categories are available together with a custom-message option.

### Received SOS

Received emergency messages can be displayed with information such as:

* Origin device
* SOS ID
* Hop count
* Time
* Message

---

# 🧩 Application Architecture

The primary application components are organized around:

```text
com.crisismesh.app
│
├── MainActivity
│
├── BluetoothManager
│
├── MeshPacket
│
├── GatewayUploader
│
├── LocationHub
│
└── OfflineMapActivity
```

### MainActivity

Responsible for:

* Application UI
* Permission requests
* Bluetooth lifecycle
* Location acquisition
* Emergency message selection
* SOS transmission
* Received SOS display
* Gateway integration

---

### BluetoothManager

Responsible for the mesh communication layer.

It handles:

* BLE advertising
* BLE scanning
* Peer discovery
* GATT connections
* Service discovery
* Characteristic discovery
* Packet transmission
* Packet reception
* Packet reconstruction
* Duplicate detection
* Automatic relay
* Retry logic
* Connection cleanup

---

### MeshPacket

Represents an emergency message as a structured mesh packet.

Responsibilities include:

* Packet creation
* Serialization
* Parsing
* Relay packet generation
* Relay eligibility

---

### GatewayUploader

Responsible for processing received mesh packets and moving them toward the network-connected gateway layer.

---

### LocationHub

Acts as a shared location/status layer used by the application to track mesh and SOS information.

---

### OfflineMapActivity

Provides the map-related user interface.

---

# 🛠️ Technology Stack

| Category                   | Technology                       |
| -------------------------- | -------------------------------- |
| Platform                   | Android                          |
| Language                   | Kotlin                           |
| Build System               | Gradle Kotlin DSL                |
| Minimum SDK                | Android API 29                   |
| Target SDK                 | Android API 37                   |
| Compile SDK                | Android API 37                   |
| UI                         | Android Views                    |
| Bluetooth                  | Bluetooth Low Energy             |
| BLE Communication          | GATT                             |
| Networking                 | Retrofit                         |
| HTTP                       | OkHttp                           |
| Serialization              | Gson                             |
| Mapping                    | MapLibre                         |
| Background Work Dependency | AndroidX Work Runtime            |
| Material Components        | Material                         |
| Testing                    | JUnit / AndroidX Test / Espresso |

---

# 📁 Repository Structure

```text
CRISISMESH/
│
├── .idea/
│
├── app/
│   │
│   ├── build.gradle.kts
│   │
│   └── src/
│       │
│       ├── androidTest/
│       │
│       ├── test/
│       │
│       └── main/
│           │
│           ├── AndroidManifest.xml
│           │
│           ├── java/
│           │   └── com/
│           │       └── crisismesh/
│           │           └── app/
│           │               ├── MainActivity.kt
│           │               ├── BluetoothManager.kt
│           │               ├── MeshPacket.kt
│           │               ├── GatewayUploader.kt
│           │               ├── LocationHub.kt
│           │               └── OfflineMapActivity.kt
│           │
│           ├── keepRules/
│           │
│           └── res/
│               ├── drawable/
│               ├── mipmap-*/
│               ├── values/
│               ├── values-night/
│               └── xml/
│
├── gradle/
│   └── libs.versions.toml
│
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

---

# ⚙️ Android Configuration

The application uses:

```text
applicationId = com.crisismesh.app
minSdk       = 29
targetSdk    = 37
compileSdk   = 37
versionCode  = 1
versionName  = 1.0
```

Java compatibility is configured for:

```text
Java 11
```

The project uses Gradle Kotlin DSL.

---

# 🔐 Permissions

CrisisMesh requires several Android permissions because it operates directly with Bluetooth and location.

## Bluetooth

For older Android versions:

```xml
BLUETOOTH
BLUETOOTH_ADMIN
```

For Android 12+:

```xml
BLUETOOTH_SCAN
BLUETOOTH_ADVERTISE
BLUETOOTH_CONNECT
```

---

## Location

```xml
ACCESS_COARSE_LOCATION
ACCESS_FINE_LOCATION
```

These are used for obtaining emergency coordinates.

---

## Network

```xml
INTERNET
ACCESS_NETWORK_STATE
```

These are required for gateway/network synchronization.

---

# 🚀 Getting Started

## Prerequisites

Install:

* Android Studio
* Android SDK
* JDK 11-compatible development environment
* Android device with BLE support

For realistic mesh testing, use **multiple physical Android devices**.

An emulator generally cannot reproduce real-world BLE peer-to-peer behavior as reliably as physical devices.

---

# 📥 Clone the Repository

```bash
git clone https://github.com/MANOJKUMAR-AS/CRISISMESH.git

cd CRISISMESH
```

Open the project in Android Studio.

Allow Gradle to synchronize all dependencies.

---

# 🔨 Build the Project

Linux/macOS:

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

---

# 🧪 Run Tests

Unit tests:

```bash
./gradlew test
```

Android instrumentation tests:

```bash
./gradlew connectedAndroidTest
```

On Windows:

```powershell
.\gradlew.bat test
```

```powershell
.\gradlew.bat connectedAndroidTest
```

---

# 📱 Install the Debug APK

Build:

```bash
./gradlew assembleDebug
```

The generated APK will normally be located under:

```text
app/build/outputs/apk/debug/
```

Install using ADB:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

# 🧪 Testing the Mesh

For meaningful BLE testing, use at least two Android devices.

### Device A

Install CrisisMesh.

Enable:

* Bluetooth
* Nearby devices permission
* Location permission when requested

### Device B

Install CrisisMesh.

Enable the same permissions.

Then:

```text
Device A
   │
   │ BLE discovery
   ▼
Device B
```

Select an emergency message on Device A.

Example:

```text
🚨 Medical Emergency
```

Send the SOS.

Device B should:

1. Discover the CrisisMesh service
2. Establish a BLE connection
3. Receive the SOS
4. Parse the packet
5. Display the emergency
6. Determine whether the packet can be relayed

---

# 🔬 Multi-Hop Testing

For testing the mesh concept with three devices:

```text
Device A
   │
   ▼
Device B
   │
   ▼
Device C
```

Where:

```text
A = Emergency Source
B = Relay
C = Destination / Next Relay
```

The expected behavior is:

```text
A creates SOS
      ↓
B receives SOS
      ↓
B validates packet
      ↓
B checks duplicate
      ↓
B checks relay eligibility
      ↓
B creates relay packet
      ↓
B searches for another peer
      ↓
C receives forwarded SOS
```

---

# 🌪️ Example Scenario

Imagine a flood has damaged cellular infrastructure.

A person is trapped inside a building.

There is:

```text
No cellular network
No Wi-Fi
No internet
```

However, several people nearby still have smartphones.

### Step 1

The trapped person opens CrisisMesh.

```text
Emergency:
🆘 Person Trapped
```

### Step 2

The phone creates an SOS packet.

```text
SOS
├── Message ID
├── Origin Device
├── Emergency message
├── Hop information
└── Location
```

### Step 3

The phone discovers another CrisisMesh device nearby.

```text
Victim → Neighbor
```

### Step 4

The neighbor receives the SOS and automatically prepares it for relay.

```text
Victim
  ↓
Neighbor
```

### Step 5

The neighbor discovers another device.

```text
Victim
  ↓
Neighbor 1
  ↓
Neighbor 2
```

### Step 6

Eventually the message reaches a gateway device with internet connectivity.

```text
Victim
  ↓
Relay
  ↓
Relay
  ↓
Gateway
  ↓
Server
```

### Step 7

The emergency information can then become available to the connected emergency infrastructure.

---

# 🧠 Design Principles

CrisisMesh is built around several principles.

## 1. Connectivity should not be assumed

The application starts from the assumption that conventional connectivity may fail.

---

## 2. Smartphones become infrastructure

Every participating smartphone can potentially become another communication node.

---

## 3. Messages should survive disconnection

A message does not require a continuous end-to-end connection.

---

## 4. Relay should be controlled

TTL and duplicate detection prevent unlimited propagation.

---

## 5. Communication should be local-first

BLE enables nearby devices to communicate without depending on the internet.

---

## 6. Connectivity should be opportunistic

Whenever another suitable node becomes available, the system can attempt forwarding.

---

# 🔒 Security and Reliability Considerations

The current implementation focuses primarily on emergency communication functionality.

A production deployment should additionally consider:

### Message Authentication

SOS packets should be cryptographically authenticated.

```text
Message
   ↓
Digital Signature / MAC
   ↓
Verification
   ↓
Accept / Reject
```

### Encryption

Sensitive emergency information should be encrypted in transit and potentially at rest.

### Replay Protection

Message IDs and timestamps should be combined with stronger replay-prevention mechanisms.

### Identity Protection

Device identifiers should avoid unnecessarily exposing persistent user identity.

### Malicious Relay Protection

A production mesh should protect against:

* Packet injection
* Message modification
* Replay attacks
* Flooding
* Malicious nodes
* False emergency messages

---

# ⚠️ Current Limitations

The current repository is an Android prototype/implementation and should not be interpreted as a complete production-grade disaster communication infrastructure.

Important considerations include:

### 1. BLE range

Bluetooth communication is fundamentally local-range.

Actual range depends on:

* Device hardware
* Environment
* Obstacles
* Interference
* Transmit power
* Android behavior

---

### 2. Android background restrictions

Long-running mesh behavior can be affected by:

* Battery optimization
* Background execution restrictions
* OS-specific Bluetooth behavior
* Manufacturer-specific power management

---

### 3. Hardware differences

BLE advertising and scanning behavior can vary between Android devices.

---

### 4. Gateway infrastructure

The application contains the gateway synchronization layer, but a complete deployment still requires a properly configured backend/server infrastructure.

---

### 5. Security hardening

A production disaster-response system requires stronger cryptographic identity, authentication, authorization, encryption, and abuse prevention mechanisms.

---

### 6. Real-world validation

A real emergency deployment would require extensive testing under:

* High device density
* Interference
* Low battery
* Device movement
* Intermittent connectivity
* Large message volumes
* Multiple simultaneous emergencies
* Long relay chains

---

# 🚀 Future Enhancements

Potential future development directions include:

## Intelligent Routing

Instead of selecting the first available peer, routing could consider:

```text
Signal strength
Battery level
Node reliability
Mobility
Hop count
Gateway proximity
Historical delivery success
```

---

## Priority-Based Emergency Queues

Emergency messages could be prioritized:

```text
CRITICAL
   ↓
HIGH
   ↓
MEDIUM
   ↓
NORMAL
```

---

## Strong End-to-End Encryption

Introduce:

```text
Public Key Cryptography
        +
Session Encryption
        +
Message Authentication
```

---

## Responder Dashboard

A centralized dashboard could display:

```text
┌─────────────────────────────┐
│       EMERGENCY MAP         │
│                             │
│   🔴 SOS                    │
│        🔴                   │
│              🟢 Gateway     │
│    🔴                       │
└─────────────────────────────┘
```

Responders could see:

* Emergency location
* Message type
* Timestamp
* Hop count
* Message status
* Delivery status
* Nearby gateway information

---

## Adaptive Routing

The mesh could dynamically select routes based on network conditions.

```text
             ┌── Relay B ──┐
Victim ──────┤             ├── Gateway
             └── Relay C ──┘
```

The system could select the path with the highest expected delivery reliability.

---

## Battery-Aware Mesh

Nodes could advertise their available battery state.

For example:

```text
Battery > 50% → Full relay
Battery 20–50% → Limited relay
Battery < 20% → Receive only
```

---

## Persistent Offline Queue

Emergency messages could be stored persistently so that they survive:

* Application restarts
* Temporary BLE loss
* Device movement
* Temporary network outages

---

# 🧪 Validation Strategy

A complete CrisisMesh evaluation should test several dimensions.

### Connectivity

```text
A → B
```

### Multi-hop

```text
A → B → C
```

### Extended relay

```text
A → B → C → D → E
```

### Duplicate handling

```text
A → B
A → C
B → C

C should not process the same SOS twice.
```

### TTL exhaustion

```text
A → B → C → D
          ↓
       TTL = 0
          ↓
        STOP
```

### Gateway recovery

```text
Offline
  ↓
Mesh relay
  ↓
Gateway appears
  ↓
Synchronize
```

### Connection failure

```text
Send
 ↓
Connection lost
 ↓
Retry discovery
 ↓
Find another peer
 ↓
Forward
```

---

# 📊 Conceptual System Comparison

Traditional emergency communication:

```text
User
 ↓
Cell Tower
 ↓
Internet
 ↓
Server
 ↓
Responder
```

CrisisMesh:

```text
User
 ↓
Nearby Device
 ↓
Relay Device
 ↓
Relay Device
 ↓
Gateway
 ↓
Server
 ↓
Responder
```

The fundamental architectural difference is that CrisisMesh introduces a **device-to-device communication layer before the cloud/network layer**.

---

# 🏆 Project Vision

CrisisMesh aims to demonstrate that emergency communication does not have to completely disappear when centralized infrastructure fails.

The vision is:

```text
              INTERNET AVAILABLE
                     │
                     ▼
              ┌────────────┐
              │   CLOUD    │
              └─────▲──────┘
                    │
                 Gateway
                    ▲
                    │
             ┌──────┴──────┐
             │             │
           Relay         Relay
             ▲             ▲
             │             │
          Victim        Victim
             
              INTERNET DOWN
                    │
                    ▼
             DEVICES BECOME
              THE NETWORK
```

> **When infrastructure disappears, proximity becomes connectivity.**

---

# 📚 Project Structure Summary

| Component             | Responsibility                            |
| --------------------- | ----------------------------------------- |
| `MainActivity.kt`     | Main application UI and orchestration     |
| `BluetoothManager.kt` | BLE mesh communication                    |
| `MeshPacket`          | Emergency packet representation           |
| `GatewayUploader`     | Gateway/network synchronization           |
| `LocationHub`         | Location and mesh/SOS state               |
| `OfflineMapActivity`  | Offline map interface                     |
| `AndroidManifest.xml` | Application configuration and permissions |
| `build.gradle.kts`    | Android build configuration               |
| `libs.versions.toml`  | Dependency/version catalog                |
| `settings.gradle.kts` | Gradle project configuration              |

---

# 🤝 Contributing

Contributions are welcome.

Typical contribution workflow:

```bash
git clone https://github.com/MANOJKUMAR-AS/CRISISMESH.git

cd CRISISMESH

git checkout -b feature/your-feature
```

Implement the change, test it, and create a pull request.

Before submitting a contribution, verify:

```bash
./gradlew test
```

and, where physical Android devices are available:

```bash
./gradlew connectedAndroidTest
```

---

# 📝 Development Guidelines

When modifying the mesh layer:

* Avoid creating uncontrolled relay loops.
* Preserve message identity.
* Maintain duplicate detection.
* Respect TTL.
* Handle BLE disconnects gracefully.
* Avoid blocking the UI thread.
* Consider Android background restrictions.
* Test on multiple physical devices.

When modifying emergency data:

* Avoid unnecessary personally identifiable information.
* Protect sensitive location data.
* Validate incoming packets.
* Consider authentication and encryption for production deployments.

---

# 📜 License

No explicit open-source license is currently defined in the repository.

Before redistributing or using the project commercially, add an appropriate license to the repository.

For example:

```text
MIT License
Apache License 2.0
GPL-3.0
```

The appropriate license should be selected based on the project's ownership and intended distribution model.

---

# 👨‍💻 Project

**CrisisMesh**

### Resilient Emergency Communication Beyond Connectivity

```text
Store → Carry → Forward → Synchronize → Respond
```

### Core Concept

```text
Victim
  ↓
Relay
  ↓
Relay
  ↓
Gateway
  ↓
Cloud
  ↓
Responder
```

> **When the network goes down, people become the network.**

---
