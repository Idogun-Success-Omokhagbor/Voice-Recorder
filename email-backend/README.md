# Voice Recorder Plus email backend

This service receives the authenticated multipart request from the Android app and relays the recording through Brevo SMTP. SMTP credentials stay on the server and must never be embedded in the APK or committed to Git.

## Required environment

- `BACKEND_BEARER_TOKEN`: random value of at least 32 characters; the Android build receives the same value through `VOICE_RECORDER_PLUS_EMAIL_BACKEND_TOKEN`.
- `SMTP_USER`: Brevo SMTP login.
- `SMTP_PASS`: Brevo SMTP key.
- `SMTP_FROM_EMAIL`: sender address verified in Brevo.

Optional values are documented in `.env.example`. Port 587 uses STARTTLS and requires a valid TLS connection.

## Local verification

```powershell
pnpm install --frozen-lockfile
pnpm test
pnpm start
```

The service exposes `GET /health` and authenticated `POST /email`. It accepts one recording up to 25 MiB, validates the recipient, timestamp, exact subject and MIME type, and rate-limits requests. A response of `{"success":true}` means the Brevo SMTP relay accepted the recipient; final inbox delivery must still be checked through Brevo logs and the recipient mailbox.
