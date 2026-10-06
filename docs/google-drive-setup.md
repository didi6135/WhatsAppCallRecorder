# Google Drive: independent accounts and app registration

This optional integration uses the native Google AuthorizationClient hosted folder picker and only `https://www.googleapis.com/auth/drive.file`. No backend, web OAuth redirect, API key or client secret belongs in the APK. Completed WAV files remain locally available. Backup starts only after the person chooses an account/folder and explicitly enables uploading existing and future completed recordings.

## Each user's account and folder

1. Open Settings and choose Connect Drive. Select your own Google account in Google's account selector and approve the app's requested access.
2. Select one writable Drive folder using Google's hosted picker.
3. Explicitly enable uploading existing and future completed recordings. Account/folder selection alone does not enable backup.
4. Check upload status; recording and local playback remain available while backup is disconnected or offline. Pending uploads need internet through Wi-Fi or mobile data.

The phone sends recordings directly to that selected account/folder. There is no maintainer storage account, shared user token or upload backend. Each installation has its own destination, private queue and Google authorization. Ordinary app users do **not** need a Google Cloud developer project.

## Current publisher APK identity

- Release application ID: `com.didi4164.WhatsAppCallRecorder.standalone`
- Persistent release certificate SHA-1: `1C:89:6A:4B:F2:3C:63:67:0F:7C:6F:F0:BE:02:FF:4A:FF:5D:15:7D`
- Certificate SHA-256: `2668e4ec607dba1e1a7639bc5b21e8f366c3e461111a54147a2626efea08922f`

These fingerprints identify the public signing certificate, not private signing material. Register a distinct Android OAuth client for any debug or Google Play signing combination; Google Play's signing certificate can differ from the sideload certificate.

## Publisher or fork maintainer configuration

1. Choose or create a dedicated Google Cloud project for the application. Its maintainers manage the app registration; this does not give them access to users' Drive accounts or select a shared upload destination.
2. Enable Google Drive API (`drive.googleapis.com`) and Google Picker API (`picker.googleapis.com`).
3. Configure Google Auth Platform branding, homepage/privacy policy/terms, support contact, audience and the Drive file scope. For public Google-account use, choose External and complete Google's production publishing requirements. Testing is limited to listed test users and is not general public availability; production OAuth publishing/review is a separate release step.
4. Create an **Android** OAuth client with the exact installed package and signing SHA-1. The values above apply only to the current publisher APK. AuthorizationClient discovers this registration from the installed package and signing certificate; there is no secret to copy into app configuration.
5. Install the signed release on a phone with Google Play services. Choose Connect Drive in Settings, personally approve Google consent and select exactly one writable folder. Cancel must preserve the previous destination.
6. Explicitly enable the backup of existing and future completed recordings. Test with a disclosed synthetic recording first. Verify the chosen account/folder, remote WAV byte count/checksum and successful upload state, including an interrupted-network retry and process restart.

Disconnect stops local scheduling and clears the selected destination. It does not delete local recordings or uploaded Drive files. Google account permissions can also be removed in the person's Google account. Reauthorization is required when Google consent expires or is revoked; a background worker never opens consent UI itself.

## Build a fork using your own registration

Follow [the build instructions](../README.md#build-your-own-android-app), choose a distinct `waRecorder.applicationId` and sign with your own key. Determine the built APK's actual package and certificate fingerprint, then register those exact values in your own Cloud project. The release/debug suffix and Google Play app-signing certificate matter. Retain the same key for updates to your fork.

The native AuthorizationClient flow has no runtime field for selecting an arbitrary Android OAuth client ID. Entering someone else's ID cannot substitute for registration matching the installed package/certificate. A client secret or personal maintainer account is not needed in the APK. Users of a registered fork still connect their own Google accounts/folders normally.

## Acceptance limits

Compilation, unit tests and a mock HTTP server do not establish Google OAuth registration, real folder access or a real upload. Current source/review APK implementation is complete locally; real Google authorization and upload acceptance remain pending. Record these results separately. Test a disclosed synthetic recording in the tester's own account; do not upload private recordings or accept Google consent using an agent's account as a validation shortcut.

## Primary references

- https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker#use_the_google_picker_with_android_apps
- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://developers.google.com/workspace/drive/api/guides/manage-uploads
- https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get
