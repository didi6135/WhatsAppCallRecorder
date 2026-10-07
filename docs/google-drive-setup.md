# Google Drive: independent accounts and app registration

This optional integration uses the native Google AuthorizationClient hosted folder picker and only `https://www.googleapis.com/auth/drive.file`. No backend, web OAuth redirect, API key or client secret belongs in the APK. Completed WAV files remain locally available. Backup starts only after the person chooses an account/folder and explicitly enables uploading existing and future completed recordings.

## Each user's account and folder

1. Open Settings and choose Connect Drive. Select your own Google account in Google's account selector and approve the app's requested access.
2. Select one writable Drive folder using Google's hosted picker.
3. Explicitly enable uploading existing and future completed recordings. Account/folder selection alone does not enable backup.
4. Check upload status; recording and local playback remain available while backup is disconnected or offline. Pending uploads need internet through Wi-Fi or mobile data.

The phone sends recordings directly to that selected account/folder. There is no maintainer storage account, shared user token or upload backend. Each installation has its own destination, private queue and Google authorization. Ordinary app users do **not** need a Google Cloud developer project.

The user's part is choosing an account and approving access. The publisher separately registers the Android app with Google using its package name and signing certificate so Google can identify the app making that request. Registering the app does not select the user's storage account or replace their consent; users do not need to register an app or complete a developer verification process themselves.

This app requests only `drive.file`, which Google classifies as **non-sensitive**, so it avoids sensitive/restricted-scope verification. This does not exempt a public production app from publisher requirements: [Google's OAuth policy](https://developers.google.com/identity/protocols/oauth2/policies) requires verified branding and a public homepage on a verified domain owned by the publisher, describing the app and linking to its privacy policy and terms. Android client registration, publisher brand/domain verification and each user's consent are separate steps. See [Drive scope classifications](https://developers.google.com/workspace/drive/api/guides/api-specific-auth) and [branding requirements](https://support.google.com/cloud/answer/15549049?hl=en).

## Current publisher APK identity

- Release application ID: `com.didi4164.WhatsAppCallRecorder.standalone`
- Persistent release certificate SHA-1: `1C:89:6A:4B:F2:3C:63:67:0F:7C:6F:F0:BE:02:FF:4A:FF:5D:15:7D`
- Certificate SHA-256: `2668e4ec607dba1e1a7639bc5b21e8f366c3e461111a54147a2626efea08922f`

These fingerprints identify the public signing certificate, not private signing material. Register a distinct Android OAuth client for any debug or Google Play signing combination; Google Play's signing certificate can differ from the sideload certificate.

## Publisher or fork maintainer configuration

1. Choose or create a dedicated Google Cloud project for the application. Its maintainers manage the app registration; this does not give them access to users' Drive accounts or select a shared upload destination.
2. Enable Google Drive API (`drive.googleapis.com`) and Google Picker API (`picker.googleapis.com`).
3. Configure Google Auth Platform branding, support contact and only the non-sensitive `drive.file` scope. Provide a public homepage on your verified owned domain, with app information and privacy/terms links. Host the privacy policy on the homepage's domain and use the same privacy URL in Branding. Register the relevant authorized domains and verify ownership as required. A GitHub repository/blob returning HTTP 200 does not establish compliance with the verified-owned-domain requirement.
4. In **Audience**, choose **External** and select **Publish app** for general Google-account use; confirm status **In production**. **Testing** is limited to listed test users and is not general public availability.
5. In **Branding**, complete **Verify Branding** and resolve any reported issues. Once status is **Ready to publish**, select **Publish branding**. This publishes the verified brand; it is separate from Audience's **Publish app** and does not require adding sensitive/restricted scopes.
6. Create an **Android** OAuth client with the exact installed package and signing SHA-1. The values above apply only to the current publisher APK. AuthorizationClient discovers this registration from the installed package and signing certificate; there is no secret to copy into app configuration.
7. Install the signed release on a phone with Google Play services. Choose Connect Drive in Settings, personally approve Google consent and select exactly one writable folder. Cancel must preserve the previous destination.
8. Explicitly enable the backup of existing and future completed recordings. Test with a disclosed synthetic recording first. Verify the chosen account/folder, remote WAV byte count/checksum and successful upload state, including an interrupted-network retry and process restart.

Disconnect stops local scheduling and clears the selected destination. It does not delete local recordings or uploaded Drive files. Google account permissions can also be removed in the person's Google account. Reauthorization is required when Google consent expires or is revoked; a background worker never opens consent UI itself.

## Build a fork using your own registration

Follow [the build instructions](../README.md#build-your-own-android-app), choose a distinct `waRecorder.applicationId` and sign with your own key. Determine the built APK's actual package and certificate fingerprint, then register those exact values in your own Cloud project. The release/debug suffix and Google Play app-signing certificate matter. Retain the same key for updates to your fork.

The native AuthorizationClient flow has no runtime field for selecting an arbitrary Android OAuth client ID. Entering someone else's ID cannot substitute for registration matching the installed package/certificate. A client secret or personal maintainer account is not needed in the APK. Users of a registered fork still connect their own Google accounts/folders normally.

## Connection troubleshooting

If Google's **403 `access_denied`** page explicitly says the app is being tested and only approved testers may access it, check **Audience** in the Cloud project containing the matching Android client. For a limited test, add that Google account to **Test users**; for public use, publish the app and confirm **In production**. Then start a fresh connection attempt in the phone app. This diagnosis applies to that explicit Testing message, not every 403.

The earlier generic Google status **8** is the SDK's `INTERNAL_ERROR`, not proof that the user failed to approve access or that app registration or verification is the cause. The SDK can also produce this status when the returned authorization result is missing. Its underlying cause remains unconfirmed; keep it separate from an explicit Testing rejection.

## Acceptance limits

Compilation, unit tests and a mock HTTP server do not establish Google OAuth registration, real folder access or a real upload. The publisher reports that Audience now shows **In production**, and the physical phone reaches Google's hosted folder picker. Confirmed folder storage and actual uploads remain pending. These results do not establish that publisher branding is verified/published or that domain ownership is verified. Record those results separately. Test a disclosed synthetic recording in the tester's own account; do not upload private recordings or accept Google consent using an agent's account as a validation shortcut.

## Primary references

- https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker#use_the_google_picker_with_android_apps
- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- https://support.google.com/cloud/answer/13463073?hl=en
- https://developers.google.com/identity/protocols/oauth2/policies
- https://support.google.com/cloud/answer/15549049?hl=en
- https://support.google.com/cloud/answer/15549945
- https://developers.google.com/android/reference/com/google/android/gms/common/api/CommonStatusCodes#INTERNAL_ERROR
- https://developers.google.com/workspace/drive/api/guides/manage-uploads
- https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get
