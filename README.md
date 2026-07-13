# Voice Recorder Plus

Voice Recorder Plus is a fork of [Fossify Voice Recorder](https://github.com/FossifyOrg/Voice-Recorder).

Modified source repository:

https://github.com/Idogun-Success-Omokhagbor/Voice-Recorder

## Recording storage

The default recording folder is:

```text
Music/Voice Recorder Plus
```

On Android 10 and newer, default recordings are created through MediaStore. The first-run setup therefore does not open Android's general "Use this folder" picker. Android still controls the wording of microphone and notification permission dialogs.

Recordings in the previous default folders `Music/Recordings` and `Music/Fossify Voice Recorder` remain discoverable in Player. They are not moved automatically. This avoids file-loss risk and avoids treating an unrelated custom folder named `Recordings` as an app folder.

## Silent email backend

Android email applications cannot silently send a message or reliably confirm that it was sent. The Record and email feature therefore requires a backend that sends the message and returns a machine-readable result.

Configure the endpoint through a Gradle property or environment variable:

```text
VOICE_RECORDER_PLUS_EMAIL_BACKEND_URL=https://example.com/voice-recorder-plus/email
```

Also set `VOICE_RECORDER_PLUS_EMAIL_BACKEND_TOKEN` through an untracked local environment or CI secret. Both the URL and token are required by default. Never place the token in source control, `gradle.properties`, a command committed to shell history, or documentation.

For a deliberately unauthenticated backend, the build must explicitly set:

```text
VOICE_RECORDER_PLUS_EMAIL_ALLOW_UNAUTHENTICATED=true
```

The default is `false`. Public production endpoints should not use unauthenticated mode. HTTP is rejected except for `localhost`, `127.0.0.1`, and Android emulator host `10.0.2.2`; production endpoints must use HTTPS.

The app sends:

```http
POST /configured-path HTTP/1.1
Authorization: Bearer <configured token>
Content-Type: multipart/form-data
```

Multipart fields:

```text
recipient  saved recipient email address
subject    Recording - yyyy-MM-dd HH:mm:ss
timestamp  yyyy-MM-dd HH:mm:ss
mimeType   recording MIME type
recording  audio attachment
```

Accepted client MIME types are `audio/mp4`, `audio/m4a`, `audio/x-m4a`, `audio/ogg`, `audio/opus`, and `application/ogg`. The client rejects known attachments larger than 25 MiB. The backend must enforce the same or a stricter size and MIME policy. The client can send the multipart request with HTTP chunked transfer encoding, so the backend and any reverse proxy in front of it must accept chunked request bodies.

Expected success response:

```json
{
  "success": true,
  "message": "Email sent"
}
```

Expected failure response:

```json
{
  "success": false,
  "message": "Reason"
}
```

`message` is optional and is not shown directly to users. Success requires a 2xx status, valid JSON, and an exact Boolean `success` property equal to `true`. String values such as `"true"`, missing properties, malformed JSON, and every non-2xx response fail safely. Recommended status codes are 400 or 422 for invalid input, 401 or 403 for authentication failure, and 5xx for server failure.

Connection timeout is 15 seconds and response-read timeout is 45 seconds. The app does not retry automatically because an uncoordinated retry could send the same email twice. A failed request leaves the finalized recording in Player, keeps the app open, and does not vibrate.

The backend must authenticate and authorize the bearer token server-side, validate the recipient and attachment, rate-limit requests, and return the documented JSON. A token embedded at build time can be extracted from an APK, so it must be scoped, revocable, monitored, and treated as an application credential rather than a private user secret.

## Local development

Set the backend URL and token in the current shell or an untracked CI secret store, then build. For an Android emulator, a host service can use a URL such as `http://10.0.2.2:8080/email`; the token is still required unless the explicit unauthenticated override is enabled.

No backend URL or token is included in this repository. Without both values, Record and email intentionally saves locally and displays a configuration error. A real email delivery must be verified against the production backend before release.

## Package and updates

This fork intentionally preserves application ID `org.fossify.voicerecorder` (Option A: upgrade compatibility). A previous client APK can be upgraded only when the new APK is signed with the same signing key. Builds with this ID cannot coexist with another installed app using the same ID, including a differently signed official Fossify build.

No release signing key is stored in this repository. Unsigned release APKs are build artifacts only and must be signed with the client's existing key before delivery.

## Build and verification

Java 17 and Android SDK 36 are required. On Unix-like systems, run:

```bash
./gradlew assembleFossDebug
./gradlew assembleFossRelease
./gradlew lintFossDebug
./gradlew detekt
./gradlew testFossDebugUnitTest
```

On Windows PowerShell, use `.\gradlew.bat` with the same task names.

GitHub Actions runs the debug build, lint, Detekt, and JVM tests on every push and pull request and uploads the debug APK when all checks pass.

## Runtime limitation

Recorder state is owned by the running service, and start/stop actions are idempotent. UI and widget actions query or command the service instead of relying on static process state. If Android force-kills the entire process during an active recording or upload, in-memory work cannot resume; the app starts a fresh recording on the next launch when automatic recording is enabled.
