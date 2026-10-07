# wa-reco presentation website

Buildless Hebrew/English site using the white, blue and graphite visual language of Codaki. Serve `dist/` directly; no package installation or build step is needed. The canonical address is [wa-reco.codaki.com](https://wa-reco.codaki.com/). Site registration, hosting identity, audience, deployment and the signed public APK are managed separately by the release owner. This source repository is not deployment evidence.

## Website source and Android release boundary

The 15 files in `dist/` exactly match the approved Site source commit `31a94c5471486a0811869b74dd904b1c67d3d1f5`. [SOURCE_PROVENANCE.json](SOURCE_PROVENANCE.json) records each Git blob, mode, byte count and SHA-256, together with the preceding public app release commit. The browser checker was updated for the new layout, local policy pages and three release downloads.

This is a website-only source advance after Android 1.5.4. The `v1.5.4` tag and `d1c9118494edef482ffa3d725fde5f6a7ec5e0f7` remain the frozen public source for that signed Android release. The repository-root `SOURCE_MANIFEST.json` is the unchanged export manifest supplied with that release; it is not a manifest of the newer website-only branch tip. Existing release APK/ZIP assets and their manifests are not rebuilt or replaced by this website change. `dist/release-metadata.json` continues to bind the 1.5.4 downloads to the frozen app source commit.

## Local preview

From the repository root:

```powershell
python -m http.server 8768 --bind 127.0.0.1 --directory website/dist
```

Open `http://127.0.0.1:8768/`. The language preference is saved locally and shared with the local privacy/terms pages. All illustrative app details are synthetic: the page never accesses the microphone, Android permissions, notifications or audio files. The three demo tabs, record button and timer only illustrate the interface.

Run the existing Python Playwright installation without adding dependencies:

```powershell
python website/check_site.py --url http://127.0.0.1:8768/ --output artifacts/website
```

## Public download metadata

The release owner updates `dist/release-metadata.json` when a release is published, after verifying the signed public APK, its source-and-notice companion and the optional application-source ZIP. Before publication it can contain `{"status":"pending"}`; that state leaves downloads disabled. A published release uses verified metadata such as:

```json
{
  "status": "ready",
  "url": "https://example.com/releases/wa-reco.apk",
  "version": "1.5.4",
  "sizeBytes": 12345678,
  "sha256": "<exact 64-character hexadecimal SHA-256>",
  "filename": "wa-reco-1.5.4.apk",
  "companion": {
    "url": "https://example.com/releases/wa-reco-source-and-notices.zip",
    "filename": "wa-reco-source-and-notices.zip",
    "sizeBytes": 12345678,
    "sha256": "<exact 64-character hexadecimal ZIP SHA-256>"
  },
  "applicationSource": {
    "url": "https://example.com/releases/wa-reco-application-source.zip",
    "filename": "wa-reco-application-source.zip",
    "sizeBytes": 12345678,
    "sha256": "<exact 64-character hexadecimal ZIP SHA-256>"
  },
  "sourceUrl": "https://github.com/didi6135/WhatsAppCallRecorder/tree/<exact 40-character public source commit>"
}
```

The example is a schema illustration, not release evidence. The page requires both the APK and companion ZIP metadata, plus a source URL for the exact 40-character public GitHub commit. `applicationSource` is optional, but must pass the same ZIP validation when present. It accepts HTTPS file URLs or same-origin relative paths, a bounded version label, integer file sizes from 1 byte to 512 MiB and exact 64-character hexadecimal checksums. Optional filenames must be safe ASCII names ending in the matching `.apk` or `.zip` extension. Invalid, missing or pending metadata leaves every release download disabled. Rendering does not download or independently verify the files; the release owner verifies the published bytes, source correspondence, notices and checksums.

The browser checker isolates its layout/language baseline with an explicit synthetic pending fixture and retains invalid/ready fixture cases. It then removes those routes and checks the actual served metadata against the displayed URLs, version, file sizes, checksums, companion and exact source commit. Actual ready-section captures are named `actual-ready-*.png`; `synthetic-ready-*.png` remain illustrative test data. These DOM checks do not download or verify APK/ZIP bytes.

## Claim boundaries

- Samsung S26 Ultra / Android 16: automatic recording and optional call names were verified by the owner on that device. Other phones and audio routes need their own tests.
- Android 14 / 15: experimental, requires device and audio verification.
- Android 13 and earlier: microphone only; no supported both-party same-device WhatsApp route.
- iPhone: this app provides no same-phone WhatsApp call recording.
- Initial helper activation needs Wi-Fi and Wireless debugging. An activated detached helper can continue without Wi-Fi; reboot or helper loss requires reactivation. WhatsApp still needs Internet.
- Call names start off and use a bounded display label from a single eligible live WhatsApp/Business call notification. Names are not verified identities; missing or ambiguous labels keep generic titles. Existing recordings and provider receipts are unchanged.
- Local recording files remain on the phone. Selected-folder Google Drive uploads were verified on the owner's S26 in 1.5.2. Default-folder creation/reuse, uploading files with the new call-name labels, other users' accounts and a real 1.5.4 upload-completion notification remain separate device/provider checks. Personal private-bot Telegram backup is implemented with explicit opt-in, encrypted credentials and a durable queue; real Telegram pairing/upload acceptance remains pending. Local implementation and synthetic tests do not establish provider acceptance.

The public GitHub repository remains `didi6135/WhatsAppCallRecorder`. This website contains no OAuth credentials, bot tokens, backend or analytics. Fonts are hosted locally; no remote font service is contacted. Inter and Assistant retain their SIL Open Font License 1.1 terms, full license texts and unchanged font-file provenance in [assets/fonts-notice.txt](dist/assets/fonts-notice.txt). The repository's 0BSD license applies to original project code and does not relicense those fonts or other dependencies.
