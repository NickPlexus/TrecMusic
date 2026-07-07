package com.trec.music.utils

data class InstrumentalGenerationResult(
    val success: Boolean,
    val error: String? = null,
    val vocalDetected: Boolean = false,
    val processingTime: Long = 0L,
    val methodUsed: String = ""
)

