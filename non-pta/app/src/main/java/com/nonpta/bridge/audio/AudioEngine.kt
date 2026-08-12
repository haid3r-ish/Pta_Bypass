package com.nonpta.bridge.audio

import android.content.Context
import android.media.*
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional VoIP audio engine over UDP.
 *
 * **Lazy microphone**: [start] only creates the UDP socket + AudioTrack for
 * playback. The AudioRecord (microphone) is initialised separately via
 * [enableMicrophone] — call it on `call_accepted` so the Android 14 green
 * privacy indicator only appears during an active call.
 *
 * 16 kHz · mono · PCM-16 bit · 20 ms chunks (640 bytes).
 */
class AudioEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        const val UDP_PORT = 5001
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MS = 20
        /** 16000 × 2 bytes × 0.020 s = 640 bytes per chunk. */
        val CHUNK_SIZE = SAMPLE_RATE * 2 * CHUNK_MS / 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var udpSocket: DatagramSocket? = null
    private var playbackJob: Job? = null
    private var captureJob: Job? = null

    // Stored so enableMicrophone() can reference them later
    private var remoteIp: InetAddress? = null
    private var remotePort: Int = UDP_PORT
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _isMuted = AtomicBoolean(false)
    val isMuted: Boolean get() = _isMuted.get()

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    /* ── playback-only start ───────────────────────────────────────────── */

    /**
     * Creates the UDP socket and AudioTrack, starts the **playback** loop.
     * Does **NOT** touch AudioRecord — call [enableMicrophone] separately
     * once the call is accepted.
     */
    fun start(context: Context, remoteIp: InetAddress, remotePort: Int = UDP_PORT) {
        if (_isRunning.getAndSet(true)) return
        this.remoteIp = remoteIp
        this.remotePort = remotePort

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ── VOL FIX: Request Audio Focus & Set Communication Mode ──
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* auto-handle */ }
                .build()
            audioFocusRequest?.let { audioManager?.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

        try {
            udpSocket = DatagramSocket(UDP_PORT).apply {
                reuseAddress = true
                soTimeout = 500
            }

            val playBuf = maxOf(
                AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING),
                CHUNK_SIZE * 4
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(playBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            playbackJob = scope.launch { runPlayback() }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /* ── lazy microphone control ───────────────────────────────────────── */

    /**
     * Initialises AudioRecord (VOICE_COMMUNICATION w/ AEC) and starts the
     * capture loop. Call **only** when the call is accepted.
     *
     * This is the point at which the Android 14 green privacy indicator
     * appears. It disappears when [disableMicrophone] or [stop] is called.
     */
    fun enableMicrophone() {
        if (audioRecord != null) return  // already active
        val ip = remoteIp ?: return
        val port = remotePort

        val recBuf = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING),
            CHUNK_SIZE * 4
        )
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_IN)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(recBuf)
            .build()

        audioRecord?.startRecording()
        captureJob = scope.launch { runCapture(ip, port) }
    }

    /**
     * Releases AudioRecord **without** stopping playback or the UDP socket.
     * The privacy indicator disappears immediately.
     */
    fun disableMicrophone() {
        captureJob?.cancel()
        captureJob = null
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
    }

    /* ── full teardown ─────────────────────────────────────────────────── */

    /** Stops everything — playback, capture, UDP socket. */
    fun stop() {
        _isRunning.set(false)
        _isMuted.set(false)
        disableMicrophone()
        playbackJob?.cancel()
        playbackJob = null
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        runCatching { udpSocket?.close() }

        // ── VOL FIX: Cleanup Audio Focus & Restoring Normal Mode ──
        audioManager?.mode = AudioManager.MODE_NORMAL
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }

        audioTrack = null
        udpSocket = null
        remoteIp = null
        audioManager = null
        audioFocusRequest = null
    }

    fun setMuted(muted: Boolean) {
        _isMuted.set(muted)
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    /* ── internal loops ────────────────────────────────────────────────── */

    private suspend fun runPlayback() {
        val buffer = ByteArray(CHUNK_SIZE * 2)
        val packet = DatagramPacket(buffer, buffer.size)

        while (coroutineContext[Job]?.isActive == true) {
            try {
                udpSocket?.receive(packet)
                audioTrack?.write(packet.data, packet.offset, packet.length)
            } catch (_: java.net.SocketTimeoutException) {
                // normal timeout — loop to check isActive
            } catch (_: Exception) {
                break
            }
        }
    }

    private suspend fun runCapture(targetIp: InetAddress, targetPort: Int) {
        val buffer = ByteArray(CHUNK_SIZE)

        while (coroutineContext[Job]?.isActive == true) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0 && !_isMuted.get()) {
                    val packet = DatagramPacket(buffer, bytesRead, targetIp, targetPort)
                    udpSocket?.send(packet)
                }
            } catch (_: Exception) {
                break
            }
        }
    }
}
