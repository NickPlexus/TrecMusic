// viewmodel/RecorderViewModel.kt
package com.trec.music.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.trec.music.PlaybackCoordinator
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class RecordingItem(val file: File, val durationMs: Long, val dateAdded: Long)

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    // --- State ---
    var isRecording by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var recordingDuration by mutableLongStateOf(0L)
    var currentAmplitude by mutableIntStateOf(0)

    // --- Settings ---
    var isNoiseSuppressionEnabled by mutableStateOf(true)
    var skipSilence by mutableStateOf(false)

    // --- List ---
    var recordings = mutableStateListOf<RecordingItem>()

    // --- Audio Engine ---
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // --- Playback (recordings) ---
    private var playbackPlayer: ExoPlayer? = null
    private var playbackJob: Job? = null
    var currentPlayback by mutableStateOf<RecordingItem?>(null)
    var isPlaybackPlaying by mutableStateOf(false)
    var playbackPosition by mutableLongStateOf(0L)
    var playbackDuration by mutableLongStateOf(0L)

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    private var currentFile: File? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        PlaybackCoordinator.registerRecorderPause { pausePlayback() }
    }

    fun initialize() {
        refreshRecordings()
    }

    private fun getRecordingsDir(): File {
        val context = getApplication<Application>()
        // Используем app-specific storage.
        // Чтобы файлы не удалялись при удалении приложения, нужно использовать MediaStore (значительно сложнее).
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val trecDir = File(baseDir, "TrecRecordings")
        if (!trecDir.exists()) trecDir.mkdirs()
        return trecDir
    }

    fun refreshRecordings() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = getRecordingsDir()
            if (!dir.exists()) return@launch

            val files = dir.listFiles()
                ?.filter { it.extension == "wav" || it.extension == "m4a" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val items = mutableListOf<RecordingItem>()
            val retriever = MediaMetadataRetriever()

            files.forEach { file ->
                var duration = 0L
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (durStr != null) duration = durStr.toLong()
                } catch (e: Exception) {
                    // Fallback для WAV
                    if (file.extension == "wav") {
                        val rawDataSize = file.length() - 44
                        val bytesPerSecond = SAMPLE_RATE * 1 * 2  // MONO = 1 channel * 2 bytes per sample
                        if (bytesPerSecond > 0) duration = (rawDataSize * 1000) / bytesPerSecond
                    }
                }
                items.add(RecordingItem(file, duration, file.lastModified()))
            }
            try { retriever.release() } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                recordings.clear()
                recordings.addAll(items)
            }
        }
    }

    private fun ensurePlaybackPlayer() {
        if (playbackPlayer != null) return
        val context = getApplication<Application>()
        playbackPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlaybackPlaying = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        playbackDuration = duration.coerceAtLeast(0L)
                    }
                    if (state == Player.STATE_ENDED) {
                        isPlaybackPlaying = false
                        playbackPosition = playbackDuration
                    }
                }
            })
        }
    }

    private fun startPlaybackProgressLoop() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive && playbackPlayer != null) {
                val p = playbackPlayer ?: break
                playbackPosition = p.currentPosition.coerceAtLeast(0L)
                playbackDuration = p.duration.coerceAtLeast(0L)
                delay(250)
            }
        }
    }

    fun playRecording(item: RecordingItem) {
        PlaybackCoordinator.pauseMusic()
        ensurePlaybackPlayer()
        val player = playbackPlayer ?: return

        if (currentPlayback?.file?.absolutePath == item.file.absolutePath) {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            if (isPlaybackPlaying) {
                player.pause()
            } else {
                player.play()
            }
            return
        }

        currentPlayback = item
        playbackPosition = 0L
        playbackDuration = item.durationMs
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(item.file)))
        player.prepare()
        player.play()
        startPlaybackProgressLoop()
    }

    fun pausePlayback() {
        playbackPlayer?.pause()
        isPlaybackPlaying = false
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackPlayer?.stop()
        playbackPlayer?.clearMediaItems()
        isPlaybackPlaying = false
        currentPlayback = null
        playbackPosition = 0L
        playbackDuration = 0L
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val context = getApplication<Application>()

        // ЯВНАЯ ПРОВЕРКА ПРАВ (на всякий случай, хотя UI теперь запрашивает)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Нет прав на микрофон!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!requestAudioFocus(context)) {
            Toast.makeText(context, "Микрофон недоступен", Toast.LENGTH_SHORT).show()
            return
        }

        stopPlayback()

        val dir = getRecordingsDir()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(dir, "REC_$timeStamp.wav")

        try {
            val audioSource = if (isNoiseSuppressionEnabled) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC
            audioRecord = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE)
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw Exception("Init failed")

            audioRecord?.startRecording()
            isRecording = true
            isPaused = false
            recordingDuration = 0L
            currentAmplitude = 0

            startWritingLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Ошибка старта: ${e.message}", Toast.LENGTH_SHORT).show()
            cleanupRecorder()
        }
    }

    private fun startWritingLoop() {
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val outputStream = FileOutputStream(currentFile)
            outputStream.write(ByteArray(44))

            val buffer = ShortArray(BUFFER_SIZE / 2)
            var totalBytesWritten = 0L
            var silenceFramesToKeep = 15
            var silenceCounter = 0

            while (isActive && isRecording) {
                if (isPaused) { delay(50); continue }

                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (readResult > 0) {
                    var maxAmp = 0
                    for (i in 0 until readResult) {
                        val absVal = abs(buffer[i].toInt())
                        if (absVal > maxAmp) maxAmp = absVal
                    }
                    currentAmplitude = maxAmp

                    var shouldWrite = true
                    if (skipSilence) {
                        if (maxAmp < 800) {
                            if (silenceCounter < silenceFramesToKeep) silenceCounter++
                            else shouldWrite = false
                        } else {
                            silenceCounter = 0
                        }
                    }

                    if (shouldWrite) {
                        val bytes = ByteBuffer.allocate(readResult * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until readResult) bytes.putShort(buffer[i])
                        val byteArray = bytes.array()
                        outputStream.write(byteArray)
                        totalBytesWritten += byteArray.size

                        val bytesPerSec = SAMPLE_RATE * 2
                        recordingDuration = (totalBytesWritten * 1000) / bytesPerSec
                    }
                }
            }

            try { outputStream.close() } catch (_: Exception) {}
            if (currentFile != null && currentFile!!.exists()) writeWavHeader(currentFile!!, totalBytesWritten)
        }
    }

    private fun writeWavHeader(file: File, totalAudioLen: Long) {
        try {
            val randomAccessFile = RandomAccessFile(file, "rw")
            randomAccessFile.seek(0)
            val totalDataLen = totalAudioLen + 36
            val longSampleRate = SAMPLE_RATE.toLong()
            val channels = 1
            val byteRate = (SAMPLE_RATE * channels * 16 / 8).toLong()

            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte(); header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0
            header[22] = channels.toByte(); header[23] = 0
            header[24] = (longSampleRate and 0xff).toByte(); header[25] = ((longSampleRate shr 8) and 0xff).toByte(); header[26] = ((longSampleRate shr 16) and 0xff).toByte(); header[27] = ((longSampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte(); header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * 16 / 8).toByte(); header[33] = 0
            header[34] = 16; header[35] = 0
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen shr 8) and 0xff).toByte(); header[42] = ((totalAudioLen shr 16) and 0xff).toByte(); header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            randomAccessFile.write(header)
            randomAccessFile.close()
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun pauseRecording() { if (isRecording) isPaused = true }
    fun resumeRecording() { if (isRecording) isPaused = false }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        isPaused = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        releaseAudioFocus(getApplication())

        viewModelScope.launch {
            delay(200)
            refreshRecordings()
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Запись сохранена", Toast.LENGTH_SHORT).show()
                currentAmplitude = 0
                recordingDuration = 0L
            }
        }
    }

    private fun cleanupRecorder() {
        isRecording = false
        isPaused = false
        recordingJob?.cancel()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        currentAmplitude = 0
        recordingDuration = 0L
        releaseAudioFocus(getApplication())
    }

    fun deleteRecording(item: RecordingItem) {
        if (currentPlayback?.file?.absolutePath == item.file.absolutePath) {
            stopPlayback()
        }
        try { if (item.file.delete()) refreshRecordings() } catch (e: Exception) { e.printStackTrace() }
    }

    // --- ПЕРЕИМЕНОВАНИЕ С ЗАДЕРЖКОЙ ---
    fun renameRecording(item: RecordingItem, newName: String) {
        val cleanName = newName.trim()
        val context = getApplication<Application>()
        if (cleanName.isEmpty()) {
            Toast.makeText(context, "Имя не может быть пустым", Toast.LENGTH_SHORT).show()
            return
        }

        val ext = item.file.extension
        val finalName = if (cleanName.endsWith(".$ext", ignoreCase = true)) cleanName else "$cleanName.$ext"

        val newFile = File(item.file.parentFile, finalName)
        if (newFile.exists()) {
            Toast.makeText(context, "Файл с таким именем уже существует", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentPlayback?.file?.absolutePath == item.file.absolutePath) {
            stopPlayback()
        }

        // Запускаем в корутине, чтобы дать время плееру остановиться
        viewModelScope.launch(Dispatchers.IO) {
            delay(500) // Ждем 0.5 сек, пока MusicViewModel освободит файл

            val success = item.file.renameTo(newFile)

            withContext(Dispatchers.Main) {
                if (success) {
                    refreshRecordings()
                    Toast.makeText(context, "Переименовано", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Ошибка переименования (Файл занят?)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun shareRecording(context: Context, item: RecordingItem) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", item.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Поделиться записью"))
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка шаринга", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAudioFocus(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = focusRequest
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseAudioFocus(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    override fun onCleared() {
        PlaybackCoordinator.clearRecorder()
        stopRecording()
        playbackPlayer?.release()
        playbackPlayer = null
        super.onCleared()
    }
}
