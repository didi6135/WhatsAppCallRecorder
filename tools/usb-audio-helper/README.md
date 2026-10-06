# App-controlled USB audio helper

Helper for an owner-authorized ADB shell connection, retaining the existing Android 16/API 36 route and enabling experimental Android 14/15 candidates. Android 13 and earlier are rejected. Android 14/15 two-sided capture and automatic recording still await real-device testing. It does not capture while idle. The installed app controls START and STOP over an authenticated TCP socket bound only to 127.0.0.1. Neither WhatsApp calls nor Android settings are controlled by the helper. Unix sockets were rejected by the device's SELinux policy; this transport uses the app's normal loopback network permission.

`ShellAudioCompatibility` is shared by native setup and this helper. Every candidate must pass real shell UID 2000, current `RECORD_AUDIO`, `MODIFY_AUDIO_ROUTING` and `CAPTURE_VOICE_COMMUNICATION_OUTPUT` grants, and metadata-only checks of the exact reflective audio APIs before authenticating readiness. It constructs no audio resources during preflight. START still requires AudioService registration, a usable sink and initialized recorders; failures cannot become STARTED. Canonical AOSP 14/15/16 current Telecom rows and boundaries are identical. The strict Samsung wrapper candidate requires both release fields to agree with the actual helper SDK; unknown OEM layouts remain UNKNOWN and cannot authorize automatic START. Samsung 14/15 wrapper compatibility is not yet device-verified.

Primary policy sources: [AudioService 14](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/services/core/java/com/android/server/audio/AudioService.java#11644), [15](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/audio/AudioService.java#12539), [16](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/audio/AudioService.java#13530). Each requires the voice-output grant for the exact voice LOOP_BACK_RENDER mix and sets the voice flag in the service. The current mix does not enable privileged media capture.

Host checks: `tests/run-compatibility-tests.ps1` exercises the version/grant matrix and missing/incompatible reflection signatures with framework stand-ins that throw if constructed or invoked. `tests/run-telecom-tests.ps1` includes SDK-bound AOSP/Samsung candidates, cross-version rejection and existing bounds/privacy/linkage failures. Native JUnit also tests the shared SDK policy; successful host checks do not substitute for older-device UAT.

The helper includes its own shell ContextWrapper and has no scrcpy runtime dependency. It validates real process UID 2000 and attributes audio to the existing com.android.shell package; it does not change permissions or the caller UID. Its default Application is also attached to that context: AudioPolicy's sink constructor uses ActivityThread.currentApplication(), so the prepopulated systemMain Application attributed to `android` must be replaced. Existing shell attribution is preserved and another app's Application is rejected. Capture uses an exact voice-communication output mix with LOOP_BACK_RENDER, preserving device playback, and an independent non-sensitive MIC stream. AudioService performs permission checks and sets its own voice capture flag. The helper never sets that flag or enables privileged media opt-out override. Protected output can remain silent; real-call support requires device testing.

Build: `powershell -ExecutionPolicy Bypass -File tools/usb-audio-helper/build-helper.ps1`. This only compiles locally. Generated JARs/build directories are ignored by Git.

Run after the app's server is ready and the user approves USB pairing:

```text
CLASSPATH=/data/local/tmp/codaki-audio-probe/usb-audio-helper.jar app_process / com.codaki.usbaudio.UsbAudioHelper --app-uid=APP_UID --port=APP_PORT --token-file=/data/local/tmp/codaki-audio-probe/pairing.json
```

The app creates a fresh ephemeral loopback port and cryptographically random 32-byte token in its private `files/usb-pairing.json`. The authorized launcher reads that file with `run-as`, verifies the installed diagnostic app UID, stages the JSON as shell-owned `pairing.json` with mode 600, and passes its matching port/UID to the helper. The JSON contains `port`, `appUid`, `token` (64 lowercase hexadecimal characters), and `protocol` (`WA_USB_2`). The helper validates metadata, file ownership/permissions, and mutual authentication before accepting START. It itself requires shell UID 2000. Tokens are never printed or transmitted over the socket.

For the phone-only route, the APK contains these same helper classes. After explicit Android wireless-debugging pairing, the app's embedded ADB client launches `com.codaki.usbaudio.ShellAudioBootstrap --spawn-detached --app-uid=APP_UID --port=APP_PORT` from its own `base.apk` with `app_process`, as actual shell UID 2000. The parent reads exactly 32 raw key bytes from its ADB stream, validates a single installed-APK classpath, and launches the fixed worker through `ProcessBuilder` and `/system/bin/setsid`. The worker receives its key through a transient pipe, runs in its own process group/session, and has stdout/stderr redirected to `/dev/null`. No secret is stored in a file or command-line argument.

After the worker authenticates the app's loopback HMAC, the app sends acknowledgement byte `0x01` to the parent. The parent relays that byte to the worker's pipe, closes the pipe, records the ownership handoff, emits `BOOTSTRAP_DETACHED_HANDOFF_OK`, and exits. The app confirms a fresh loopback heartbeat after closing ADB. Only then is the detached activation ready. EOF, wrong ACK, or a 12-second parent startup timeout cleans up the child; its own 20-second pre-handoff deadline also prevents a leaked worker if the parent is abruptly killed. Both processes enforce real shell UID 2000; the worker verifies its own PID equals its process group and session using bounded `/proc/self/stat` metadata. Detached diagnostics use Android's `UsbAudioHelper` log tag.

The active worker depends on the authenticated app loopback socket, so later ADB/Wi-Fi disconnection does not revoke it. App socket EOF still stops capture and exits the helper. Wi-Fi is required for initial wireless-debugging pairing/start and for activation again after phone/app process restart; device verification must establish that the vendor preserves the detached process when Wi-Fi is disabled. No additional manager app or scrcpy installation is required. Ordinary APK microphone permission alone does not grant these shell privileges. The APK foreground service and START/STOP remain the recording owner; connecting the helper itself captures nothing.

Connection attempts expire after 60 seconds and log only the first/final connection error. Authentication expires after five seconds. A two-second blocked-send watchdog closes the socket; the idle command reader blocks until a command arrives or the socket closes. A socket failure immediately stops capture. Process exit is explicit so framework background threads do not outlive the helper.

## Wire protocol WA_USB_2

Authentication uses Java `DataOutputStream.writeUTF`/`DataInputStream.readUTF` throughout. Each side independently creates a random 32-byte nonce encoded as 64 lowercase hexadecimal characters. The token's decoded 32 bytes are the HMAC-SHA256 key. Proofs are 64 lowercase hexadecimal characters, compared using `MessageDigest.isEqual` on decoded bytes.

1. Helper sends `WA_USB_2`, then its `clientNonce`.
2. App sends `serverNonce`, then HMAC-SHA256 of UTF-8 `SERVER|clientNonce|serverNonce`.
3. Helper verifies the server proof before sending HMAC-SHA256 of UTF-8 `CLIENT|clientNonce|serverNonce`.
4. App verifies the client proof before considering the connection ready.

After authentication, app-to-helper commands are `writeUTF("START")` and `writeUTF("STOP")`. Exactly one command reader and one frame writer exist. START creates and starts both capture streams before acknowledgement. STOP releases microphone/output and unregisters the policy before acknowledgement. Idle heartbeat contains no PCM.

STOP first sets Java stop flags and wakes FIFO readers; the command reader never calls a blocking native audio operation. Output/microphone release runs concurrently with a common 2.5-second deadline, followed by at most one second for policy removal. `AudioRecord.release()` performs its own stop; the helper avoids repeated synchronous stop calls. Cleanup stage diagnostics identify stalled native calls. If native cleanup cannot finish within its deadline, the helper terminates its own process so Android releases its audio clients; it disconnects without falsely sending STOPPED. The app then preserves its partial recording as interrupted and USB pairing must be restarted.

Before release, STOP quiesces both producer threads within a common 500-millisecond deadline and freezes each FIFO's captured-sample cutoff. An equal, already-built packet is still sent. An unequal terminal packet waits for quiescence, fills each short channel's suffix from its frozen FIFO, and is then sent exactly once; late in-flight samples retain their positions. Remaining samples drain in packets of at most 4096 stereo frames (16384 bytes); partial final packets are valid. STOP does not invent a live deadline gap for a short terminal block. Per-track cutoff logs report produced/delivered/missing/stale/overflow/queued frames and maximum backlog. Existing live gaps and policy silencing remain visible; this is not a promise that device capture never gaps.

| Frame type | Payload |
|---|---|
| byte 0 | Heartbeat, no payload |
| byte 1 | flags byte, signed int32 big-endian byteCount, PCM payload |
| byte 2 | STARTED, no payload |
| byte 3 | STOPPED, no payload |
| byte 4 | Java writeUTF error message (maximum 600 characters) |

PCM is 16 kHz, stereo, signed PCM16 little-endian: voice output on the left, microphone on the right. Current packets contain 320 stereo frames (1280 bytes). Receivers must reject lengths outside 4..16384 or not divisible by four.

Flags: bit 0 output policy silenced; bit 1 microphone policy silenced; bit 2 output missed a bounded sample deadline; bit 3 microphone missed a bounded sample deadline. Treat either policy silencing or deadline gaps as interruptions. Sound detection is not proof that both call participants were captured.

Independent readers continuously drain each recorder into a bounded two-second FIFO. The writer preserves excess samples between packets and allows 200 milliseconds for missing data before marking an actual transport deadline gap. Late samples corresponding to already-reported gaps are discarded to retain frame alignment. Startup microphone padding follows the measured monotonic start-time offset. This provides bounded diagnostic alignment; it is not sample-accurate hardware timestamp synchronization. Two seconds without any samples or FIFO overflow stops the session with ERROR and STOPPED.

The bounded probe under `artifacts/adb-audio-probe` has separately verified synthetic voice output plus concurrent microphone capture on the attached Samsung device. This helper requires its own app socket/lifecycle checks and a user-operated, disclosed real-call test before WhatsApp recording can be reported as working.
