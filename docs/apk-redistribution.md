# APK companion source and notice bundle

Distribute the APK together with its companion ZIP, and link both from the same download page. Prominently state that the app uses SPAKE2 Android 2.2.1 under LGPL-3.0 and link the supplied source/rebuild instructions. Original application code remains licensed under 0BSD; dependencies retain their own terms. Modification of the included library and reverse engineering for debugging those modifications are permitted.

The companion contains the exact application-source snapshot, assembled Java/JNI/native SPAKE2 source, full license texts, LibADB notices, Conscrypt and its pinned BoringSSL notices, the exact MaterialIcons attribution, resolved runtime component inventory and per-file SHA256 manifest. It does not contain private Git history, private signing keys, environment files, recordings, `node_modules` or other dependency binaries. The APK itself contains the runtime binaries.

Conscrypt 2.5.3 embeds BoringSSL `49f0329110a1d93a5febc2bceceedc655d995420`, as identified by its published AAR's `org/conscrypt/conscrypt.properties`. Retain the complete upstream license including OpenSSL/SSLeay, Google ISC and fiat MIT terms. Required acknowledgments include:

> This product includes software developed by the OpenSSL Project for use in the OpenSSL Toolkit (http://www.openssl.org/)

> This product includes cryptographic software written by Eric Young (eay@cryptsoft.com).

> This product includes software written by Tim Hudson (tjh@cryptsoft.com).

The bundled `MaterialIcons.ttf` is the exact font from react-native-vector-icons 10.2.0 / commit `9152776633a488fec666bfd64205c9930c5425a4`, SHA256 `ef149f08bdd2ff09a4e2c8573476b7b0f3fbb15b623954ade59899e7175bedda`. Its embedded copyright is Google 2018 and version is 1.017. Google's Apache-2.0 font terms are separate from the wrapper package's MIT terms; both are supplied. Font source provenance is pinned to the actual wrapper copy, without inventing an earlier Google repository revision for the transformed font.

## Prepare the artifacts

Run from the project source root, using Python 3.11+. Commands refuse to overwrite existing output files.

```powershell
python tools/export-public-source.py --output artifacts/application-source.zip
python tools/release-compliance/release_bundle.py sources `
  --output artifacts/spake2-corresponding-source.zip
```

The source command downloads only the two pinned official upstream archives, enforces their SHA256 hashes and size limits, and combines the native submodule at its declared path. It preserves all source notices/build inputs and makes the Gradle wrapper executable in the ZIP. Fetching the library source does not execute upstream build scripts.

For the actual final release, use the external Gradle init script to obtain the *resolved* `releaseRuntimeClasspath` artifacts:

```powershell
Set-Location android
./gradlew.bat -I ../tools/release-compliance/recombine.init.gradle `
  '-PwaReco.inventoryOutput=../artifacts/resolved-runtime-private.json' `
  :app:writeReleaseRedistributionInputs
```

That intermediate JSON includes local artifact paths and must remain private. It lists resolved external Maven inputs; AGP's autolinked project modules are reviewed through their npm/source inputs to avoid selecting ambiguous secondary variants. It is not automatically admitted to the companion bundle. Review all resolved AAR/JAR/native notices and the npm packages included in the JS/native build (include React Native/Hermes/Expo, autolinked modules and their embedded libraries). A direct-dependency list and `package-lock.json` alone do not prove complete notice coverage. Conditional Fresco/JSC dependencies must follow the actual build flags, rather than a generic list. Preserve required notices even if AGP removes them from the APK.

Create a public inventory JSON with *exactly* these fields:

```json
{
  "schema": 1,
  "appSourceCommit": "<40 lowercase hex characters>",
  "apkSha256": "<SHA256 of the final APK>",
  "reviewed": false,
  "components": [
    {
      "id": "org.conscrypt:conscrypt-android:2.5.3",
      "sha256": "<SHA256 of that resolved AAR>",
      "license": "Apache-2.0 AND upstream BoringSSL terms",
      "noticeFiles": ["conscrypt/NOTICE.txt", "conscrypt/LICENSE.txt", "boringssl/LICENSE.txt"],
      "reviewed": false
    }
  ]
}
```

Every resolved dependency gets an entry, including component-specific embedded notices. `noticeFiles` are UTF-8 `.txt`/`.md` paths relative to a separate reviewed notice directory. Only explicitly named notice text is copied. Google Play authorization is an external Google SDK under its applicable terms, not original 0BSD code. The MaterialIcons font entry is `npm:react-native-vector-icons@10.2.0`; use the exact font SHA256 for that bundled asset. Set reviewed flags only after examining the actual components and their applicable terms. The script cannot establish that a manually supplied inventory lists every shipped library.

Record a real modified-library recombination using [the rebuild instructions](rebuild-spake2.md):

```powershell
python tools/release-compliance/release_bundle.py modified-sources `
  --directory /work/spake2 --output artifacts/modified-spake2-source.zip
python tools/release-compliance/release_bundle.py record-recombination `
  --apk artifacts/final.apk --app-source artifacts/application-source.zip `
  --modified-apk artifacts/modified.apk --modified-aar artifacts/modified-spake2.aar `
  --modified-source artifacts/modified-spake2-source.zip `
  --own-key-signed --installation-instructions-reviewed `
  --output artifacts/recombination-check.json
```

Assemble and check the companion:

```powershell
python tools/release-compliance/release_bundle.py bundle `
  --apk artifacts/final.apk --app-source artifacts/application-source.zip `
  --spake-source artifacts/spake2-corresponding-source.zip `
  --spake-aar /resolved/cache/spake2-android-2.2.1.aar `
  --conscrypt-aar /resolved/cache/conscrypt-android-2.5.3.aar `
  --strip-tool /installed/ndk/toolchain/llvm-strip `
  --inventory artifacts/reviewed-public-inventory.json `
  --notices /reviewed/runtime-notices --recombination artifacts/recombination-check.json `
  --require-ready --output artifacts/apk-companion.zip
python tools/release-compliance/release_bundle.py check artifacts/apk-companion.zip
```

The generator checks the exact source snapshots, known AAR hashes, Conscrypt's BoringSSL pin, native library provenance and the packaged font hash. Native libraries must equal the pinned AAR bytes. When AGP has stripped a library, the optional `--strip-tool` must name the existing `llvm-strip` executable used by the build (`llvm-strip.exe` on Windows). The generator writes bounded AAR library inputs to temporary files and runs that executable with `--strip-unneeded -o` twice, each with a 30-second timeout. Both outputs must equal the APK library byte for byte. The manifest records the original and packaged SHA256 hashes, the transformation and the executable's SHA256 without exporting local SDK paths. Pinned AAR hash requirements remain unchanged; supplying a tool does not allow arbitrary native changes or operator assertions. Omit the option when all native libraries match directly.

The generator rejects unsafe/duplicate/oversized ZIP entries, unexpected public inventory fields, private app-source files, missing notices and stale recombination evidence. It copies no arbitrary directories. Omitting `--require-ready` creates a review candidate with explicit outstanding gates; it is not a verified distribution checkpoint. `check` verifies bundle hashes and reports recorded gates; it does not replace source provenance, dependency review, signature/security testing or legal judgment.

Keep the download page's source/notice links accessible to APK recipients. Do not distribute the APK alone while its corresponding-source or tested-recombination gate remains open. Actual Google account registration, Telegram pairing, installation and call audio acceptance are separate product checks.
