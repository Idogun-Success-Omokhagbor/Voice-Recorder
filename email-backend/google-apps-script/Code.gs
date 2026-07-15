const MAX_ATTACHMENT_BYTES = 14 * 1024 * 1024;
const ALLOWED_MIME_TYPES = {
  "audio/mp4": true,
  "audio/m4a": true,
  "audio/x-m4a": true,
  "audio/mpeg": true,
  "audio/ogg": true,
  "audio/opus": true,
  "application/ogg": true,
  "audio/wav": true,
  "audio/x-wav": true,
  "audio/aac": true,
  "audio/flac": true
};

function jsonResponse_(payload) {
  return ContentService.createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}

function reject_(code) {
  return jsonResponse_({ success: false, code: code });
}

function isValidEmail_(address) {
  return address.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address);
}

function doPost(e) {
  try {
    const request = JSON.parse(e && e.postData ? e.postData.contents : "{}");
    const relaySecret = PropertiesService.getScriptProperties().getProperty("RELAY_SECRET");
    if (!relaySecret || request.secret !== relaySecret) {
      return reject_("AUTH_FAILED");
    }

    const recipient = String(request.to || "").trim().toLowerCase();
    if (!isValidEmail_(recipient)) {
      return reject_("INVALID_RECIPIENT");
    }

    const subject = String(request.subject || "");
    if (!/^Recording - \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(subject)) {
      return reject_("INVALID_SUBJECT");
    }

    const attachment = request.attachment || {};
    const contentType = String(attachment.contentType || "").toLowerCase();
    const base64Content = String(attachment.content || "");
    if (!ALLOWED_MIME_TYPES[contentType] || !base64Content) {
      return reject_("INVALID_ATTACHMENT");
    }

    const estimatedBytes = Math.floor(base64Content.length * 3 / 4);
    if (estimatedBytes > MAX_ATTACHMENT_BYTES) {
      return reject_("ATTACHMENT_TOO_LARGE");
    }

    let bytes;
    try {
      bytes = Utilities.base64Decode(base64Content);
    } catch (error) {
      return reject_("INVALID_ATTACHMENT");
    }
    if (bytes.length > MAX_ATTACHMENT_BYTES) {
      return reject_("ATTACHMENT_TOO_LARGE");
    }
    if (MailApp.getRemainingDailyQuota() < 1) {
      return reject_("QUOTA_EXCEEDED");
    }

    const filename = String(attachment.filename || "recording.m4a")
      .replace(/[\\/\r\n"]/g, "_")
      .slice(0, 255) || "recording.m4a";
    const blob = Utilities.newBlob(bytes, contentType, filename);
    MailApp.sendEmail({
      to: recipient,
      subject: subject,
      body: String(request.text || "Voice Recorder Plus recording attached."),
      attachments: [blob],
      name: "Voice Recorder Plus"
    });

    return jsonResponse_({ success: true });
  } catch (error) {
    console.error("Voice Recorder Plus relay failed", error);
    return reject_("SEND_FAILED");
  }
}
