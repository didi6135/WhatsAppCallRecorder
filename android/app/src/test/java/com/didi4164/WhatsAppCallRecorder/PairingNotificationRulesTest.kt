package com.didi4164.WhatsAppCallRecorder

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNotificationRulesTest {
  @Test fun preservesLeadingZeroesAndAllowsOnlyOuterWhitespace() {
    assertEquals("001234", PairingNotificationRules.normalizeCode(" 001234\n"))
    assertNull(PairingNotificationRules.normalizeCode("001 234"))
  }

  @Test fun rejectsMissingLongAndNonAsciiCodes() {
    listOf(null, "", "12345", "1234567", "١٢٣٤٥٦", "１２３４５６", "12345a").forEach {
      assertNull(PairingNotificationRules.normalizeCode(it))
    }
  }

  @Test fun acceptsOnlyOneValidLocalPort() {
    assertNull(PairingNotificationRules.singlePort(emptyList()))
    assertEquals(37123, PairingNotificationRules.singlePort(listOf(37123, 37123)))
    assertNull(PairingNotificationRules.singlePort(listOf(37123, 38123)))
    listOf(0, 1023, 65536).forEach { assertNull(PairingNotificationRules.singlePort(listOf(it))) }
  }

  @Test fun acceptsLoopbackWithoutNetworkInterface() {
    assertTrue(PairingNotificationRules.isLocalAddress(InetAddress.getByName("127.0.0.1"), emptyList()))
    assertTrue(PairingNotificationRules.isLocalAddress(InetAddress.getByName("::1"), emptyList()))
  }

  @Test fun acceptsPhoneInterfaceButRejectsOtherLanDevice() {
    val phone = InetAddress.getByName("192.168.1.20")
    assertTrue(PairingNotificationRules.isLocalAddress(phone, listOf(phone)))
    assertFalse(PairingNotificationRules.isLocalAddress(InetAddress.getByName("192.168.1.21"), listOf(phone)))
  }

  @Test fun matchesIpv6InterfaceBytes() {
    val phone = InetAddress.getByName("fe80::1234")
    assertTrue(PairingNotificationRules.isLocalAddress(InetAddress.getByName("fe80::1234"), listOf(phone)))
    assertFalse(PairingNotificationRules.isLocalAddress(InetAddress.getByName("fe80::5678"), listOf(phone)))
  }

  @Test fun rejectsWildcardAndMulticastEvenIfListed() {
    listOf("0.0.0.0", "::", "224.0.0.251", "ff02::fb").forEach {
      val invalid = InetAddress.getByName(it)
      assertFalse(PairingNotificationRules.isLocalAddress(invalid, listOf(invalid)))
    }
  }
}
