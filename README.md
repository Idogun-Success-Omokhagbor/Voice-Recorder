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

## Email handoff

Record and email finalizes the recording, then opens the device's default email application with the recipient, subject, and audio attachment filled in. If no default email application is configured, Android offers the installed email applications. The user remains responsible for tapping Send in the email application.

This project has no email server, relay, provider credential, backend token, or network API dependency. If the device has no compatible email application, the recording remains saved locally and the app displays an error.

## Background recording warning

The optional warning is enabled by default. After 60 accumulated minutes of active background recording, the app raises one high-priority warning for that recording. The warning cannot be dismissed from the app and provides Continue, Save and exit, and Exit actions. Android versions that restrict full-screen notifications still retain the ongoing warning notification and its three actions.

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

Recorder state is owned by the running service, and start/stop actions are idempotent. UI and widget actions query or command the service instead of relying on static process state. If Android force-kills the entire process during an active recording, in-memory work cannot resume; the app starts a fresh recording on the next launch when automatic recording is enabled.
