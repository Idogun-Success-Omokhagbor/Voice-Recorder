# Voice Recorder Plus email backend

This service receives the authenticated multipart request from the Android app and relays the recording through a protected Google Apps Script web app. Brevo HTTPS API and SMTP remain available as fallback transports. Provider credentials stay on the server and must never be embedded in the APK or committed to Git.

## Required environment

- `BACKEND_BEARER_TOKEN`: random value of at least 32 characters; the Android build receives the same value through `VOICE_RECORDER_PLUS_EMAIL_BACKEND_TOKEN`.
- `SMTP_FROM_EMAIL`: sender address used by the configured transport.
- `EMAIL_TRANSPORT=google_apps_script` with `GOOGLE_APPS_SCRIPT_URL` and `GOOGLE_APPS_SCRIPT_SECRET`: production HTTPS route for the authorized Gmail sender.
- `EMAIL_TRANSPORT=api` with `BREVO_API_KEY`: Brevo HTTPS API fallback.
- `EMAIL_TRANSPORT=smtp` with `SMTP_USER` and `SMTP_PASS`: for paid Render services or other hosts that allow outbound SMTP.

Optional values are documented in `.env.example`. Port 587 uses STARTTLS and requires a valid TLS connection.

## Local verification

```powershell
pnpm install --frozen-lockfile
pnpm test
pnpm start
```

The service exposes `GET /health` and authenticated `POST /email`. It accepts one recording up to 14 MiB, validates the recipient, timestamp, exact subject and MIME type, and rate-limits requests. A response of `{"success":true}` means the configured provider accepted the message; final inbox delivery must still be checked in the recipient mailbox.
