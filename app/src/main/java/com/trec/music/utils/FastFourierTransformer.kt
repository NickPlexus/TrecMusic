// FFT utility used by vocal-removal processing.

package com.trec.music.utils

import kotlin.math.*

/**
 * In-place complex FFT data layout: [real0, imag0, real1, imag1, ...].
 */
class FastFourierTransformer(private val n: Int) {

    private val m = (ln(n.toDouble()) / ln(2.0)).toInt()

    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)
    private val bitReverse = IntArray(n)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2" }

        for (i in 0 until n / 2) {
            cosTable[i] = cos(-2 * PI * i / n)
            sinTable[i] = sin(-2 * PI * i / n)
        }

        for (i in 0 until n) {
            var j = i
            var k = 0
            for (l in 0 until m) {
                k = (k shl 1) or (j and 1)
                j = j shr 1
            }
            bitReverse[i] = k
        }
    }

    /**
     */
    fun transform(data: DoubleArray) {
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (j > i) {
                // Swap Real parts
                val tr = data[2 * i]
                data[2 * i] = data[2 * j]
                data[2 * j] = tr
                // Swap Imaginary parts
                val ti = data[2 * i + 1]
                data[2 * i + 1] = data[2 * j + 1]
                data[2 * j + 1] = ti
            }
        }

        var k = 1
        while (k < n) {
            val step = k shl 1 // k * 2

            val tableStep = n / step

            for (i in 0 until k) {
                val tableIdx = i * tableStep
                val wr = cosTable[tableIdx]
                val wi = sinTable[tableIdx]

                for (j in i until n step step) {
                    val j2 = j + k

                    val idxJ = 2 * j
                    val idxJ2 = 2 * j2

                    val realJ2 = data[idxJ2]
                    val imagJ2 = data[idxJ2 + 1]

                    val tr = wr * realJ2 - wi * imagJ2
                    val ti = wr * imagJ2 + wi * realJ2

                    val realJ = data[idxJ]
                    val imagJ = data[idxJ + 1]

                    data[idxJ2] = realJ - tr
                    data[idxJ2 + 1] = imagJ - ti
                    data[idxJ] += tr
                    data[idxJ + 1] += ti
                }
            }
            k = step
        }
    }

    /**
     */
    fun inverseTransform(data: DoubleArray) {
        for (i in 1 until 2 * n step 2) {
            data[i] = -data[i]
        }

        transform(data)

        val scale = 1.0 / n
        for (i in 0 until 2 * n) {
            data[i] *= scale
            if (i % 2 != 0) {
                data[i] = -data[i]
            }
        }
    }
}

