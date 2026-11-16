# FRIDAY Google integration setup

FRIDAY uses Android Google Identity Services authorization. It does not use an embedded WebView, a service account, or a backend. The user explicitly taps **Connect Google • Read only** before any Google authorization or data request starts.

## APIs to enable

In the Google Cloud project used for FRIDAY, enable:
- Google Drive API
- Gmail API
- Google Calendar API
- People API

## OAuth consent configuration

1. Configure the Google Auth Platform branding and audience.
2. Add the four scopes below to the Data Access configuration.
3. While the app is in Testing, add the Google account you will use as a test user.
4. Public distribution requires Google's verification for applicable sensitive/restricted scopes. Gmail `gmail.readonly` and Drive `drive.readonly` are restricted scopes. Calendar and Contacts read scopes may also require verification depending on the exact scope classification and publishing setup.

Requested scopes (read-only):
- `https://www.googleapis.com/auth/drive.readonly`
- `https://www.googleapis.com/auth/gmail.readonly`
- `https://www.googleapis.com/auth/calendar.events.readonly`
- `https://www.googleapis.com/auth/contacts.readonly`

No send, modify, create, or delete scopes are requested.

## Android OAuth client

Create an OAuth client of type **Android**:
- Package name: `com.friday.app`
- SHA-1: obtain the signing certificate fingerprint for the APK you install.

For a local debug build, run:

```bash
./gradlew signingReport
```

Use the SHA-1 under the `debug` variant. If you install an APK signed with a different key (for example, a separately signed release APK), register that certificate fingerprint too.

No client secret belongs in the Android app or GitHub repository.

## What the current implementation does

After the user taps Connect and grants consent, FRIDAY uses a short-lived access token in memory to read:
- Up to 20 recently modified Drive file records, and text exports for Google Docs among them.
- Up to 15 recent Gmail message summaries (subject, sender, date, snippet).
- Up to 20 Calendar events.
- Up to 100 Google Contacts connections.

The compact context summary is written to the app-private `filesDir/google_context.txt` and supplied to local Qwen inference when relevant. The OAuth access token is not written to disk. No Google data is sent to a FRIDAY server or a cloud LLM by this integration.

The local text cache is currently app-private but not encrypted at rest. Do not treat app-private storage as equivalent to encryption. A later hardening pass should encrypt this cache with an Android Keystore-backed AES-GCM key.

## Disconnect

Use **Disconnect and delete local Google cache** in FRIDAY setup. Also review/revoke FRIDAY's access in your Google Account's third-party connections page if you want Google-side revocation. Uninstalling the app alone should not be treated as revoking the OAuth grant.

## Limitations in this first integration

- This is an on-demand snapshot sync, not continuous background sync.
- Drive currently imports Google Docs text and file metadata; it does not yet download arbitrary PDFs, Office files, or media.
- Gmail imports recent message summaries/snippets, not full message bodies or attachments.
- The app needs network access during sync. After the snapshot is cached, the selected context is used by the local model.
- The current sync is intentionally bounded to avoid long first-run waits and excessive local storage.
