import assert from "node:assert/strict"
import { describe, test } from "node:test"
import request from "supertest"
import { createApp } from "../src/app.js"

const TOKEN = "test-backend-token-that-is-long-enough"
const config = {
  backendToken: TOKEN,
  maxUploadBytes: 25 * 1024 * 1024,
  smtpFromName: "Voice Recorder Plus",
  smtpFromEmail: "verified@example.com"
}

function testApp(sendMail = async (message) => ({ accepted: [message.to] })) {
  return createApp({
    config,
    mailer: { sendMail },
    rateLimitEnabled: false
  })
}

function testAppWithConfig(overrides, sendMail = async (message) => ({ accepted: [message.to] })) {
  return createApp({
    config: { ...config, ...overrides },
    mailer: { sendMail },
    rateLimitEnabled: false
  })
}

function validRequest(app, token = TOKEN) {
  return request(app)
    .post("/email")
    .set("Authorization", `Bearer ${token}`)
    .field("recipient", "recipient@example.com")
    .field("subject", "Recording - 2026-07-13 10:20:30")
    .field("timestamp", "2026-07-13 10:20:30")
    .field("mimeType", "audio/mp4")
    .attach("recording", Buffer.from("audio-data"), {
      filename: "recording.m4a",
      contentType: "audio/mp4"
    })
}

describe("Voice Recorder Plus email backend", () => {
  test("reports health without exposing configuration", async () => {
    const response = await request(testApp()).get("/health").expect(200)
    assert.deepEqual(response.body, { status: "ok" })
  })

  test("rejects a missing bearer token before parsing the upload", async () => {
    const response = await request(testApp()).post("/email").expect(401)
    assert.equal(response.body.success, false)
  })

  test("rejects an incorrect bearer token", async () => {
    const response = await validRequest(testApp(), "incorrect-token-that-is-long-enough").expect(401)
    assert.equal(response.body.success, false)
  })

  test("sends a valid recording with the exact subject", async () => {
    let sentMessage
    const app = testApp(async (message) => {
      sentMessage = message
      return { accepted: [message.to], rejected: [] }
    })

    const response = await validRequest(app).expect(200)

    assert.deepEqual(response.body, { success: true, message: "Email sent" })
    assert.equal(sentMessage.subject, "Recording - 2026-07-13 10:20:30")
    assert.equal(sentMessage.attachments[0].filename, "recording.m4a")
    assert.equal(sentMessage.attachments[0].contentType, "audio/mp4")
  })

  test("rejects an invalid recipient", async () => {
    const response = await request(testApp())
      .post("/email")
      .set("Authorization", `Bearer ${TOKEN}`)
      .field("recipient", "not-an-email")
      .field("subject", "Recording - 2026-07-13 10:20:30")
      .field("timestamp", "2026-07-13 10:20:30")
      .field("mimeType", "audio/mp4")
      .attach("recording", Buffer.from("audio-data"), {
        filename: "recording.m4a",
        contentType: "audio/mp4"
      })
      .expect(422)
    assert.equal(response.body.success, false)
  })

  test("rejects a subject that does not match the timestamp", async () => {
    const response = await request(testApp())
      .post("/email")
      .set("Authorization", `Bearer ${TOKEN}`)
      .field("recipient", "recipient@example.com")
      .field("subject", "Arbitrary subject")
      .field("timestamp", "2026-07-13 10:20:30")
      .field("mimeType", "audio/mp4")
      .attach("recording", Buffer.from("audio-data"), {
        filename: "recording.m4a",
        contentType: "audio/mp4"
      })
      .expect(422)
    assert.equal(response.body.success, false)
  })

  test("rejects an unsupported MIME type", async () => {
    const response = await request(testApp())
      .post("/email")
      .set("Authorization", `Bearer ${TOKEN}`)
      .field("recipient", "recipient@example.com")
      .field("subject", "Recording - 2026-07-13 10:20:30")
      .field("timestamp", "2026-07-13 10:20:30")
      .field("mimeType", "application/octet-stream")
      .attach("recording", Buffer.from("audio-data"), {
        filename: "recording.bin",
        contentType: "application/octet-stream"
      })
      .expect(415)
    assert.equal(response.body.success, false)
  })

  test("rejects a missing recording", async () => {
    const response = await request(testApp())
      .post("/email")
      .set("Authorization", `Bearer ${TOKEN}`)
      .field("recipient", "recipient@example.com")
      .field("subject", "Recording - 2026-07-13 10:20:30")
      .field("timestamp", "2026-07-13 10:20:30")
      .field("mimeType", "audio/mp4")
      .expect(422)
    assert.equal(response.body.success, false)
  })

  test("rejects a recording over the configured size limit", async () => {
    const response = await validRequest(testAppWithConfig({ maxUploadBytes: 4 })).expect(413)
    assert.deepEqual(response.body, { success: false, message: "Recording is too large" })
  })

  test("returns failure when Brevo does not accept the recipient", async () => {
    const response = await validRequest(testApp(async () => ({
      accepted: [],
      rejected: ["recipient@example.com"]
    }))).expect(502)
    assert.deepEqual(response.body, { success: false, message: "Email was not accepted" })
  })

  test("returns failure without exposing an SMTP exception", async () => {
    const response = await validRequest(testApp(async () => {
      const error = new Error("sensitive SMTP details")
      error.code = "EAUTH"
      throw error
    })).expect(502)
    assert.deepEqual(response.body, { success: false, message: "Email delivery failed" })
  })
})
