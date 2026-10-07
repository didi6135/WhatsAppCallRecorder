# Android compatibility: measured results

Test date: **2026-10-07**. These results apply to the specific environments and
test layers below. They do not certify every phone with the same Android version.

## סיכום בעברית

נבדקו בפועל ארבע סביבות Android: 13, 14, 15 ו־16. רכיב האודיו המקורי עבר
בדיקת אות מלאכותי ב־14, 15 ו־16. ב־13 ההרשאה הנדרשת חסרה, והקלטת השיחות
נעצרה במכוון לפני יצירת רכיב אודיו. אלה אמולטורים; לא נבדקו בהם שיחות
WhatsApp אמיתיות, זיהוי אוטומטי או טלפונים של יצרנים אחרים.

בטלפון Samsung SM-S928B עם Android 16 נבדקו רק הרשאות ויכולות מערכת, ללא
הקלטה. הבדיקה עברה. לא הותקן עליו עדכון ולא שונו ההגדרות שלו.

נמצאה ותוקנה בקוד בעיה שהסתירה את שלב הכשל בהפעלה: תקלות שונות הוצגו
כהודעה כללית. התיקון עבר קומפילציה ובדיקות, אך **אינו כלול בקובץ ההתקנה
1.5.4 שנבדק**. לא הוכח עדיין תיקון לכשל המדווח בטלפונים אחרים; נדרשים הדגם,
גרסת Android והשלב שבו כל מכשיר נכשל.

## Recorded OS matrix

| Android / API | Environment | Shell voice grant | Production audio factories | Tested release APK |
| --- | --- | --- | --- | --- |
| 13 / 33 | AOSP x86_64 emulator, userdebug/test-keys | Absent | Intentionally unsupported; no audio resources created | Install rejected: ARM64 APK on x86-only image |
| 14 / 34 | AOSP x86_64 emulator, userdebug/test-keys | Granted | Output and microphone start/read passed; synthetic output nonzero | Install rejected: ARM64 APK on x86-only image |
| 15 / 35 | AOSP x86_64 emulator, userdebug/test-keys | Granted | Output and microphone start/read passed; 750 Hz signal verified | Install rejected: ARM64 APK on x86-only image |
| 16 / 36 | Google APIs x86_64 emulator, userdebug/dev-keys | Granted | Output and microphone start/read passed; 750 Hz signal verified | Installed via ARM translation; app launch **failed** to load ARM64 React Native library |
| 16 / 36 | Samsung SM-S928B, stock user build, ARM64 | Granted | **Metadata only**: context/permission/API preflight passed; no audio resources | Existing app installation preserved; no new launch/call test |

The Android 16 launch failure was independently reproduced after boot with 2 GB
guest RAM. Its crash identifies `SoLoaderDSONotFoundError`, `libreactnative.so`
and an x86_64 `DirectApkSoSource` lookup in an ARM64-only APK. Installation success
and `am start: Status ok` therefore did **not** become an app-launch pass. This
does not establish the same failure on an ARM64 physical phone.

The release has minimum install API **24** and packages **arm64-v8a only**.
Installation eligibility is separate from shell call capture, whose current
candidate boundary is API **34**. Android 13 and older are not supported by this
call-capture route; microphone-only use is a separate feature.

## Audio observations

All full probes ran as actual Shell UID **2000**, using the production
`ShellAudioContext`, `ShellAudioCompatibility`, `AudioCaptureApi` and the actual
`UsbAudioHelper` output/microphone factories. Host audio input/output were disabled.
The two-second synthetic track uses `USAGE_VOICE_COMMUNICATION`, 16 kHz mono PCM
and 750 Hz. No audio recording file was saved or replayed.

| OS | Output frames | Nonzero output samples | Output peak | 750 Hz energy fraction | Microphone frames |
| --- | ---: | ---: | ---: | ---: | ---: |
| 14 | 32,256 | 27,776 | 6,000 | Not measured by the earlier harness | 32,048 |
| 15 | 32,256 | 28,272 | 6,000 | 0.9048 | 31,769 |
| 16 | 34,816 | 22,320 | 6,000 | 0.6618 | 29,637 |

Each passed run reported no resource-stop/release/unregister invocation errors.
Asynchronous policy removal was not independently measured. Microphone frame
counts prove readable buffers, **not intelligible microphone audio**, because
host microphone input was deliberately disabled. A shell-generated synthetic
track also does not establish WhatsApp's capture policy or a phone's audio route.

The first Android 14 harness incorrectly required a static `AudioTrack` to be
initialized before its first write. That test-only bug was corrected and the
same environment passed. Invalid blank-data/port attempts, host disk exhaustion
and a low-memory emulator System UI ANR are separately retained environment
failures, rather than counted as app or phone results.

## Portable diagnostics correction

Native code already identified discovery, ADB connection, bootstrap,
authentication/readiness, handoff, detach, liveness and foreground-owner stages.
JavaScript replaced them with a generic error, and focused setup replaced them
again with pairing-failure copy. The fix preserves an approved stage code and
actionable Hebrew/English message through native status and Promise rejection.

The existing rejection families remain compatible. Unknown, malformed or
cross-family details fall back to fixed generic copy; private exception text is
not displayed. Accepted retries, pairing cancellation and verified success clear
stale diagnostics. No permissions, ADB commands, attribution rules, audio APIs
or compatibility guards were changed.

Validation: the new regression failed before the fix, then passed; **34**
localization checks, **468** paired resources, full TypeScript, Java mapping tests,
actual Android/Kotlin/Java compilation and **161 Android unit tests across 19
suites** passed, with zero failures/errors/skips. These are source/build checks,
not a new release APK or installed-device acceptance.

## What still requires real phones

No additional physical OEM WhatsApp call was tested in this run. Prior owner
reports of successful recording on the original Samsung remain evidence for
that tested setup; they do not certify other models or Android versions.

Use stock, unrooted ARM64 devices, recording exact model, Android API, OEM UI,
WhatsApp/Business version and APK hash. A useful next physical sample is:

| Device family | OS coverage sought | Current status |
| --- | --- | --- |
| Google Pixel / stock Android | 14 or 15 | Pending |
| A second Samsung / One UI model | 14 or 15 | Pending |
| Xiaomi / Redmi / POCO / HyperOS | 14 or 15 | Pending |
| OnePlus / OPPO / OxygenOS / ColorOS | 15 or 16 | Pending |
| Motorola / near-stock Android | 14 or 15 | Pending |

For each phone, run these independent cases with participants aware of the test:

1. Normal installation and first-run permissions/pairing without a computer;
   clear recovery when developer options, notification/microphone permission or
   pairing authorization is missing.
2. Incoming and outgoing answered calls in WhatsApp **and** Business, without
   pressing Record: one start, one stop/save, both participants intelligible.
   Missed/declined calls, unrelated notifications and other VoIP apps must not
   generate unintended recordings.
3. Earpiece, speaker, Bluetooth and wired/USB headphones where available. Check
   each participant separately; file size or nonzero samples alone are insufficient.
4. Screen lock, app in background, idle/battery restrictions, activation followed
   by Wi-Fi off/mobile data, and ordinary network transitions.
5. Manual STOP during a call, hold/resume, consecutive calls, service loss,
   reboot and guided reactivation. Work profiles/cloned apps are separate cases.
6. Local library/playback and each enabled backup independently; upload failure
   must preserve the local file.

Share only the failed stage, model/OS and sanitized capability receipt. No private
audio, call/notification dumps, caller identity, pairing code or credentials are
needed. The owner was asked for failed models/versions/stages; that information
was still pending when this report was prepared.

## Reproducibility and sources

- [Machine-readable measured matrix](validation/android-matrix-2026-10-07.json)
- [Probe build, ownership guards and commands](../tools/android-device-matrix/README.md)
- [Android emulator command-line documentation](https://developer.android.com/studio/run/emulator-commandline)
- [AOSP Android 13 Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/packages/Shell/AndroidManifest.xml)
- [AOSP Android 14 Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml)

The immutable AOSP 13–16 capture/context audit retained 40 source downloads and
the 14–16 attribution audit retained 42, each bound to URL, bytes and SHA256.
Host regressions passed: capture compatibility 71, reflective API 17, shell
context 4, audio owner 38, Telecom attribution 143 and monitor 13. A further 30
source-rendered attribution replays are **synthetic host tests**, not real phones.
No Samsung/model override or manufacturer spoofing was added.

Tested release **1.5.4**, 32,885,737 bytes:
`c5db0060af960dad1b55a2e068e2e943de76833d9bc75a089c4a70ac82571fac`.
The frozen APK, personal device data, credentials, unrelated emulators and pending
Codaki website preview were preserved. Scratch SDK images/AVDs were sequential;
some generated image/RAM caches were removed after retaining results to fit this
host's limited disk space. The Android 15/16 tone-proof JAR, the earlier Android
14 JAR and raw receipt hashes are recorded in the machine-readable matrix.
