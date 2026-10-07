# Rebuild and recombine the SPAKE2 library

The application uses SPAKE2 Android 2.2.1 under LGPL-3.0. Original application code remains 0BSD. You may modify the library and reverse engineer the combined application to debug those modifications. The publisher imposes no additional restriction on that work. Full LGPL/GPL texts and the native component's additional terms accompany the download.

This distribution uses the corresponding-source/recombination route described in [LGPL section 4(d)(0)](https://www.gnu.org/licenses/lgpl-3.0.html). A native `.so` inside an APK is not, by itself, evidence of a user-replaceable shared-library mechanism.

## Exact build inputs

- Java/JNI: [spake2-java 7615ddd680b990e14513ebb66eac4cb0dbf82464](https://github.com/MuntashirAkon/spake2-java/tree/7615ddd680b990e14513ebb66eac4cb0dbf82464).
- Native sources at `android/src/main/cpp/spake2-c`: [spake2-c 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31](https://github.com/MuntashirAkon/spake2-c/tree/0d15933e5ba3e662cb01245a7ac0dc9fca3eac31).
- Pinned source archives are included in the companion bundle as one assembled `spake2-corresponding-source.zip`. It contains all source files, source headers, CMake files, Gradle inputs and both submodule commits. The parent archive alone omits the native submodule.
- The published 2.2.1 AAR's SHA256 is `8798fb6c04b5d53a6307ed9481e9afe563227abd4e8b9e917c8514fa850071bb`. Upstream's pinned `android/build.gradle` still declares module version `2.2.0`; do not mistake that string for a different source revision.
- The library build declares AGP 8.9.2, Android SDK platform 35/build-tools 35.0.0, Java source level 8 and CMake >=3.4.1. AGP needs JDK 17. The native target is `libspake2.so`; CMake compiles `sha512.c`, `spake2.c` and `spake2_jni.cpp`.
- The pinned library wrapper requests Gradle 9.0.0. Use the application's pinned Gradle 8.11.1 wrapper to run AGP 8.9.2 consistently. Install Android SDK platforms/build-tools 35 and 36, NDK 28.2.13676358 and CMake 3.22.1. Keep SDK location in a local, untracked `local.properties` or Android SDK environment variable.
- Application inputs are in `application-source.zip`, including `package-lock.json`, Gradle wrapper, vendored LibADB, helper sources and build scripts. Run `npm ci` in that source directory; Node 20+ and JDK 17 are the intended environment. Downloaded build dependencies are resolved from the declared repositories; credentials and the publisher's private signing key are not source inputs.

## Library build and application substitution

Extract both source ZIPs in separate directories. The following PowerShell example uses placeholders for your own absolute directories. It makes no changes to the application's normal dependencies:

```powershell
$appSource = 'C:/work/WhatsAppCallRecorder'
$librarySource = 'C:/work/spake2'
Set-Location "$appSource/android"
./gradlew.bat -p $librarySource `
  -I "$appSource/tools/release-compliance/recombine.init.gradle" :android:assembleRelease
# Rebuilt artifact: $librarySource/android/build/outputs/aar/android-release.aar

Set-Location $appSource
npm ci
Set-Location "$appSource/android"
./gradlew.bat -I ../tools/release-compliance/recombine.init.gradle `
  '-PwaReco.spake2Aar=C:/work/spake2/android/build/outputs/aar/android-release.aar' `
  '-PwaRecorder.applicationId=org.example.wareco.modified' assembleDebug
```

For the upstream library build, the external init script selects the documented NDK 28.2.13676358 and CMake 3.22.1 without changing its source. For the application build, it removes exactly the SPAKE2 Maven dependency and replaces it with the supplied local AAR. Other dependencies, recording policy and normal builds remain unchanged. Keep the public Java/native interface compatible if you want the application's existing pairing code to work.

For a release build, create your own key and a private signing properties file using `docs/signing.properties.example`. Supply `-PwaRecorder.signingProperties=/your/private/signing.properties` and run `assembleRelease`. Never include that file or the key in a source/download bundle. The original publisher's key is unnecessary. Android will not accept your signature as an update to the publisher's installed app; using your own application ID installs a separate application, with separate settings/recordings and a separate guided activation. The debug route uses the conventional public debug key and is intended for testing.

You control installation through Android's normal APK installer (including its install-from-this-source approval), or your own developer tooling. The app does not enforce the publisher's signing certificate for helper activation. Android 16 has device-tested capture behavior; Android 14/15 remains experimental. Building a modified APK does not establish audio compatibility on another device.

## Publisher verification checkpoint

Before advertising a binary distribution as having a tested recombination path, the build operator must modify the library, rebuild the AAR, use the override to build/sign a modified application and inspect the resulting APK. A version-script or source edit that changes only a comment does not demonstrate relinking; the native library bytes must change. Save the modified library's complete source/build inputs privately for this check, together with the AAR and APK. The utility `record-recombination` verifies that the modified APK actually includes the changed AAR's `libspake2.so` and records their hashes, bound to the exact original APK/application-source ZIP. Own-key signing and installation-instruction review are explicitly recorded operator checks.

These instructions are a concrete build route. The companion bundle manifest states whether an actual modified-library build has been recorded. A synthetic test, source URL or unchecked recipe is not recorded as that build. Preserve the modified sources, build flags and corresponding notices when distributing a modified library.
