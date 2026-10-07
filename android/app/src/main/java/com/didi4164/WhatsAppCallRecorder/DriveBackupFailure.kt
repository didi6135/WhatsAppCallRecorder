package com.didi4164.WhatsAppCallRecorder

class DriveBackupFailure(val code: String, val retryable: Boolean = false, val auth: Boolean = false) : Exception(code)
