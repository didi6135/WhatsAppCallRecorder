# wa-reco presentation website

Buildless bilingual site. Serve `dist/` directly; no package installation or build step is needed. Site registration, hosting identity, deployment, and the signed public APK are managed separately by the release owner.

## Local preview

From the repository root:

```powershell
python -m http.server 8768 --bind 127.0.0.1 --directory website/dist
```

Open `http://127.0.0.1:8768/`. The language preference is saved locally. All illustrative app details are synthetic: the page never accesses the microphone, Android permissions, notifications, or audio files.

Run the existing Python Playwright installation without adding dependencies:

```powershell
python website/check_site.py --url http://127.0.0.1:8768/ --output artifacts/website
```

## Public download metadata

The release owner updates `dist/release-metadata.json` when a release is published, after verifying the signed public APK and its source-and-notice companion. Before publication it can contain `{"status":"pending"}`; that state leaves downloads disabled. A published release uses verified metadata such as:

```json
{
  "status": "ready",
  "url": "https://example.com/releases/wa-reco.apk",
  "version": "1.5.0",
  "sizeBytes": 12345678,
  "sha256": "<exact 64-character hexadecimal SHA-256>",
  "filename": "wa-reco-1.5.0.apk",
  "companion": {
    "url": "https://example.com/releases/wa-reco-source-and-notices.zip",
    "filename": "wa-reco-source-and-notices.zip",
    "sizeBytes": 12345678,
    "sha256": "<exact 64-character hexadecimal ZIP SHA-256>"
  },
  "sourceUrl": "https://github.com/didi6135/WhatsAppCallRecorder/tree/<exact 40-character public source commit>"
}
```

The example is a schema illustration, not release evidence. The page requires both the APK and companion ZIP metadata, plus a source URL for the exact 40-character public GitHub commit. It accepts HTTPS file URLs or same-origin relative paths, a bounded version label, integer file sizes from 1 byte to 512 MiB, and exact 64-character hexadecimal checksums. Optional filenames must be safe ASCII names ending in the matching `.apk` or `.zip` extension. Invalid, missing, or pending metadata leaves every release download disabled. Rendering does not download or independently verify either file; the release owner verifies the published bytes, source correspondence, notices, and checksums.

The browser checker isolates its layout/language baseline with an explicit synthetic pending fixture and retains invalid/ready fixture cases. It then removes those routes and checks the actual served metadata against the displayed URLs, version, file sizes, checksums, companion and exact source commit. Actual ready-section captures are named `actual-ready-*.png`; `synthetic-ready-*.png` remain illustrative test data. These DOM checks do not download or verify APK/ZIP bytes.

## Claim boundaries

- Samsung S26 Ultra / Android 16: recording reported working by the owner on that device.
- Android 14 / 15: experimental, requires device and audio verification.
- Android 13 and earlier: microphone only; no supported both-party same-device WhatsApp route.
- iPhone: this app provides no same-phone WhatsApp call recording.
- Initial helper activation needs Wi-Fi and Wireless debugging. An activated detached helper can continue without Wi-Fi; reboot or helper loss requires reactivation. WhatsApp still needs Internet.
- Local recording files remain on the phone. Google Drive registration and real-upload verification are pending. Personal private-bot Telegram backup is implemented with explicit opt-in, encrypted credentials and a durable queue; real Telegram pairing/upload acceptance remains pending. Local implementation and synthetic tests do not establish provider acceptance.

The public GitHub repository remains `didi6135/WhatsAppCallRecorder`. This website contains no OAuth credentials, bot tokens, backend, analytics, or external fonts.
