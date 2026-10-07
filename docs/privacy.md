# wa-reco privacy and data handling

Updated2026-10-07. This describes the source implementation; successful device recording and provider uploads require separate testing.

## Recording and permissions

Audio and recording metadata are saved in the app's private storage on your phone. The app has no maintainer recording server or shared backup destination. Manual capture starts when you press Record. Automatic capture requires you to enable it and uses supported WhatsApp/WhatsApp Business call notifications and fresh Android call/audio ownership signals. Recording has a visible Android foreground notification and can be stopped.

Android notification access is broad. The app filters only WhatsApp and WhatsApp Business structural call notifications; it does not read, store or transmit message content, contacts or phone numbers from those notifications. Microphone access records audio. Developer settings and wireless debugging activate a local helper with Android's shell permissions after your approval; the app does not obtain WhatsApp encryption keys or private WhatsApp files.

App diagnostics may contain recording IDs, duration, byte counts, channel/silencing/gap status and app-owned error codes. They do not intentionally log audio, bot tokens, Google access tokens, private-chat messages or raw provider responses. Do not post credentials or private recordings in public issue reports.

## Optional cloud backup

Google Drive and Telegram are independent and off until you explicitly enable the corresponding backup. Enabling includes existing and future completed recordings. Each service receives the audio you choose to back up; review that choice before enabling.

- **Google Drive:** Google handles account selection and authorization. The app uses the resulting access grant only for the selected Drive backup destination and permitted file operations. It sends finalized audio directly to Google's API. Account/folder connection and actual upload acceptance are currently unresolved for the publisher build. Google's own terms and privacy policy apply.
- **Telegram:** You provide a dedicated bot token and confirm a private chat with a temporary Start link. The app verifies bot/chat identity and sends finalized audio directly to Telegram as lossless WAV parts. Bot credentials, private-chat binding, temporary challenges and upload receipts are stored in an authenticated AES-GCM journal with an Android Keystore key, in storage excluded from Android backup. Private bot chats are Telegram cloud chats, not Secret Chats. Telegram's own terms and privacy policy apply.

The app's normal Android automatic backup is disabled. Tokens and keys are not included in the public source, APK release metadata or app status. No maintainer `.env` credential is embedded in the APK.

## Your controls

You can listen, share or delete local recordings in the app. Sharing gives the chosen receiving app access to that file. Disable a backup to stop scheduled uploads, or disconnect its account/bot to remove the active connection. An in-progress request already accepted by the provider cannot be recalled. These actions do not delete files already uploaded to Drive or Telegram; manage those in your own provider account.

Telegram's encrypted receipt history is retained after disconnect so confirmed parts are not silently resent when you reconnect the same destination. Reinstalling or clearing app data removes local settings, credentials and local files, subject to Android's storage behavior; it does not delete provider copies. Revoke a compromised bot token through BotFather and revoke Google access through your Google account controls.

## Website and third-party services

The presentation website uses local browser storage for its language preference. It has no project analytics, recording upload form or microphone access. Hosting providers handle their own platform/network data. Download/source/issue links lead to GitHub; bot setup leads to Telegram; cloud connection uses Google services.

Service policies: [Google](https://policies.google.com/privacy), [Telegram](https://telegram.org/privacy), [GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). Source and questions: [project repository](https://github.com/didi6135/WhatsAppCallRecorder).
