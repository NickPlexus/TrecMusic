// utils/VocalRemoverUltimate.kt
//
// ТИП: DSP Engine (ULTIMATE VOCAL REMOVAL)
//
// НАЗНАЧЕНИЕ:
// 3. Multi-band processing
// 4. AI-based voice detection
//

package com.trec.music.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object VocalRemoverUltimate {
    
    private const val TAG = "VocalRemoverUltimate"
    private const val FFT_SIZE = 2048 // Стабильное качество
    private const val HOP_SIZE = FFT_SIZE / 8
    
    private val VOICE_FREQ_RANGE = 80f..3000f // comment normalized
    private val HARMONIC_THRESHOLD = 0.25f // comment normalized
    private val VOCAL_SUPPRESSION_STRENGTH = 0.9f // Усиленная подавление вокала
    private val STEREO_SEPARATION_FACTOR = 0.7f // comment normalized
    
    data class ProcessingResult(
        val success: Boolean,
        val error: String? = null,
        val vocalDetected: Boolean = false,
        val processingTime: Long = 0L
    )
    
    // Основная функция - УЛЬТИМАТИВНАЯ
    suspend fun generateInstrumental(
        context: Context,
        sourceUri: Uri,
        outputFile: File
    ): ProcessingResult = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        Log.d(TAG, ">>> ULTIMATE VOCAL REMOVER START <<<")
        
        val tempInputRaw = File(context.cacheDir, "vr_ult_in_${System.currentTimeMillis()}.raw")
        val tempOutputRaw = File(context.cacheDir, "vr_ult_out_${System.currentTimeMillis()}.raw")
        
        try {
            val decodeResult = decodeToRawFile(context, sourceUri, tempInputRaw)
                ?: return@withContext ProcessingResult(false, "Ошибка декодирования файла")
            
            if (decodeResult.channels != 2) {
                return@withContext ProcessingResult(false, "Нужен стерео-файл. Найдено каналов: ${decodeResult.channels}")
            }
            
            // ШАГ 2: AI Voice Detection
            val vocalDetected = detectVoicePresence(tempInputRaw, decodeResult)
            Log.d(TAG, "Voice detected: $vocalDetected")
            
            if (!vocalDetected) {
                Log.w(TAG, "No significant voice detected, applying minimal processing")
                tempInputRaw.copyTo(tempOutputRaw, overwrite = true)
            } else {
                val success = processUltimateVocalRemoval(tempInputRaw, tempOutputRaw, decodeResult)
                if (!success) {
                    return@withContext ProcessingResult(false, "Ошибка ультимативной обработки")
                }
            }
            
            buildWavFile(tempOutputRaw, outputFile, decodeResult.sampleRate)
            
            val processingTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ ULTIMATE SUCCESS! Time: ${processingTime}ms")
            
            ProcessingResult(
                success = true,
                vocalDetected = vocalDetected,
                processingTime = processingTime
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Critical Error", e)
            ProcessingResult(false, "Ошибка: ${e.message}")
        } finally {
            try { tempInputRaw.delete() } catch (_: Exception) {}
            try { tempOutputRaw.delete() } catch (_: Exception) {}
        }
    }
    
    // ==========================================
    // AI VOICE DETECTION
    // ==========================================
    
    private fun detectVoicePresence(inputRaw: File, decodeResult: DecodeResult): Boolean {
        Log.d(TAG, "Performing ULTIMATE voice detection...")
        
        val raf = RandomAccessFile(inputRaw, "r")
        val fft = FastFourierTransformer(2048)
        val window = createHanningWindow(2048)
        
        return try {
            val bufferSize = 2048 * 4 // 16-bit stereo
            val buffer = ByteArray(bufferSize)
            val fftBuffer = DoubleArray(2048 * 2)
            
            var voiceScore = 0.0
            var framesAnalyzed = 0
            var maxHarmonicStrength = 0.0
            
            val maxFrames = ((decodeResult.sampleRate * 15) / 512).coerceAtMost(1500)
            
            for (frame in 0 until maxFrames) {
                val bytesRead = raf.read(buffer)
                if (bytesRead <= 0) break
                
                bytesToComplex(buffer, bytesRead, fftBuffer, window)
                fft.transform(fftBuffer)
                
                val frameVoiceScore = analyzeVoiceCharacteristics(fftBuffer, decodeResult.sampleRate)
                voiceScore += frameVoiceScore
                framesAnalyzed++
                
                maxHarmonicStrength = maxOf(maxHarmonicStrength, frameVoiceScore)
                
                raf.seek(frame.toLong() * 512 * 4) // Skip ahead for faster analysis
            }
            
            val avgVoiceScore = voiceScore / framesAnalyzed
            
            val adaptiveThreshold = HARMONIC_THRESHOLD + (maxHarmonicStrength * 0.1)
            val hasVoice = avgVoiceScore > adaptiveThreshold
            
            Log.d(TAG, "Voice score: $avgVoiceScore, adaptive threshold: $adaptiveThreshold, max harmonic: $maxHarmonicStrength")
            Log.d(TAG, "Voice detected: $hasVoice")
            hasVoice
            
        } catch (e: Exception) {
            Log.e(TAG, "Voice detection error", e)
            true // Conservatively assume voice present
        } finally {
            raf.close()
        }
    }
    
    private fun analyzeVoiceCharacteristics(fftData: DoubleArray, sampleRate: Int): Double {
        val fftSize = fftData.size / 2
        var voiceScore = 0.0
        var binsAnalyzed = 0
        
        for (k in 0 until fftSize) {
            val freq = k * sampleRate.toFloat() / fftSize
            
            if (freq in VOICE_FREQ_RANGE) {
                val magnitude = sqrt(fftData[2*k] * fftData[2*k] + fftData[2*k+1] * fftData[2*k+1])
                
                val harmonicScore = detectHarmonics(fftData, k, fftSize)
                voiceScore += magnitude * harmonicScore
                binsAnalyzed++
            }
        }
        
        return if (binsAnalyzed > 0) voiceScore / binsAnalyzed else 0.0
    }
    
    private fun detectHarmonics(fftData: DoubleArray, fundamentalBin: Int, fftSize: Int): Double {
        var harmonicScore = 0.0
        val fundamentalMag = sqrt(fftData[2*fundamentalBin].pow(2) + fftData[2*fundamentalBin+1].pow(2))
        
        if (fundamentalMag < 1e-6) return 0.0
        
        for (harmonic in 2..4) {
            val harmonicBin = (fundamentalBin * harmonic).coerceAtMost(fftSize - 1)
            val harmonicMag = sqrt(fftData[2*harmonicBin].pow(2) + fftData[2*harmonicBin+1].pow(2))
            
            val ratio = harmonicMag / fundamentalMag
            if (ratio > 0.1) harmonicScore += ratio
        }
        
        return harmonicScore / 3.0
    }
    
    // ==========================================
    // ULTIMATE VOCAL REMOVAL ALGORITHM
    // ==========================================
    
    private fun processUltimateVocalRemoval(
        inputRaw: File, outputRaw: File, decodeResult: DecodeResult
    ): Boolean {
        Log.d(TAG, "Starting ULTIMATE vocal removal...")
        Log.d(TAG, "Input file: ${inputRaw.absolutePath}, size: ${inputRaw.length()}")
        Log.d(TAG, "FFT_SIZE: $FFT_SIZE, HOP_SIZE: $HOP_SIZE")
        
        val rafIn = RandomAccessFile(inputRaw, "r")
        val rafOut = RandomAccessFile(outputRaw, "rw")
        
        return try {
            val fft = FastFourierTransformer(FFT_SIZE)
            val window = createKaiserWindow(FFT_SIZE, 8.0)
            
            if (fft == null || window == null) {
                Log.e(TAG, "Failed to create FFT or window")
                return false
            }
            
            rafOut.setLength(inputRaw.length())
            
            val numSamplesTotal = inputRaw.length() / 4 // 16-bit stereo = 4 bytes per sample
            val numFrames = (numSamplesTotal - FFT_SIZE) / HOP_SIZE
            
            Log.d(TAG, "Processing $numFrames frames with ULTIMATE algorithm...")
            
            val frameSizeBytes = FFT_SIZE * 4 // 16-bit stereo
            val readBufferBytes = ByteArray(frameSizeBytes)
            val fftBufferL = DoubleArray(FFT_SIZE * 2)
            val fftBufferR = DoubleArray(FFT_SIZE * 2)
            val outReadBufferBytes = ByteArray(frameSizeBytes)
            val outWriteByteBuffer = ByteBuffer.allocate(frameSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
            
            val overlapGain = 0.85
            var currentHopIndex = 0L
            val reportStep = (numFrames / 10).coerceAtLeast(1)
            
            while (currentHopIndex < numFrames) {
                if (Thread.currentThread().isInterrupted) break
                
                val seekPos = currentHopIndex * HOP_SIZE * frameSizeBytes
                
                rafIn.seek(seekPos)
                val bytesReadInput = rafIn.read(readBufferBytes)
                if (bytesReadInput < readBufferBytes.size) {
                    readBufferBytes.fill(0, bytesReadInput, readBufferBytes.size)
                }
                
                if (currentHopIndex % 100 == 0L) {
                    Log.d(TAG, "Processing frame $currentHopIndex/$numFrames, bytesRead: $bytesReadInput")
                }
                
                val bbIn = ByteBuffer.wrap(readBufferBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (j in 0 until HOP_SIZE) {
                    val l = bbIn.short.toDouble() / 32768.0
                    val r = bbIn.short.toDouble() / 32768.0
                    val winVal = window[j]
                    
                    fftBufferL[2 * j] = l * winVal
                    fftBufferR[2 * j] = r * winVal
                    fftBufferL[2 * j + 1] = 0.0
                    fftBufferR[2 * j + 1] = 0.0
                }
                
                // FFT
                fft.transform(fftBufferL)
                fft.transform(fftBufferR)
                
                try {
                    applyUltimateMasking(fftBufferL, fftBufferR, decodeResult.sampleRate)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in masking: ${e.message}", e)
                    return false
                }
                
                // IFFT
                fft.inverseTransform(fftBufferL)
                fft.inverseTransform(fftBufferR)
                
                // Overlap-Add
                rafOut.seek(seekPos)
                val bytesReadOutput = rafOut.read(outReadBufferBytes)
                val bbOutRead = ByteBuffer.wrap(outReadBufferBytes).order(ByteOrder.LITTLE_ENDIAN)
                
                outWriteByteBuffer.clear()
                for (j in 0 until HOP_SIZE) {
                    val offsetBytes = j * 4 // 16-bit stereo = 4 bytes per sample
                    
                    bbOutRead.position(offsetBytes)
                    val oldL = if (offsetBytes < bytesReadOutput) bbOutRead.short.toDouble() else 0.0
                    
                    bbOutRead.position(offsetBytes + 2)
                    val oldR = if (offsetBytes + 2 < bytesReadOutput) bbOutRead.short.toDouble() else 0.0
                    
                    val newL = fftBufferL[2 * j] * window[j] * overlapGain
                    val newR = fftBufferR[2 * j] * window[j] * overlapGain
                    
                    var resL = (oldL + (newL * 32768.0)).toInt().coerceIn(-32768, 32767)
                    var resR = (oldR + (newR * 32768.0)).toInt().coerceIn(-32768, 32767)
                    
                    outWriteByteBuffer.putShort(resL.toShort())
                    outWriteByteBuffer.putShort(resR.toShort())
                }
                
                rafOut.seek(seekPos)
                rafOut.write(outWriteByteBuffer.array())
                
                currentHopIndex++
                if (currentHopIndex % reportStep == 0L) {
                    Log.v(TAG, "Progress: ${(currentHopIndex * 100) / numFrames}%")
                }
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Ultimate processing error", e)
            false
        } finally {
            try { rafIn.close() } catch (_: Exception) {}
            try { rafOut.close() } catch (_: Exception) {}
        }
    }
    
    // ==========================================
    // ULTIMATE MASKING ALGORITHM
    // ==========================================
    
    private fun applyUltimateMasking(
        left: DoubleArray, right: DoubleArray, sampleRate: Int
    ) {
        val fftSize = left.size / 2
        
        for (k in 0 until fftSize) {
            val freqBin = k * 2
            val frequency = k * sampleRate.toDouble() / fftSize
            
            // Получаем комплексные амплитуды
            val leftReal = left[freqBin]
            val leftImag = left[freqBin + 1]
            val rightReal = right[freqBin]
            val rightImag = right[freqBin + 1]
            
            val leftMag = sqrt(leftReal * leftReal + leftImag * leftImag)
            val rightMag = sqrt(rightReal * rightReal + rightImag * rightImag)
            
            if (leftMag < 1e-10 && rightMag < 1e-10) continue
            
            // Multi-band analysis
            val gain = calculateUltimateGain(leftReal, leftImag, rightReal, rightImag, frequency, leftMag, rightMag)
            
            left[freqBin] *= gain
            left[freqBin + 1] *= gain
            right[freqBin] *= gain
            right[freqBin + 1] *= gain
        }
    }
    
    private fun calculateUltimateGain(
        leftReal: Double, leftImag: Double,
        rightReal: Double, rightImag: Double,
        frequency: Double, leftMag: Double, rightMag: Double
    ): Double {
        // 1. Enhanced center channel detection
        val diffReal = leftReal - rightReal
        val diffImag = leftImag - rightImag
        val diffMag = sqrt(diffReal * diffReal + diffImag * diffImag)
        val sumMag = leftMag + rightMag
        val pan = if (sumMag > 0) diffMag / sumMag else 0.0
        
        // 2. Enhanced voice detection with multiple frequency bands
        val voiceProbability = when {
            frequency < 80 -> 0.0 // Суб-бас не голос
            frequency < 200 -> 0.2 // Очень низкий голос
            frequency < 400 -> 0.6 // Низкий голос
            frequency < 800 -> 0.9 // Основной диапазон голоса
            frequency < 1500 -> 1.0 // comment normalized
            frequency < 3000 -> 0.8 // comment normalized
            frequency < 6000 -> 0.4 // Очень высокие
            else -> 0.1 // comment normalized
        }
        
        // 3. Advanced spectral complexity analysis
        val spectralComplexity = calculateSpectralComplexity(leftReal, leftImag, rightReal, rightImag)
        
        val centerSuppression = if (pan < 0.15) {
            (pan / 0.15).pow(6.0) // Увеличена степень
        } else 1.0
        
        // Усиленное подавление вокала
        val voiceSuppression = 1.0 - (voiceProbability * VOCAL_SUPPRESSION_STRENGTH)
        val complexityFactor = 1.0 - (spectralComplexity * 0.4) // Усилен эффект
        
        val preserveFactor = when {
            frequency < 80 -> 0.9 // Суб-бас
            frequency < 200 -> 0.8 // Бас
            frequency < 400 -> 0.7 // Низкие частоты
            frequency < 800 -> 0.5 // comment normalized
            frequency < 2000 -> 0.3 // comment normalized
            frequency < 4000 -> 0.2 // Высокие
            else -> 0.1 // comment normalized
        }
        
        val stereoEnhancement = if (frequency > 500) STEREO_SEPARATION_FACTOR else 1.0
        
        val finalGain = centerSuppression * voiceSuppression * complexityFactor * preserveFactor * stereoEnhancement.toDouble()
        
        return finalGain.coerceIn(0.02, 1.0) // Увеличен диапазон
    }
    
    private fun calculateSpectralComplexity(
        leftReal: Double, leftImag: Double,
        rightReal: Double, rightImag: Double
    ): Double {
        val leftPhase = atan2(leftImag, leftReal)
        val rightPhase = atan2(rightImag, rightReal)
        val phaseDiff = abs(leftPhase - rightPhase)
        
        // Голос обычно имеет стабильные фазовые отношения
        return when {
            phaseDiff < 0.1 -> 0.8 // Очень похоже на голос
            phaseDiff < 0.5 -> 0.5 // Возможно голос
            else -> 0.2 // Не похоже на голос
        }
    }
    
    // ==========================================
    // Вспомогательные функции
    // ==========================================
    
    private fun bytesToComplex(bytes: ByteArray, bytesRead: Int, complex: DoubleArray, window: DoubleArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = complex.size / 2
        
        for (i in 0 until size) {
            val byteIndex = i * 4
            val l = if (byteIndex + 1 < bytesRead) buffer.short.toDouble() / 32768.0 else 0.0
            val r = if (byteIndex + 3 < bytesRead) buffer.short.toDouble() / 32768.0 else 0.0
            
            val winVal = window[i]
            complex[2 * i] = l * winVal
            complex[2 * i + 1] = 0.0
        }
    }
    
    private fun createHanningWindow(size: Int): DoubleArray {
        val window = DoubleArray(size)
        for (i in 0 until size) {
            window[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))
        }
        return window
    }
    
    private fun createKaiserWindow(size: Int, beta: Double): DoubleArray {
        val window = DoubleArray(size)
        val i0a = modifiedBesselFunction0(beta)
        
        for (i in 0 until size) {
            val alpha = (2.0 * i / (size - 1) - 1.0) * beta
            window[i] = modifiedBesselFunction0(alpha) / i0a
        }
        return window
    }
    
    private fun modifiedBesselFunction0(x: Double): Double {
        var sum = 0.0
        var factorial = 1.0
        var xPow = 1.0
        val x2 = x * x / 4.0
        
        for (k in 0 until 20) {
            sum += xPow / (factorial * factorial)
            factorial *= (k + 1).toDouble()
            xPow *= x2
        }
        return sum
    }
    
    // ==========================================
    // ==========================================
    
    private data class DecodeResult(val sampleRate: Int, val channels: Int, val file: File)
    
    private fun decodeToRawFile(context: Context, uri: Uri, destFile: File): DecodeResult? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var fos: BufferedOutputStream? = null
        
        try {
            extractor = MediaExtractor()
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            
            var trackIndex = -1
            var sampleRate = 44100
            var channels = 2
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    extractor.selectTrack(i)
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    break
                }
            }
            
            if (trackIndex == -1) return null
            
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            fos = BufferedOutputStream(FileOutputStream(destFile))
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            val kTimeOutUs = 5000L
            
            while (!isEOS) {
                val inIdx = codec.dequeueInputBuffer(kTimeOutUs)
                if (inIdx >= 0) {
                    val buffer = codec.getInputBuffer(inIdx)
                    val size = extractor.readSampleData(buffer!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, kTimeOutUs)
                if (outIdx >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIdx)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        fos.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                    }
                }
            }
            
            fos.flush()
            return DecodeResult(sampleRate, channels, destFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Decode Error", e)
            return null
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
    
    private fun buildWavFile(inputRaw: File, outputFile: File, sampleRate: Int) {
        Log.d(TAG, "Building WAV file: ${outputFile.name}, sampleRate: $sampleRate")
        
        try {
            val outputStream = FileOutputStream(outputFile)
            WavUtils.writeWavHeader(outputStream, sampleRate, 2, inputRaw.length())
            
            val inputStream = FileInputStream(inputRaw)
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            inputStream.close()
            outputStream.close()
            
            Log.d(TAG, "WAV file built successfully, size: ${outputFile.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error building WAV file: ${e.message}", e)
            throw e
        }
    }
}


