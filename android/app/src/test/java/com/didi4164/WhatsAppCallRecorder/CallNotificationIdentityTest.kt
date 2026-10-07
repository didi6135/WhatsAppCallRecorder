package com.didi4164.WhatsAppCallRecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CallNotificationIdentityTest {
  private val uid = 10123
  private fun ongoing(key: String = "current-call", packageName: String = "com.whatsapp", ownerUid: Int = uid) =
    AutoCallPolicy.NotificationSignal(packageName, ownerUid, key, "call", true, callType = 2)
  private fun audio(at: Long = 1000, ownerUid: Int = uid, mode: Int = 3, known: Boolean = true) =
    AutoCallPolicy.AudioObservation(mode, ownerUid, known, at)
  private fun name(signal: AutoCallPolicy.NotificationSignal = ongoing(), person: String? = null,
    people: List<String?> = emptyList(), title: String? = null) =
    CallNotificationIdentity.extractDisplayName(signal, person, people, title)

  @Test fun acceptedStartMatchesLivePackageUidAndKey() {
    val live = ongoing()
    assertSame(live, CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(), uid, listOf(live)))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(), uid, listOf(live.copy(key = "old-call"))))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(), uid, listOf(live.copy(packageName = "com.whatsapp.w4b"))))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(), uid, listOf(live.copy(uid = uid + 1))))
  }

  @Test fun manualUsbCanUseOneFreshLiveAttributedOngoingCall() {
    val live = ongoing()
    assertSame(live, CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio()))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(ownerUid = uid + 1)))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(), uid + 1))
  }

  @Test fun endedOrReplacedNotificationNeverReusesPreviousLabel() {
    val old = ongoing("ended-call")
    val next = ongoing("next-call")
    assertNull(CallNotificationIdentity.selectLiveCall(1000, emptyList(), audio(), uid, listOf(old)))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(next), audio(), uid, listOf(old)))
    assertSame(next, CallNotificationIdentity.selectLiveCall(1000, listOf(next), audio(), uid, listOf(next)))
  }

  @Test fun ongoingNotificationAgeDoesNotExpireButAudioMustBeCurrent() {
    val live = ongoing()
    assertSame(live, CallNotificationIdentity.selectLiveCall(500_000, listOf(live), audio(at = 500_000), uid, listOf(live)))
    assertNull(CallNotificationIdentity.selectLiveCall(500_000, listOf(live), audio(at = 497_499), uid, listOf(live)))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(at = 1001)))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(known = false)))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), null))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(live), audio(mode = 0)))
  }

  @Test fun anyMultipleOngoingNotificationsProduceGenericRecording() {
    val first = ongoing()
    listOf(first.copy(key = "another-call"), ongoing("business-call", "com.whatsapp.w4b", uid + 1), first).forEach {
      assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(first, it), audio(), uid, listOf(first)))
    }
  }

  @Test fun messageIncomingSummaryOtherPackageAndEmptyKeyCannotBind() {
    val invalid = listOf(ongoing().copy(callType = 0, category = "msg", hasHangup = false),
      ongoing().copy(callType = 1), ongoing().copy(hasAnswer = true), ongoing().copy(groupSummary = true),
      ongoing(packageName = "com.whatsapp.clone"), ongoing(key = ""))
    invalid.forEach { assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(it), audio())) }
  }

  @Test fun unrelatedMessagesDoNotMakeOneLiveCallAmbiguous() {
    val call = ongoing()
    val message = ongoing("message").copy(category = "msg", callType = 0, ongoing = false)
    assertSame(call, CallNotificationIdentity.selectLiveCall(1000, listOf(call, message), audio()))
  }

  @Test fun telecomDelegationMustProveExactActivePackageUserAndFreshSample() {
    val business = ongoing(packageName = "com.whatsapp.w4b")
    val proof = AutoCallPolicy.TelecomObservation(true, 2, 0, 1, 1, true, true, true, 1000)
    val delegated = audio(ownerUid = 1000).copy(telecom = proof)
    assertSame(business, CallNotificationIdentity.selectLiveCall(1000, listOf(business), delegated))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(business), delegated.copy(telecom = proof.copy(packageCode = 1))))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(business), delegated.copy(telecom = proof.copy(activeCalls = 0))))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(business), delegated.copy(telecom = proof.copy(liveCalls = 2))))
    assertNull(CallNotificationIdentity.selectLiveCall(1000, listOf(business), delegated.copy(telecom = proof.copy(userId = 1))))
    assertNull(CallNotificationIdentity.selectLiveCall(4000, listOf(business), audio(at = 4000, ownerUid = 1000).copy(telecom = proof)))
  }

  @Test fun explicitOptOutReadsNoDisplayNameFields() {
    var reads = 0
    val result = CallNotificationIdentity.readDisplayName(false, ongoing()) {
      reads++; CallNotificationIdentity.NameFields("Private person", emptyList(), "Private title")
    }
    assertNull(result)
    assertEquals(0, reads)
  }

  @Test fun nonCallsReadNoDisplayNameFieldsEvenWhenEnabled() {
    var reads = 0
    val invalid = listOf(ongoing().copy(callType = 0, category = "msg", ongoing = false),
      ongoing().copy(callType = 1), ongoing().copy(groupSummary = true), ongoing(packageName = "com.other"))
    invalid.forEach { signal ->
      assertNull(CallNotificationIdentity.readDisplayName(true, signal) {
        reads++; CallNotificationIdentity.NameFields("Private person", emptyList(), "Private title")
      })
    }
    assertEquals(0, reads)
  }

  @Test fun structuredPersonPreferredToCallTitleAndSinglePersonSupported() {
    assertEquals("Alice", name(person = "Alice", title = "Video call"))
    assertEquals("David", name(people = listOf("David"), title = "WhatsApp"))
    assertEquals("שרה כהן", name(title = "שרה כהן"))
    assertEquals("Alice", name(person = "Alice", people = listOf("Alice"), title = "Another title"))
  }

  @Test fun multipleOrConflictingStructuredPeopleCannotBeMislabelled() {
    assertNull(name(person = "Alice", people = listOf("Alice", "Bob"), title = "Alice"))
    assertNull(name(person = "Alice", people = listOf("Bob"), title = "Alice"))
    assertNull(name(people = listOf(null, "Bob"), title = "Alice"))
  }

  @Test fun missingAndGenericDisplayLabelsKeepGenericTitle() {
    listOf("WhatsApp", "WhatsApp Business", "Voice call", "Ongoing call", "שיחת וידאו", "שיחה נכנסת",
      "Calling Alice", "מתקשר אליך", "00:42", "1:20:10", "3 participants", "").forEach {
      assertNull(name(title = it))
    }
    assertNull(name())
    assertNull(name(person = "WhatsApp", title = "Voice call"))
  }

  @Test fun displayLabelsAreSanitizedAndBoundedWithoutChangingIdentityKeys() {
    assertEquals("Alice Bob", name(person = " Alice\r\n/Bob\\ "))
    assertEquals("שלום עולם", name(person = "שלום\u202E עולם"))
    assertEquals("é", name(person = "e\u0301"))
    assertEquals("+972501234567", name(title = "+972501234567"))
    assertEquals(80, name(person = "A".repeat(200))!!.length)
    assertNull(name(person = ". / :"))
    assertNull(name(person = "A".repeat(2049)))
  }

  @Test fun ordinaryMessageNameIsNeverReturnedAsCallIdentity() {
    val message = ongoing().copy(category = "msg", callType = 0, ongoing = false)
    assertNull(name(message, person = "Alice", people = listOf("Alice"), title = "Alice"))
  }

  @Test fun optionalIdentityAndFieldObjectsRedactTheirStringRepresentation() {
    val identity = CallNameIdentity("Private person", "com.whatsapp")
    val fields = CallNotificationIdentity.NameFields("Private person", listOf("Private person"), "Private title")
    assertFalse(identity.toString().contains("Private"))
    assertFalse(fields.toString().contains("Private"))
  }

  @Test fun settingCannotChangeFromBackgroundDuringRecordingOrInvalidActivity() {
    assertEquals("FOREGROUND_REQUIRED", CallNameMutationPolicy.rejection(false, true, true, false))
    assertEquals("FOREGROUND_REQUIRED", CallNameMutationPolicy.rejection(true, false, true, false))
    assertEquals("FOREGROUND_REQUIRED", CallNameMutationPolicy.rejection(true, true, false, false))
    assertEquals("RECORDING_BUSY", CallNameMutationPolicy.rejection(true, true, true, true))
    assertNull(CallNameMutationPolicy.rejection(true, true, true, false))
  }
}
