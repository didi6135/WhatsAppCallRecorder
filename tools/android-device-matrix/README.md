# Android compatibility probes

These developer tests separate Android framework capability from real WhatsApp
and manufacturer support. They do not replace tests on stock physical phones.
See [the recorded matrix](../../docs/android-device-compatibility.md).

## What is measured

`RuntimeAudioProbe` invokes the **production** shell context, permission and API
preflight, output recorder and microphone recorder factories. Full mode plays
a two-second 750 Hz synthetic voice track, reads sample counts and tone energy,
then stops/releases the resources and unregisters the capture policy. Samples
are discarded; no recording file is saved. Microphone input is disabled, so its
frame count does not prove intelligible microphone capture.

Full mode requires Shell UID 2000 and emulator hardware. `run-probe.py` additionally
requires this checkout's exact owned AVD, an audio-disabled generated hardware
configuration, no third-party apps, and a fresh evidence directory. It separately
tries installing/launching the supplied APK. An x86-only image cannot install an
ARM64-only release; that is an ABI limitation of the test environment.

`--metadata-only` accepts physical devices and returns **before** any capture
policy, recorder or playback is constructed. It checks only SDK/ABI, Shell grants
and the production reflection/context preflight. It does not read notifications,
contacts, calls or recordings, install an app, or change permissions/settings.
Neither mode establishes automatic WhatsApp detection or real call audio.

## Build the probe

Use a trusted checkout, Java/Javac, and installed Android SDK platform/build-tools
36. No phone or app-specific credentials are needed.

```powershell
./tools/android-device-matrix/build-probe.ps1 -BuildName probe-build-local
```

The JAR uses minimum DEX API 24. Its adjacent `build-manifest.json` binds sorted
production/probe source hashes, Git HEAD, compiler inputs and output bytes/hash.
Use a fresh build name after changing source; preserve old results.

## Create an isolated test environment

The optional preparation scripts currently require Windows, `fsutil`, an
installed Android emulator/qemu-img and a WSL distro with `/sbin/mkfs.ext4`.
They format a **new owned file**, never a disk or an existing AVD.
Do not reuse a personal/project emulator or a connected physical phone.

Install an official x86_64 system image with SDK Manager. Alternatively the
optional sparse provisioner verifies the official catalog archive checksum and
ZIP members while keeping its bounded archive in RAM. A partial failed extraction
is invalid; retry into a fresh owned SDK root. Allow several GB of disk and RAM;
other builds on the computer can exhaust the reserve.

```powershell
python tools/android-device-matrix/provision-sparse-image.py --sdk 35
python tools/android-device-matrix/prepare-owned-avd.py --sdk 35 `
  --image artifacts/sparse-test-sdk/system-images/android-35/default/x86_64
```

Preparation supports API 33, 34, 35 and 36 and validates the actual image API,
ABI and tag. `--android-sdk` and `--wsl-distro` can select installed dependencies.
Set `ANDROID_AVD_HOME` to this checkout's `artifacts/avd-home`. Start only your
new `wa-reco-apiSDK` profile on a free emulator port (5554 through 5584, even):

```powershell
$env:ANDROID_AVD_HOME = "$PWD/artifacts/avd-home"
emulator -avd wa-reco-api35 -port 5578 -no-window -no-audio -no-boot-anim `
  -no-snapshot -feature -QuickbootFileBacked -gpu swiftshader_indirect `
  -memory 2048 -cores 4
```

The RAM-file feature is disabled to avoid a large mapped snapshot file even with
`-no-snapshot`. Google API 36 needs more resources than the minimal AOSP images;
system UI ANRs or host disk failures are test-environment failures. Never mark
the app launch as passed from `am start` alone.

## Run an emulator test

```powershell
python tools/android-device-matrix/run-probe.py --serial emulator-5578 --sdk 35 `
  --jar artifacts/android-device-matrix/probe-build-local/runtime-audio-probe.jar `
  --apk path/to/wa-reco.apk --expected-apk-sha256 YOUR_64_CHARACTER_DIGEST `
  --output artifacts/android-device-matrix/api35-local
```

The expected APK digest is optional but recommended for release evidence.
Results include real SDK/build type, permissions, resource/read/tone outcomes,
cleanup-call errors, APK installation and launch evidence, and explicit false
fields for untested physical WhatsApp, pairing and automatic-call behavior.
Check the screenshot and the app's actual foreground/process state.
Afterwards stop only your owned AVD; preserve unrelated emulators.

## Check a consenting tester's phone without recording

Use an authorized ADB connection and the trusted probe built above. The serial
selects the tester's device but is **not included** in the saved receipt.

```powershell
python tools/android-device-matrix/run-metadata-probe.py --serial DEVICE_SERIAL `
  --jar artifacts/android-device-matrix/probe-build-local/runtime-audio-probe.jar `
  --output artifacts/android-device-matrix/phone-capabilities.json
```

This pushes a small uniquely named JAR to `/data/local/tmp` and runs exactly the
capability-only mode. A pass does not prove that Android will permit an actual
call capture. Share only the sanitized receipt, failed setup stage and model/OS;
do not share ADB identities, pairing codes, bot credentials, account IDs,
notification text, caller identities, call dumps or recordings.

Physical incoming/outgoing WhatsApp and Business, routes, screen lock, background,
Wi-Fi/mobile-data changes and reboot recovery require the separate manual matrix
in the linked report, with participants aware of the test.
