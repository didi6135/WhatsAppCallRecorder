package com.didi4164.WhatsAppCallRecorder

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFile {
  const val SAMPLE_RATE = 16000
  const val HEADER_SIZE = 44

  fun header(dataBytes: Long, channels: Int = 1): ByteArray {
    require(channels in 1..2)
    require(dataBytes in 0..0xfffffff0L && dataBytes % (2L * channels) == 0L)
    return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((dataBytes + 36).toInt())
      put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
      putShort(1.toShort()); putShort(channels.toShort()); putInt(SAMPLE_RATE)
      putInt(SAMPLE_RATE * 2 * channels); putShort((2 * channels).toShort()); putShort(16.toShort())
      put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
    }.array()
  }

  fun finalize(file: RandomAccessFile, channels: Int = 1) {
    val alignment = 2L * channels
    val bytes = ((file.length() - HEADER_SIZE).coerceAtLeast(0L) / alignment) * alignment
    file.setLength(bytes + HEADER_SIZE)
    file.seek(0); file.write(header(bytes, channels)); file.fd.sync()
  }
}
