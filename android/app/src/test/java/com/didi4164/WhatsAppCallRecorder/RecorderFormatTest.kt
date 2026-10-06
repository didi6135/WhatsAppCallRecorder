package com.didi4164.WhatsAppCallRecorder

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecorderFormatTest {
  @Test fun pcmHeaderHasCorrectSizeRateAndDuration() {
    val header = WavFile.header(32000L)
    val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(44, header.size)
    assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
    assertEquals(32036, fields.getInt(4))
    assertEquals(16000, fields.getInt(24))
    assertEquals(32000, fields.getInt(28))
    assertEquals(1, fields.getShort(22).toInt())
    assertEquals(16, fields.getShort(34).toInt())
    assertEquals(32000, fields.getInt(40))
  }
  @Test fun silenceAndInterruptedCaptureCannotBeCalledCaptured() {
    assertEquals("silent", CaptureQuality.classify(0, false, false))
    assertEquals("silent", CaptureQuality.classify(199, true, true))
    assertEquals("interrupted", CaptureQuality.classify(200, true, false))
    assertEquals("interrupted", CaptureQuality.classify(1000, false, true))
    assertEquals("captured", CaptureQuality.classify(1000, false, false))
  }
  @Test(expected = IllegalArgumentException::class) fun rejectIncompletePcmSample() { WavFile.header(3) }
  @Test fun usbStereoHeaderRetainsSeparateChannels() {
    val fields = ByteBuffer.wrap(WavFile.header(64000L, 2)).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(2, fields.getShort(22).toInt())
    assertEquals(64000, fields.getInt(28))
    assertEquals(4, fields.getShort(32).toInt())
    assertEquals(64000, fields.getInt(40))
  }
}
