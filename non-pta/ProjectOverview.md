# RelayR – Complete System Architecture Document

## 1. Overview
RelayR is a peer-to-peer communication system that allows a **non-PTA approved** flagship phone to use the SIM/cellular capabilities of a **PTA-approved cheap phone** over Wi-Fi. The system bypasses carrier taxes and regional restrictions by separating the cellular radio (SIM) from the user's primary device.

---

## 2. System Components

### 2.1 Hub App (PTA Approved Mobile)
This app runs on a cheap, local PTA-approved Android phone that holds the active SIM card.

**Purpose:**
- Acts as a bridge between the cellular network and the flagship device.
- Receives incoming calls/SMS and relays them to the client app.
- Places outgoing calls/SMS on behalf of the client.

**Key Features:**
- Runs as a lightweight background service.
- Minimal UI – just a status screen and toggle.
- Handles all cellular interactions (call management, SMS).
- Maintains a persistent connection to the client (via TCP/UDP over Wi-Fi).
- Supports root access for advanced features (e.g., auto-answer, call interception).

**Communication:**
- **Local Mode:** Direct Wi-Fi connection to the client.
- **Remote Mode:** Via a relay server (optional, if both phones are on different networks).

---

### 2.2 Client App (Non-PTA Flagship)
This app runs on the user's primary device (Samsung S23 Ultra, iPhone, etc.). It does not have a SIM or cellular radio active.

**Purpose:**
- Provides a full phone experience (calls, SMS, contacts) using the Hub as the backend.
- Connects to the Hub over Wi-Fi to relay all telephony operations.

**Key Features:**
- VoIP calls (low-latency audio over UDP).
- SMS send/receive via TCP signaling.
- Full call log and contacts integration.
- Notification-based call management (works on lock screen).
- Dark mode Material 3 UI.
- Call waiting and proximity sensor support (screen off during active calls).

**Communication:**
- **Local Mode:** Direct Wi-Fi connection to the Hub.
- **Remote Mode:** Via a relay server (if Hub and client are not on the same network).

---

### 2.3 Relay Server (Optional – Self-Hosted or Provided)
This is a lightweight server that acts as an intermediary when the Hub and client are not on the same local network.

**Purpose:**
- Facilitates remote connectivity between Hub and client.
- Routes signaling and audio packets between the two devices.
- Optionally provides NAT traversal.

**Features:**
- Simple TCP/UDP relay with minimal overhead.
- Authentication/pairing to ensure only authorized devices connect.
- Self-hostable by advanced users; also offered as a service.

---

## 3. Communication Modes

| Mode | Network | Server Required? | Use Case |
|------|---------|------------------|----------|
| Local Mode | Same Wi-Fi | No | Hub and client in same home/office. |
| Remote Mode | Different networks | Yes | Hub at home, client on mobile data or other Wi-Fi. |

---

## 4. Hub App (PTA Phone) – Detailed Design

### 4.1 Tech Stack
- Minimum Android 6.0 (API 23) for broad compatibility.
- Kotlin, Coroutines, Flow.
- Minimal dependencies (no Compose – use XML for UI to keep lightweight).
- Foreground Service for background operation.
- TelephonyManager, SmsManager, AudioRecord/AudioTrack.

### 4.2 Core Components

#### 4.2.1 HubService (Foreground)
- Manages TCP server (port 5000) for signaling.
- Manages UDP socket (port 5001) for audio.
- Handles all call/SMS events via TelephonyWrapper.
- Bridges audio between carrier and client.
- Maintains persistent notification "Relay Hub Active".

#### 4.2.2 TelephonyWrapper
- Listens to call state changes (`PhoneStateListener`).
- Answers/rejects calls (via `ITelephony` AIDL if root, else via `TelecomManager` on Android P+).
- Sends/receives SMS (`SmsManager`, `BroadcastReceiver` for incoming SMS).
- Intercepts call logs (optional).

#### 4.2.3 AudioEngine
- Captures voice from mic (`AudioRecord`) and sends via UDP.
- Plays received audio (`AudioTrack`) to speaker/earpiece.
- Supports echo cancellation (via system APIs or manual).

#### 4.2.4 UI (Minimal)
- MainActivity with status: "Service Running/Stopped".
- Toggle button to enable/disable service.
- Connected client IP (if any).
- Log viewer for debugging.

### 4.3 Root Integration (Optional)
- Root is used to:
  - Answer/end calls silently via `ITelephony`.
  - Intercept SMS before they are stored (to avoid duplicates).
  - Access low-level telephony events.
- If root is not available, fallback to standard APIs (with user interaction for answer/reject).

---

## 5. Client App (Non-PTA Flagship) – Detailed Design

### 5.1 Tech Stack
- Android 14+ (optimized for flagship devices).
- Jetpack Compose for UI.
- Foreground Service for background operation.
- TCP signaling (port 5000), UDP audio (port 5001).
- Room Database (call logs, messages), DataStore (settings).
- ContactsContract integration.

### 5.2 Core Components

#### 5.2.1 BridgeService (Foreground)
- Maintains TCP connection to Hub.
- Sends/receives JSON signals (`SignalMessage`).
- Manages UDP audio session during calls.
- Handles incoming call notifications and call control.

#### 5.2.2 PhoneViewModel (UI State)
- Observes `BridgeService` flows.
- Manages `dialedNumber`, `incomingCaller`, `activeCallState`, `ringingCallState`.
- Handles user actions (dial, accept, reject, end).
- Logs all calls to Room database.

#### 5.2.3 Navigation
- Bottom navigation: Calls, Messages, Settings.
- Active Call Screen overlay.
- Minimized "Return to Call" bar (appears when call is minimized).

#### 5.2.4 Notifications
- Incoming call notification with Accept/Reject (always visible).
- Ongoing call notification with End button.
- Notification actions target `BridgeService` directly.

---

## 6. Server (Optional) – Detailed Design

### 6.1 Purpose
- Enables remote connectivity when Hub and client are on different networks.
- Lightweight NAT traversal.

### 6.2 Implementation
- Node.js server with WebSocket or raw TCP/UDP.
- Simple authentication (PIN/pairing).
- Basic logging and session management.

### 6.3 Self-Hosting
- Provide setup scripts for Docker or bare-metal deployment.
- Users can host on their own VPS or local server with public IP.

---

## 7. Summary Table

| Component | Device | Purpose | Key Tech |
|-----------|--------|---------|----------|
| Hub App | PTA Phone | Relay calls/SMS to client | Foreground Service, TelephonyManager, AudioRecord/Track |
| Client App | Non-PTA Flagship | User interface for calls/SMS | Compose, Foreground Service, Room, DataStore |
| Relay Server | Cloud/VPS | Remote connectivity for different networks | Node.js, TCP/UDP relay |

---

## 8. Scalability & Future Enhancements
- **Multi-client support:** Allow multiple flagship devices to connect to one Hub (shared SIM).
- **Encryption:** Add TLS/DTLS for signaling and audio.
- **Mesh support:** Multiple Hubs for redundancy.
- **iOS Client:** Extend protocol for iPhone support.

---

## 9. Conclusion
RelayR provides a complete solution for bypassing carrier taxes by separating the SIM/cellular hardware from the user's primary device. The system is modular, lightweight, and designed for both local and remote operation. With minimal UI on the Hub and a feature-rich client app, it offers a seamless phone experience without paying exorbitant import/activation taxes.