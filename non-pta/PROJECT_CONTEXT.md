# Project Overview
RelayR is an Android 14 Kotlin application using Jetpack Compose that acts as a VoIP relay. It manages calls and SMS messages through a persistent TCP/UDP connection to a P2P bridge.

# Architecture Summary
The application routes VoIP calls and SMS via a P2P relay server. A foreground service (`BridgeService`) maintains a persistent TCP connection for signaling (`SocketManager`) and manages the audio stream via UDP (`AudioEngine`). The UI interacts with the background service through a shared `PhoneViewModel`, ensuring the UI is reactive to call state changes and can dispatch commands (accept, reject, dial) back to the socket.

State is globally managed using `StateFlow` and `MutableStateFlow`, propagating connection, call status, and audio parameters to Jetpack Compose screens. The system ensures screen blanking during active calls via proximity wake locks and appropriately elevates foreground service permissions (e.g., microphone access) only when a call is accepted.

# Files

- **`app/src/main/java/com/nonpta/bridge/service/BridgeService.kt`**
  - **Keytags**: `#service`, `#lifecycle`, `#networking`, `#state`
  - **Purpose**: Foreground service maintaining socket connection and audio engine. Manages call lifecycle states and proximity wake lock.
  - **Key Classes/Methods**: `BridgeService` (`acceptCall`, `rejectCall`, `endCall`, `startOutgoingCall`, `observeConnectionState`, `observeIncomingSignals`).
  - **State Variables**: `_activeCallState` (CallState), `_ringingCallState` (CallState), `activeCallerId`, `ringingCallerId`, `_isReconnecting`.
  - **Critical Logic**: Connects/disconnects sockets. Elevates foreground permissions for mic usage. Manages proximity lock to prevent face-dials. Uses `MutableSharedFlow` for disconnected/missed events.
  - **Dependencies**: `SocketManager`, `AudioEngine`, `RingerManager`, Notifications.

- **`app/src/main/java/com/nonpta/bridge/viewmodel/PhoneViewModel.kt`**
  - **Keytags**: `#viewmodel`, `#state`, `#ui`
  - **Purpose**: Acts as the bridge between `BridgeService` and the UI. Holds mutable state for dialing, call duration, and mute/speaker toggles.
  - **Key Classes/Methods**: `PhoneViewModel` (`handleSignal`, `dial`, `endCall`, `toggleMute`, `toggleSpeaker`, `startTimer`).
  - **State Variables**: `activeCallState`, `ringingCallState`, `dialedNumber`, `incomingCaller`, `isMuted`, `isSpeaker`, `isHeld`, `isRecording`, `callDurationSeconds`, `isCallMinimized`, `ringingCaller`.
  - **Critical Logic**: Parses incoming `SignalMessage` from socket. Automatically inserts call logs to local DB. Translates SMS signals to local DB insertions.
  - **Dependencies**: `BridgeService`, `ContactResolver`, `CallLogDao`, `MessageDao`.

- **`app/src/main/java/com/nonpta/bridge/network/SocketManager.kt`**
  - **Keytags**: `#networking`, `#signaling`
  - **Purpose**: Persistent TCP client handling JSON signaling with the relay server.
  - **Key Classes/Methods**: `SocketManager` (`connect`, `disconnect`, `sendMessage`).
  - **State Variables**: `_connectionState` (ConnectionState), `_incomingMessages` (SharedFlow<SignalMessage>).
  - **Critical Logic**: Heartbeat ping every 12s, 25s timeout. Thread-safe writing via `Mutex`. Non-blocking disconnect that kills sockets on a detached IO coroutine.
  - **Dependencies**: `Gson`, `SignalMessage`.

- **`app/src/main/java/com/nonpta/bridge/audio/AudioEngine.kt`**
  - **Keytags**: `#audio`, `#udp`
  - **Purpose**: Bidirectional VoIP audio engine over UDP (16 kHz, mono, PCM-16 bit).
  - **Key Classes/Methods**: `AudioEngine` (`start`, `enableMicrophone`, `disableMicrophone`, `stop`, `runPlayback`, `runCapture`).
  - **State Variables**: `_isMuted` (AtomicBoolean), `_isRunning` (AtomicBoolean).
  - **Critical Logic**: Lazy microphone initialization (starts playback first, mic later on acceptance) to prevent premature privacy indicators. Requests Audio Focus and manages `MODE_IN_COMMUNICATION`.
  - **Dependencies**: `AudioTrack`, `AudioRecord`, `DatagramSocket`.

- **`app/src/main/java/com/nonpta/bridge/audio/RingerManager.kt`**
  - **Keytags**: `#audio`, `#haptics`
  - **Purpose**: Handles playback of the system ringtone and vibration for incoming calls.
  - **Key Classes/Methods**: `RingerManager` (`startRinging`, `stopRinging`).
  - **State Variables**: `isRinging` (Boolean).
  - **Critical Logic**: Respects system silent/vibrate modes. Uses modern `VibratorManager` or fallback for haptics.
  - **Dependencies**: `AudioManager`, `RingtoneManager`, `Vibrator`.

- **`app/src/main/java/com/nonpta/bridge/ui/navigation/AppNavigation.kt`**
  - **Keytags**: `#ui`, `#navigation`
  - **Purpose**: Main Jetpack Compose navigation host and UI layout root.
  - **Key Classes/Methods**: `AppNavigation`, `ConnectionBanner`.
  - **State Variables**: Subscribes to `activeCallState`, `connState`, `isCallMinimized`.
  - **Critical Logic**: Handles bottom navigation bar routing. Overlays `ActiveCallScreen` if a call is active. Provides a banner for socket connection state. Displays persistent minimized call banner.
  - **Dependencies**: `PhoneViewModel`, `SettingsViewModel`, various Jetpack Compose Screens.

- **`app/src/main/java/com/nonpta/bridge/ui/screens/ActiveCallScreen.kt`**
  - **Keytags**: `#ui`, `#call`
  - **Purpose**: Renders the active call UI with controls for mute, speaker, hold, and dialpad.
  - **Key Classes/Methods**: `ActiveCallScreen`, `IsolatedCallTimer`, `ActionButton`, `DtmfPad`.
  - **State Variables**: Consumes states from `PhoneViewModel`. Local state: `showDtmfPad`.
  - **Critical Logic**: Overrides hardware volume rocker to `STREAM_VOICE_CALL`. Hides keyboard and clears focus on start. `IsolatedCallTimer` prevents recomposition of the entire screen every second. Solid-wall root catches background taps.
  - **Dependencies**: `PhoneViewModel`.

- **`app/src/main/java/com/nonpta/bridge/ui/screens/DialpadScreen.kt`**
  - **Keytags**: `#ui`, `#dialer`
  - **Purpose**: Provides a standard T9 dialpad interface to initiate calls.
  - **Key Classes/Methods**: `DialpadScreen`.
  - **State Variables**: Consumes `dialedNumber`.
  - **Critical Logic**: Appends digits to the ViewModel state and triggers `vm.dial()`. Includes haptic feedback for keystrokes.
  - **Dependencies**: `PhoneViewModel`.

# State Variables Reference
| Variable | Location | Type | Meaning |
|----------|----------|------|---------|
| `activeCallState` | `BridgeService` / `PhoneViewModel` | `StateFlow<CallState>` | Tracks if a call is IDLE, DIALING, ACTIVE, HELD, or ENDED. |
| `ringingCallState` | `BridgeService` / `PhoneViewModel` | `StateFlow<CallState>` | Tracks if there is an incoming ringing call. |
| `connectionState` | `SocketManager` | `StateFlow<ConnectionState>` | Tracks TCP socket connection (DISCONNECTED, CONNECTING, CONNECTED). |
| `dialedNumber` | `PhoneViewModel` | `StateFlow<String>` | The current number entered on the dialpad. |
| `incomingCaller` | `PhoneViewModel` | `StateFlow<String>` | Phone number of the active or dialing call. |
| `ringingCaller` | `PhoneViewModel` | `StateFlow<String>` | Phone number of the currently ringing incoming call. |
| `isMuted` | `PhoneViewModel` | `StateFlow<Boolean>` | Microphone mute toggle state. |
| `isSpeaker` | `PhoneViewModel` | `StateFlow<Boolean>` | Speakerphone output toggle state. |
| `isCallMinimized` | `PhoneViewModel` | `StateFlow<Boolean>` | Whether the active call screen is minimized into the overlay banner. |
| `callDurationSeconds`| `PhoneViewModel` | `StateFlow<Int>` | Seconds elapsed in the active call. |

# Known Bug Areas
- **Disconnect Timing**: The socket `disconnect()` method uses detached IO coroutines specifically to avoid blocking (documented as `FIX #1`), implying past issues with deadlocks.
- **Keyboard/Focus Bleed**: `ActiveCallScreen` forcefully hides the keyboard (`FIX #3`) and consumes pointer inputs to prevent touch events from interacting with screens underneath it (`FIX #1`).
- **UI System Bars**: The `AppNavigation` connection banner includes padding (`FIX #5`) to avoid sitting under the system status bar.
- **Audio Routing**: `AudioEngine` explicitly sets `MODE_IN_COMMUNICATION` and gains Audio Focus (`VOL FIX`), suggesting previous issues with audio levels or routing.
- **Caller ID Resolution**: Resolved asynchronously (`FIX #1/3` in `ActiveCallScreen`) to prevent blocking the main thread during render.
