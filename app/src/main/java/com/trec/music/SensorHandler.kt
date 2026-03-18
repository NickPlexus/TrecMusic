//SensorHandler.kt
//ЯКОРЬ МНЕ В ГУЗНО, ЭТО ЖЕ ИСПОЛЬЗОВАНИЕ ВОЗМОЖНОСТЕЙ ТЕЛЕФОНА, ПАЛКОЙ МНЕ ПО ЖОПКЕ

package com.trec.music.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.sqrt

class SensorHandler(private val vm: MusicViewModel) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0
    private var vibrator: Vibrator? = null

    // SoundPool
    private var soundPool: SoundPool? = null
    private val scratchSoundIds = mutableListOf<Int>()
    private var currentStreamId: Int = 0

    fun initSensors(app: Application) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vmM = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vmM.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun initSoundPool(context: Context) {
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        soundPool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attr).build()
        listOf("scratch", "scratch1", "scratch2", "scratch3", "scratch4").forEach { name ->
            val id = context.resources.getIdentifier(name, "raw", context.packageName)
            if (id != 0) soundPool?.load(context, id, 1)?.let { scratchSoundIds.add(it) }
        }
    }

    fun toggleNeedle() { vm.isNeedleEnabled = !vm.isNeedleEnabled; vm.prefs.saveNeedleEnabled(vm.isNeedleEnabled) }
    fun toggleScratchSound() { vm.isScratchSoundEnabled = !vm.isScratchSoundEnabled; vm.prefs.saveScratchEnabled(vm.isScratchSoundEnabled) }
    fun toggleShake() { vm.isShakeEnabled = !vm.isShakeEnabled; vm.prefs.saveShakeEnabled(vm.isShakeEnabled) }

    @SuppressLint("MissingPermission")
    fun performHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(e: SensorEvent?) {
        if (!vm.isShakeEnabled) return
        if (e?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
            if (g > 3.5) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 1500) {
                    lastShakeTime = now
                    vm.skipNextRandom()
                }
            }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    fun startScratchLoop() {
        if (!vm.isScratchSoundEnabled || scratchSoundIds.isEmpty()) return
        stopScratchLoop()
        currentStreamId = soundPool?.play(scratchSoundIds.random(), 0.7f, 0.7f, 1, -1, 1f) ?: 0
    }

    fun stopScratchLoop() {
        if (currentStreamId != 0) {
            soundPool?.stop(currentStreamId)
            currentStreamId = 0
        }
    }

    fun hasScratchSounds() = scratchSoundIds.isNotEmpty()

    fun playRandomScratch() {
        if (scratchSoundIds.isNotEmpty()) {
            soundPool?.play(scratchSoundIds.random(), 0.3f, 0.3f, 1, 0, 2.0f)
        }
    }

    fun onScratch(dragAmount: Float) {
        val delta = (dragAmount * 100).toLong(); val cur = vm.player?.currentPosition ?: 0
        val vis = if (vm.isReversing) (vm.duration - cur) else cur
        val newVis = (vis + delta).coerceIn(0, vm.duration)

        vm.player?.seekTo(if (vm.isReversing) (vm.duration - newVis) else newVis)
        vm.currentPosition = newVis
        vm.vinylRotationAngle += dragAmount * 0.5f
    }

    fun cleanup() {
        soundPool?.release()
        sensorManager?.unregisterListener(this)
    }
}
