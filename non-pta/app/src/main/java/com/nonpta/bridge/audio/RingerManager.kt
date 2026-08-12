package com.nonpta.bridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class RingerManager(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isRinging = false

    init {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun startRinging() {
        if (isRinging) return
        isRinging = true

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Respect silent/vibrate modes
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                ringtone?.play()
                startVibrating()
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                startVibrating()
            }
            AudioManager.RINGER_MODE_SILENT -> {
                // Do nothing
            }
        }
    }

    private fun startVibrating() {
        // Wait 0ms, Vibrate 1000ms, Wait 1000ms...
        val pattern = longArrayOf(0, 1000, 1000)
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(pattern, 1)) // 1 = repeat from index 1
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 1)
            }
        }
    }

    fun stopRinging() {
        if (!isRinging) return
        isRinging = false
        ringtone?.takeIf { it.isPlaying }?.stop()
        vibrator?.cancel()
    }
}
