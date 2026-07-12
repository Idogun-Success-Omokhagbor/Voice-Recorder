# Voice Recorder Plus

Voice Recorder Plus is a fork of [Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder).

Modified source repository:

https://github.com/Idogun-Success-Omokhagbor/Voice-Recorder

## Recording Folder

The default recording folder is:

```text
Music/Voice Recorder Plus
```

On Android 10 and newer, default recordings are created through MediaStore so the first-run setup does not need to show Android's general folder picker. Android still owns the wording of system permission dialogs.

## Silent Email Sending

Android email apps cannot silently send a message, cannot confirm that the user pressed Send, and cannot return a reliable success result. Voice Recorder Plus therefore uses a configurable HTTPS backend for the Record and email feature.

Set the backend endpoint at build time with either a Gradle property or environment variable:

```text
VOICE_RECORDER_PLUS_EMAIL_BACKEND_URL=https://example.com/voice-recorder-plus/email
```

The app sends a `multipart/form-data` POST containing:

```text
recipient: saved recipient email address
subject: Recording - yyyy-MM-dd HH:mm:ss
timestamp: yyyy-MM-dd HH:mm:ss
mimeType: recording MIME type
recording: audio attachment
```

Expected successful response:

```json
{"success": true}
```

Any non-2xx response, timeout, network failure, authentication failure, malformed response, or `success` value other than `true` is treated as a send failure. On failure, the recording remains saved locally, the app stays open, and no success vibration is triggered.

Do not commit backend credentials or API keys to this repository.
