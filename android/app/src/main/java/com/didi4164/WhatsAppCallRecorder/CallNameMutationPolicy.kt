package com.didi4164.WhatsAppCallRecorder

/** A setting write cannot enable metadata collection in the background or change a live recording. */
object CallNameMutationPolicy {
  fun rejection(validActivity: Boolean, resumed: Boolean, focused: Boolean, recordingBusy: Boolean): String? = when {
    !validActivity || !resumed || !focused -> "FOREGROUND_REQUIRED"
    recordingBusy -> "RECORDING_BUSY"
    else -> null
  }
}
