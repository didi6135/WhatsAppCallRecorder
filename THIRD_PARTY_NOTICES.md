# Third-party code and dependencies

The resolved Fresco3.4.0 native image transcoder includes libjpeg-turbo2.1.5.1, whose complete BSD/IJG/zlib notices accompany the APK source/notice companion. This software is based in part on the work of the Independent JPEG Group. Upstream source: https://github.com/libjpeg-turbo/libjpeg-turbo/tree/2.1.5.1.

The root 0BSD license covers original project code only. It does not relicense bundled or downloaded dependencies. Keep their source headers, license texts, copyright notices and modification notices when redistributing. This source snapshot includes source/manifests/build instructions; `node_modules`, Maven/JitPack binaries and build outputs are not included.

## Vendored LibADB

- Upstream: [LibADB Android 3.1.1](https://github.com/MuntashirAkon/libadb-android/tree/c849886ebc6d48e7b46d967e78a6bb65c90c3b74), commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`.
- Project elects the **Apache-2.0** alternative wherever `GPL-3.0-or-later OR Apache-2.0` is offered. Additional BSD-3-Clause and MIT requirements indicated by per-file `AND` notices remain applicable.
- Full original texts are retained in [android/vendor/libadb/LICENSES](android/vendor/libadb/LICENSES); per-file notices and [local modification record](android/vendor/libadb/UPSTREAM.md) are retained. Modified files carry explicit modification notices.
- Authors named in the retained source include Muntashir Al-Islam, Cameron Gutman, Sam Palmer, Google Inc. and other original contributors. Their original source notices govern.

## SPAKE2 Android 2.2.1

- Dependency: `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`.
- Exact Java/JNI source: [commit 7615ddd680b990e14513ebb66eac4cb0dbf82464](https://github.com/MuntashirAkon/spake2-java/tree/7615ddd680b990e14513ebb66eac4cb0dbf82464).
- Upstream declares LGPL-3.0; its full [LGPL text](LICENSES/LGPL-3.0.txt) and accompanying [GPL text](LICENSES/GPL-3.0.txt) are retained here. See upstream source for copyright and native-component notices.
- Native submodule: [spake2-c commit 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31](https://github.com/MuntashirAkon/spake2-c/tree/0d15933e5ba3e662cb01245a7ac0dc9fca3eac31). Its per-file terms include LGPL-2.1-or-later and permissive terms; retain them when supplying that library's source or binaries.
- Source publication of this app is separate from binary redistribution of the linked library. [APK companion tooling](docs/apk-redistribution.md) assembles pinned corresponding source including the native submodule, retained component notices and a manifest bound to the exact APK/application source. [Rebuilding and recombination instructions](docs/rebuild-spake2.md) use an external Gradle override with a modified local AAR. A source URL alone does not establish that a binary release meets all LGPL requirements; the bundle records notice-review and actual modified-library-build gates separately.
- Builders can modify the application/library, build and sign their own APK with their own key/application ID. The app release build does not require the publisher's private key or contain an application-side ban on library modification/reverse engineering. Instructions must be verified for any redistributed modified library.

## Other direct native dependencies

| Component | Pinned version | Upstream license/reference |
| --- | --- | --- |
| Conscrypt | 2.5.3 | [Pinned upstream](https://github.com/google/conscrypt/tree/fd209de6167201e4f3bca5b99c6db9f08d1843c6); full Apache-2.0, NOTICE, Netty and Harmony texts retained in [LICENSES/third-party](LICENSES/third-party) |
| BoringSSL in Conscrypt | 49f0329110a1d93a5febc2bceceedc655d995420 | Identified by the published Conscrypt AAR properties; full OpenSSL/SSLeay, ISC and fiat MIT terms in [BoringSSL-LICENSE.txt](LICENSES/third-party/BoringSSL-LICENSE.txt) |
| Bouncy Castle bcprov-jdk15to18 | 1.81 | [Bouncy Castle license](https://www.bouncycastle.org/licence.html) |
| AndroidX WorkManager | 2.10.1 | [AndroidX Apache-2.0 source](https://android.googlesource.com/platform/frameworks/support/) |
| Gradle wrapper/build tooling | 8.11.1 | [Gradle Apache-2.0 source](https://github.com/gradle/gradle/tree/v8.11.1); wrapper scripts retain their original headers |
| Google Play services authorization | 22.0.0 | [Google SDK/service terms](https://developers.google.com/android/guides/overview); external dependency, not relicensed as project source |

The copied AOSP `rn_edit_text_material.xml` resource retains its 2014 Android Open Source Project copyright and Apache-2.0 header. The packaged `MaterialIcons.ttf` exactly matches [react-native-vector-icons 10.2.0 commit 9152776633a488fec666bfd64205c9930c5425a4](https://github.com/oblador/react-native-vector-icons/blob/9152776633a488fec666bfd64205c9930c5425a4/Fonts/MaterialIcons.ttf), SHA256 `ef149f08bdd2ff09a4e2c8573476b7b0f3fbb15b623954ade59899e7175bedda`. Its embedded notice is Copyright 2018 Google, Inc., font version 1.017. [Google's Apache-2.0 text](LICENSES/third-party/MaterialIcons-LICENSE.txt), [font notice](LICENSES/third-party/MaterialIcons-NOTICE.txt) and [wrapper MIT text](LICENSES/third-party/VectorIcons-LICENSE.txt) are retained separately. React Native/Expo and direct npm runtime packages declare their own licenses, predominantly MIT; inspect the actual resolved package notices when distributing their binaries. `package-lock.json` pins the npm dependency tree but is not a complete license report.

## Release boundary

This source package is not an audit certificate for every transitive dependency. Use the [APK companion procedure](docs/apk-redistribution.md) to collect notices from the actual resolved npm/AAR/native artifacts, check packaged native/font hashes and record a real modified-library rebuild. Its review candidate explicitly lists unfinished evidence gates; `--require-ready` rejects those candidates. The mechanical tool does not certify legal compliance or independently prove completeness of an operator-reviewed runtime inventory. A source snapshot alone does not complete Google OAuth registration, Google publishing review or device acceptance.
