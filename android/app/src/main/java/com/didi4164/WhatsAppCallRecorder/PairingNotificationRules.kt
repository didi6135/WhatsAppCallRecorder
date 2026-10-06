package com.didi4164.WhatsAppCallRecorder

import java.net.InetAddress

/** Pure validation shared by the notification receiver and NSD resolver. */
internal object PairingNotificationRules {
  fun normalizeCode(raw: String?): String? = raw?.trim()?.takeIf { it.matches(Regex("[0-9]{6}")) }

  fun isLocalAddress(address: InetAddress, ownAddresses: Collection<InetAddress>): Boolean {
    if (address.isLoopbackAddress) return true
    if (address.isAnyLocalAddress || address.isMulticastAddress) return false
    return ownAddresses.any { it.address.contentEquals(address.address) }
  }

  fun singlePort(ports: Collection<Int>): Int? {
    val distinct = ports.toSet()
    return distinct.singleOrNull()?.takeIf { it in 1024..65535 }
  }
}
