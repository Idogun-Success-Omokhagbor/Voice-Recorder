# Voice Recorder Plus Google email relay

Voice Recorder Plus uses a Google Apps Script web app to send a recording without opening an Android email application.

## Deployment

1. Create an Apps Script project in the Google account that will own outgoing email.
2. Replace `Code.gs` with `google-apps-script/Code.gs`.
3. Add a Script Property named `RELAY_SECRET` with a long random value.
4. Deploy the project as a web app that executes as the project owner and is accessible to anyone.
5. Build the Android app with these untracked values:

```text
VOICE_RECORDER_PLUS_EMAIL_RELAY_URL=https://script.google.com/macros/s/DEPLOYMENT_ID/exec
VOICE_RECORDER_PLUS_EMAIL_RELAY_SECRET=the-script-property-value
```

The relay owner must authorize Apps Script to send email. A successful JSON response confirms that Google accepted the send request; final inbox placement remains controlled by the recipient's mail provider.

## Security and ownership

Do not commit the deployment secret. The value is compiled into configured APKs and can be extracted by a determined recipient, so use a dedicated Google account, monitor its sending quota, and rotate the deployment secret when transferring ownership or ending a test period. Google Apps Script enforces the account's daily email quota, and this implementation limits each attachment to 14 MiB.
