package com.didi4164.WhatsAppCallRecorder

/** Sound detection is not evidence that both participants were recorded. */
object CaptureQuality {
  const val SOUND_THRESHOLD = 0.003

  fun classify(soundMs: Long, wasSilenced: Boolean, failed: Boolean): String = when {
    soundMs < 200L -> "silent"
    wasSilenced || failed -> "interrupted"
    else -> "captured"
  }
}
