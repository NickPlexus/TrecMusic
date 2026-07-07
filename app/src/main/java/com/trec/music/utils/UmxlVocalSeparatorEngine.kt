// utils/UmxlVocalSeparatorEngine.kt
//
// On-device neural vocal separation (UMX-L / UMXL spectrogram model) using ONNX Runtime.
//
// Pipeline (mirrors open-unmix Separator defaults):
// - Decode audio -> PCM16
// - Resample to 44100 Hz if needed
// - STFT (n_fft=4096, hop=1024, Hann periodic window, center=true, pad_mode=reflect, onesided=true)
// - Magnitude -> ONNX model -> estimated vocal magnitude
// - Reconstruct vocals with mixture phase -> iSTFT -> vocals waveform
// - Instrumental = mixture - vocals
//
package com.trec.music.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

object UmxlVocalSeparatorEngine {

    enum class OutputMode {
        INSTRUMENTAL,
        ACAPELLA
    }

    private const val TAG = "UmxlVocalSep"

    // Open-Unmix parameters (see openunmix.model.Separator + openunmix.transforms.TorchSTFT)
    private const val TARGET_SAMPLE_RATE = 44100
    private const val CHANNELS = 2
    private const val N_FFT = 4096
    private const val HOP = 1024
    private const val NB_BINS = N_FFT / 2 + 1 // onesided
    private const val PAD = N_FFT / 2 // center=True

    // Inference chunking (time frames)
    // NOTE: This exported UMXL ONNX graph only accepts exactly 100 frames per call.
    // Using any other value (e.g. 256) throws ORT reshape errors at runtime.
    private const val CHUNK_FRAMES = 100

    private const val EPS = 1e-9f
    private const val MAX_PROCESSING_SECONDS = 12 * 60

    private const val MODEL_FILE_NAME = "umxl_vocals.onnx"

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    suspend fun generateInstrumental(
        context: Context,
        sourceUri: Uri,
        outputFile: File,
        outputMode: OutputMode = OutputMode.INSTRUMENTAL,
        removalStrength: Float = 1.0f,
        vocalBoost: Float = 1.15f
    ): InstrumentalGenerationResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val tempInputRaw = File(context.cacheDir, "umxl_in_${System.currentTimeMillis()}.raw")

        try {
            val decode = decodeToPcm16RawFile(context, sourceUri, tempInputRaw)
                ?: return@withContext InstrumentalGenerationResult(false, "Не удалось декодировать аудио")

            if (decode.channels <= 0) {
                return@withContext InstrumentalGenerationResult(
                    success = false,
                    error = "Не удалось определить число каналов аудио"
                )
            }

            if (decode.channels != CHANNELS) {
                Log.w(TAG, "Source channels=${decode.channels}; converting to stereo for UMXL")
            }

            val estimatedFrames = tempInputRaw.length() / (decode.channels.toLong() * 2L)
            val estimatedSeconds = if (decode.sampleRate > 0) {
                estimatedFrames.toDouble() / decode.sampleRate.toDouble()
            } else {
                0.0
            }
            if (estimatedSeconds > MAX_PROCESSING_SECONDS) {
                return@withContext InstrumentalGenerationResult(
                    success = false,
                    error = "Трек слишком длинный для AI-сепарации на устройстве (>12 минут). Разделите трек или используйте более короткий фрагмент."
                )
            }

            val (rawL, rawR) = readPcm16ToStereo(tempInputRaw, decode.channels)

            val mixL: FloatArray
            val mixR: FloatArray
            if (decode.sampleRate == TARGET_SAMPLE_RATE) {
                mixL = rawL
                mixR = rawR
            } else {
                mixL = resampleLinear(rawL, decode.sampleRate, TARGET_SAMPLE_RATE)
                mixR = resampleLinear(rawR, decode.sampleRate, TARGET_SAMPLE_RATE)
            }

            if (mixL.isEmpty() || mixR.isEmpty()) {
                return@withContext InstrumentalGenerationResult(false, "Пустой PCM после декодирования")
            }

            // Ensure clean output on retry.
            if (outputFile.exists()) outputFile.delete()

            val modelFile = try {
                ensureModelFile(context)
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось подготовить AI-модель (UMXL)"
                return@withContext InstrumentalGenerationResult(false, msg)
            }

            val error = runUmxlVocalSeparation(
                modelFile = modelFile,
                mixL = mixL,
                mixR = mixR,
                outputWav = outputFile,
                outputMode = outputMode,
                removalStrength = removalStrength,
                vocalBoost = vocalBoost
            )

            if (error != null) {
                if (outputFile.exists()) outputFile.delete()
                return@withContext InstrumentalGenerationResult(false, error)
            }

            val elapsed = System.currentTimeMillis() - startMs
            InstrumentalGenerationResult(
                success = true,
                vocalDetected = true,
                processingTime = elapsed,
                methodUsed = if (outputMode == OutputMode.ACAPELLA) {
                    "AI UMXL Vocals (ONNX Runtime)"
                } else {
                    "AI UMXL Instrumental (ONNX Runtime)"
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "AI vocal separation failed", e)
            if (outputFile.exists()) outputFile.delete()
            InstrumentalGenerationResult(false, "Ошибка: ${e.message}")
        } finally {
            try { tempInputRaw.delete() } catch (_: Exception) {}
        }
    }

    private fun runUmxlVocalSeparation(
        modelFile: File,
        mixL: FloatArray,
        mixR: FloatArray,
        outputWav: File,
        outputMode: OutputMode,
        removalStrength: Float,
        vocalBoost: Float
    ): String? {
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            Log.w(TAG, "Model missing or too small: ${modelFile.absolutePath}")
            return "AI-модель не найдена или повреждена"
        }

        val totalSamples = minOf(mixL.size, mixR.size)
        if (totalSamples <= 0) return "Пустой аудиопоток после декодирования"
        val safeStrength = removalStrength.coerceIn(0f, 1.5f)
        val safeBoost = vocalBoost.coerceIn(1f, 2f)

        val totalPcmBytes = totalSamples.toLong() * CHANNELS.toLong() * 2L

        val window = createHannWindowPeriodic(N_FFT)
        val fft = FastFourierTransformer(N_FFT)

        var session: OrtSession? = null
        val sessionOptions = OrtSession.SessionOptions()

        try {
            session = try {
                ortEnv.createSession(modelFile.absolutePath, sessionOptions)
            } catch (e: OrtException) {
                // If the model file is corrupted, remove it so the next run can restore it from assets.
                try { modelFile.delete() } catch (_: Exception) {}
                return "AI-модель UMXL повреждена. Файл удален — попробуйте еще раз."
            }

            val inputName = session.inputNames.firstOrNull()
                ?: throw OrtException("UMXL ONNX session has no inputs")

            val nFrames = 1 + (totalSamples / HOP) // center=True => frames = 1 + floor(L / hop)

            // OLA buffers for vocals reconstruction (padded domain), plus window^2 envelope for normalization.
            val olaL = DoubleArray(N_FFT)
            val olaR = DoubleArray(N_FFT)
            val olaW = DoubleArray(N_FFT)

            val fftBufL = DoubleArray(N_FFT * 2)
            val fftBufR = DoubleArray(N_FFT * 2)
            val specL = DoubleArray(N_FFT * 2)
            val specR = DoubleArray(N_FFT * 2)

            val phaseRe = FloatArray(CHUNK_FRAMES * NB_BINS * CHANNELS)
            val phaseIm = FloatArray(CHUNK_FRAMES * NB_BINS * CHANNELS)

            // Output writing
            var writtenSamples = 0 // samples written to WAV (0..totalSamples)
            FileOutputStream(outputWav).use { fos ->
                WavUtils.writeWavHeader(fos, TARGET_SAMPLE_RATE, CHANNELS, totalPcmBytes)
                BufferedOutputStream(fos).use { out ->
                    var globalFrame = 0
                    var paddedWritten = 0 // samples in padded domain advanced by hop emissions

                    while (globalFrame < nFrames) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")

                        val framesThis = minOf(CHUNK_FRAMES, nFrames - globalFrame)

                        val inputMag = FloatArray(CHUNK_FRAMES * NB_BINS * CHANNELS)

                        // Fill chunk (pad remaining frames with zeros)
                        // Layout: (1, C, FREQ, TIME) in row-major => TIME is fastest
                        for (f in 0 until CHUNK_FRAMES) {
                            if (f < framesThis) {
                                val frameIndex = globalFrame + f

                                fillStftFrame(
                                    fftBuf = fftBufL,
                                    signal = mixL,
                                    frameIndex = frameIndex,
                                    window = window
                                )
                                fft.transform(fftBufL)

                                fillStftFrame(
                                    fftBuf = fftBufR,
                                    signal = mixR,
                                    frameIndex = frameIndex,
                                    window = window
                                )
                                fft.transform(fftBufR)

                                for (bin in 0 until NB_BINS) {
                                    // L
                                    val reL = fftBufL[2 * bin]
                                    val imL = fftBufL[2 * bin + 1]
                                    val magL = hypot(reL, imL).toFloat()
                                    val idxL = tensorIndex(channel = 0, bin = bin, frame = f)
                                    inputMag[idxL] = magL
                                    val invL = if (magL > EPS) 1.0f / magL else 0.0f
                                    phaseRe[idxL] = (reL.toFloat() * invL)
                                    phaseIm[idxL] = (imL.toFloat() * invL)

                                    // R
                                    val reR = fftBufR[2 * bin]
                                    val imR = fftBufR[2 * bin + 1]
                                    val magR = hypot(reR, imR).toFloat()
                                    val idxR = tensorIndex(channel = 1, bin = bin, frame = f)
                                    inputMag[idxR] = magR
                                    val invR = if (magR > EPS) 1.0f / magR else 0.0f
                                    phaseRe[idxR] = (reR.toFloat() * invR)
                                    phaseIm[idxR] = (imR.toFloat() * invR)
                                }
                            } else {
                                // Padded frames: keep zeros + phase=0
                                // (inputBuffer is already zeroed for these indices because it's freshly allocated)
                            }
                        }

                        val inputShape = longArrayOf(1L, CHANNELS.toLong(), NB_BINS.toLong(), CHUNK_FRAMES.toLong())
                        val inputBuffer = ByteBuffer
                            .allocateDirect(inputMag.size * 4)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer()
                        inputBuffer.put(inputMag)
                        inputBuffer.rewind()
                        val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)

                        val outputMag = FloatArray(CHUNK_FRAMES * NB_BINS * CHANNELS)
                        var results: OrtSession.Result? = null
                        try {
                            results = session.run(mapOf(inputName to inputTensor))
                            val outTensor = results[0] as? OnnxTensor
                                ?: throw OrtException("UMXL ONNX output is not a tensor")
                            val fb = outTensor.floatBuffer
                            if (fb == null) throw OrtException("UMXL ONNX output has no float buffer")
                            fb.rewind()
                            fb.get(outputMag, 0, outputMag.size)
                        } finally {
                            try { results?.close() } catch (_: Exception) {}
                            try { inputTensor.close() } catch (_: Exception) {}
                        }

                        // Reconstruct vocals for actual frames, write instrumental samples as we go.
                        for (f in 0 until framesThis) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")

                            // Build full spectra for iFFT from onesided (mirrored for real signal)
                            buildSpectrumFromMagAndPhase(
                                outSpectrum = specL,
                                mag = outputMag,
                                phaseRe = phaseRe,
                                phaseIm = phaseIm,
                                frame = f,
                                channel = 0
                            )
                            buildSpectrumFromMagAndPhase(
                                outSpectrum = specR,
                                mag = outputMag,
                                phaseRe = phaseRe,
                                phaseIm = phaseIm,
                                frame = f,
                                channel = 1
                            )

                            fft.inverseTransform(specL)
                            fft.inverseTransform(specR)

                            // OLA add (synthesis window) + envelope for normalization.
                            for (i in 0 until N_FFT) {
                                val w = window[i].toDouble()
                                val sampleL = specL[2 * i] * w
                                val sampleR = specR[2 * i] * w
                                olaL[i] += sampleL
                                olaR[i] += sampleR
                                olaW[i] += w * w
                            }

                            // Emit next hop in padded domain.
                            if (writtenSamples < totalSamples) {
                                val hopBytes = HOP * CHANNELS * 2
                                val writeBuf = ByteArray(hopBytes)
                                var bi = 0

                                for (i in 0 until HOP) {
                                    val paddedPos = paddedWritten + i
                                    val outPos = paddedPos - PAD
                                    if (outPos < 0 || outPos >= totalSamples) continue

                                    val denom = olaW[i].takeIf { it > 1e-12 } ?: 1e-12
                                    val vocalL = (olaL[i] / denom).toFloat()
                                    val vocalR = (olaR[i] / denom).toFloat()

                                    val outL = if (outputMode == OutputMode.ACAPELLA) {
                                        (vocalL * safeBoost).coerceIn(-1.0f, 1.0f)
                                    } else {
                                        (mixL[outPos] - vocalL * safeStrength).coerceIn(-1.0f, 1.0f)
                                    }
                                    val outR = if (outputMode == OutputMode.ACAPELLA) {
                                        (vocalR * safeBoost).coerceIn(-1.0f, 1.0f)
                                    } else {
                                        (mixR[outPos] - vocalR * safeStrength).coerceIn(-1.0f, 1.0f)
                                    }

                                    val sL = (outL * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                                    val sR = (outR * 32767.0f).roundToInt().coerceIn(-32768, 32767)

                                    writeBuf[bi] = (sL and 0xFF).toByte()
                                    writeBuf[bi + 1] = ((sL shr 8) and 0xFF).toByte()
                                    writeBuf[bi + 2] = (sR and 0xFF).toByte()
                                    writeBuf[bi + 3] = ((sR shr 8) and 0xFF).toByte()
                                    bi += 4
                                    writtenSamples++
                                    if (writtenSamples >= totalSamples) break
                                }

                                if (bi > 0) out.write(writeBuf, 0, bi)
                            }

                            paddedWritten += HOP
                            shiftLeftAndZeroTail(olaL, HOP)
                            shiftLeftAndZeroTail(olaR, HOP)
                            shiftLeftAndZeroTail(olaW, HOP)

                            if (writtenSamples >= totalSamples) break
                        }

                        globalFrame += framesThis
                    }

                    // Flush remaining tail (center=True cropping requires extra hops beyond nFrames)
                    var flushIters = 0
                    while (writtenSamples < totalSamples && flushIters < 8) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")

                        val hopBytes = HOP * CHANNELS * 2
                        val writeBuf = ByteArray(hopBytes)
                        var bi = 0

                        for (i in 0 until HOP) {
                            val paddedPos = paddedWritten + i
                            val outPos = paddedPos - PAD
                            if (outPos < 0 || outPos >= totalSamples) continue

                            val denom = olaW[i].takeIf { it > 1e-12 } ?: 1e-12
                            val vocalL = (olaL[i] / denom).toFloat()
                            val vocalR = (olaR[i] / denom).toFloat()

                            val outL = if (outputMode == OutputMode.ACAPELLA) {
                                (vocalL * safeBoost).coerceIn(-1.0f, 1.0f)
                            } else {
                                (mixL[outPos] - vocalL * safeStrength).coerceIn(-1.0f, 1.0f)
                            }
                            val outR = if (outputMode == OutputMode.ACAPELLA) {
                                (vocalR * safeBoost).coerceIn(-1.0f, 1.0f)
                            } else {
                                (mixR[outPos] - vocalR * safeStrength).coerceIn(-1.0f, 1.0f)
                            }

                            val sL = (outL * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                            val sR = (outR * 32767.0f).roundToInt().coerceIn(-32768, 32767)

                            writeBuf[bi] = (sL and 0xFF).toByte()
                            writeBuf[bi + 1] = ((sL shr 8) and 0xFF).toByte()
                            writeBuf[bi + 2] = (sR and 0xFF).toByte()
                            writeBuf[bi + 3] = ((sR shr 8) and 0xFF).toByte()
                            bi += 4
                            writtenSamples++
                            if (writtenSamples >= totalSamples) break
                        }

                        if (bi > 0) out.write(writeBuf, 0, bi)

                        paddedWritten += HOP
                        shiftLeftAndZeroTail(olaL, HOP)
                        shiftLeftAndZeroTail(olaR, HOP)
                        shiftLeftAndZeroTail(olaW, HOP)
                        flushIters++
                    }

                    out.flush()
                }
            }

            return if (writtenSamples == totalSamples) null else "AI-сепарация не завершилась (неполный вывод)"
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ONNX Runtime native load failed", e)
            return "ONNX Runtime не запускается на этом устройстве (ABI/Native libs): ${e.message}"
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during AI separation", e)
            return "Недостаточно памяти (RAM) для AI-сепарации UMXL"
        } catch (e: OrtException) {
            Log.e(TAG, "ONNX Runtime error", e)
            if (e.message?.contains("requested shape:{100,1,1024}", ignoreCase = true) == true) {
                return "Несовместимая UMXL-модель: требуется окно 100 кадров. Обновите APK/модель."
            }
            return "ONNX Runtime: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "UMXL processing failed", e)
            return "Ошибка AI-сепарации: ${e.message}"
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { sessionOptions.close() } catch (_: Exception) {}
        }
    }

    private fun buildSpectrumFromMagAndPhase(
        outSpectrum: DoubleArray,
        mag: FloatArray,
        phaseRe: FloatArray,
        phaseIm: FloatArray,
        frame: Int,
        channel: Int
    ) {
        // onesided bins
        for (bin in 0 until NB_BINS) {
            val idx = tensorIndex(channel = channel, bin = bin, frame = frame)
            val m = mag[idx].toDouble()
            val pr = phaseRe[idx].toDouble()
            val pi = phaseIm[idx].toDouble()
            outSpectrum[2 * bin] = m * pr
            outSpectrum[2 * bin + 1] = m * pi
        }

        // mirror for real iFFT
        for (bin in NB_BINS until N_FFT) {
            val mirror = N_FFT - bin
            outSpectrum[2 * bin] = outSpectrum[2 * mirror]
            outSpectrum[2 * bin + 1] = -outSpectrum[2 * mirror + 1]
        }
    }

    private fun tensorIndex(channel: Int, bin: Int, frame: Int): Int {
        // (C, FREQ, TIME) contiguous by TIME (frame)
        return ((channel * NB_BINS + bin) * CHUNK_FRAMES) + frame
    }

    private fun fillStftFrame(
        fftBuf: DoubleArray,
        signal: FloatArray,
        frameIndex: Int,
        window: FloatArray
    ) {
        val start = frameIndex * HOP - PAD
        val n = signal.size
        for (i in 0 until N_FFT) {
            val srcIdx = reflectIndex(start + i, n)
            val s = signal[srcIdx] * window[i]
            val bi = 2 * i
            fftBuf[bi] = s.toDouble()
            fftBuf[bi + 1] = 0.0
        }
    }

    private fun reflectIndex(index: Int, length: Int): Int {
        if (length <= 1) return 0
        var idx = index
        while (idx < 0 || idx >= length) {
            idx = if (idx < 0) -idx else 2 * length - idx - 2
        }
        return idx
    }

    private fun shiftLeftAndZeroTail(buffer: DoubleArray, shift: Int) {
        val keep = buffer.size - shift
        if (keep > 0) {
            System.arraycopy(buffer, shift, buffer, 0, keep)
        }
        for (i in keep until buffer.size) {
            buffer[i] = 0.0
        }
    }

    private fun createHannWindowPeriodic(n: Int): FloatArray {
        val w = FloatArray(n)
        val denom = n.toDouble().coerceAtLeast(1.0)
        for (i in 0 until n) {
            w[i] = (0.5 - 0.5 * cos(2.0 * PI * i / denom)).toFloat()
        }
        return w
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        if (input.isEmpty()) return FloatArray(0)

        val outLen = ((input.size.toLong() * dstRate.toLong() + (srcRate / 2L)) / srcRate.toLong()).toInt()
            .coerceAtLeast(1)
        val output = FloatArray(outLen)

        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceIn(0, input.size - 1)
            val frac = (srcPos - i0)
            output[i] = (input[i0] * (1.0 - frac) + input[i1] * frac).toFloat()
        }
        return output
    }

    private fun readPcm16ToStereo(file: File, channels: Int): Pair<FloatArray, FloatArray> {
        require(channels >= 1) { "Invalid channel count: $channels" }

        val bytesPerFrame = channels * 2
        val totalBytes = file.length() - (file.length() % bytesPerFrame)
        val totalFrames = (totalBytes / bytesPerFrame).toInt().coerceAtLeast(0)
        val l = FloatArray(totalFrames)
        val r = FloatArray(totalFrames)

        FileInputStream(file).use { fis ->
            BufferedInputStream(fis).use { input ->
                val buf = ByteArray(64 * 1024)
                var frame = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    var bi = 0
                    while (bi + (bytesPerFrame - 1) < read && frame < totalFrames) {
                        val sL = littleEndianShort(buf[bi], buf[bi + 1]).toInt()
                        val sR = if (channels >= 2) {
                            littleEndianShort(buf[bi + 2], buf[bi + 3]).toInt()
                        } else {
                            sL
                        }
                        l[frame] = sL / 32768.0f
                        r[frame] = sR / 32768.0f
                        frame++
                        bi += bytesPerFrame
                    }
                    if (frame >= totalFrames) break
                }
            }
        }

        return l to r
    }

    private fun littleEndianShort(lo: Byte, hi: Byte): Short {
        val loInt = lo.toInt() and 0xFF
        val hiInt = hi.toInt()
        return ((hiInt shl 8) or loInt).toShort()
    }

    // ==========================================
    // Offline model cache
    // ==========================================

    private const val MIN_MODEL_BYTES = 50_000_000L
    private const val MODEL_ASSET_PATH = "onnx/$MODEL_FILE_NAME"

    @Throws(IOException::class)
    private fun ensureModelFile(context: Context): File {
        val dir = File(context.filesDir, "onnx_models")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Не удалось создать папку для AI-модели")
        }

        val dest = File(dir, MODEL_FILE_NAME)
        if (isModelFileValid(dest)) return dest

        if (copyModelFromAssetsIfPresent(context, dest)) {
            return dest
        }

        throw IOException("AI-модель UMXL должна быть встроена в приложение: assets/$MODEL_ASSET_PATH")
    }

    private fun isModelFileValid(file: File): Boolean {
        return file.exists() && file.length() >= MIN_MODEL_BYTES
    }

    private fun copyModelFromAssetsIfPresent(context: Context, destFile: File): Boolean {
        return try {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                if (destFile.exists()) destFile.delete()
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            isModelFileValid(destFile)
        } catch (_: Exception) {
            false
        }
    }

    // ==========================================
    // Decode: Android MediaCodec -> PCM16 raw file
    // ==========================================

    private data class DecodeResult(val sampleRate: Int, val channels: Int, val file: File)

    private fun decodeToPcm16RawFile(context: Context, uri: Uri, destFile: File): DecodeResult? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var out: BufferedOutputStream? = null

        try {
            extractor = MediaExtractor()
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var trackIndex = -1
            var sampleRate = TARGET_SAMPLE_RATE
            var channels = CHANNELS

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    extractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    break
                }
            }

            if (trackIndex == -1) return null

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(mime)
            // Explicitly request PCM16 to keep downstream code deterministic across devices.
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Exception) {}
            codec.configure(format, null, null, 0)
            codec.start()

            out = BufferedOutputStream(FileOutputStream(destFile))
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 5_000L
            var eos = false
            var inputDone = false
            var outputSampleRate = sampleRate
            var outputChannels = channels
            var outputPcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!eos) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIdx)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(outBuffer.remaining())
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        when (outputPcmEncoding) {
                            AudioFormat.ENCODING_PCM_16BIT -> {
                                out?.write(chunk)
                            }
                            AudioFormat.ENCODING_PCM_FLOAT -> {
                                // Some decoders output float PCM; convert to PCM16 for the separator.
                                val bb = ByteBuffer.wrap(chunk).order(ByteOrder.nativeOrder())
                                val fb = bb.asFloatBuffer()
                                val sampleCount = fb.remaining()
                                val pcm16 = ByteArray(sampleCount * 2)
                                var bi = 0
                                while (fb.hasRemaining()) {
                                    val v = fb.get().coerceIn(-1.0f, 1.0f)
                                    val s = (v * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                                    pcm16[bi] = (s and 0xFF).toByte()
                                    pcm16[bi + 1] = ((s shr 8) and 0xFF).toByte()
                                    bi += 2
                                }
                                out?.write(pcm16)
                            }
                            else -> {
                                Log.w(TAG, "Unsupported PCM encoding from decoder: $outputPcmEncoding")
                                return null
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eos = true
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec.outputFormat
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputPcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }

            out?.flush()
            return DecodeResult(sampleRate = outputSampleRate, channels = outputChannels, file = destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            return null
        } finally {
            try { out?.close() } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
