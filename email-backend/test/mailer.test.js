import assert from "node:assert/strict"
import { describe, test } from "node:test"
import { createBrevoApiTransport, createGoogleAppsScriptTransport } from "../src/mailer.js"

const config = { brevoApiKey: "test-api-key" }

describe("Brevo API transport", () => {
  test("verifies the API key over HTTPS", async () => {
    let request
    const transport = createBrevoApiTransport(config, async (url, options) => {
      request = { url, options }
      return new Response("{}", { status: 200 })
    })

    assert.equal(await transport.verify(), true)
    assert.equal(request.url, "https://api.brevo.com/v3/account")
    assert.equal(request.options.headers["api-key"], "test-api-key")
  })

  test("sends a base64 attachment and returns an accepted recipient", async () => {
    let requestBody
    const transport = createBrevoApiTransport(config, async (_url, options) => {
      requestBody = JSON.parse(options.body)
      return new Response(JSON.stringify({ messageId: "message-id" }), {
        status: 201,
        headers: { "content-type": "application/json" }
      })
    })

    const result = await transport.sendMail({
      from: { name: "Voice Recorder Plus", address: "verified@example.com" },
      to: "recipient@example.com",
      subject: "Recording - 2026-07-13 10:20:30",
      text: "Recording attached.",
      attachments: [{
        filename: "recording.m4a",
        content: Buffer.from("audio-data"),
        contentType: "audio/mp4"
      }]
    })

    assert.deepEqual(result.accepted, ["recipient@example.com"])
    assert.deepEqual(requestBody.sender, {
      name: "Voice Recorder Plus",
      email: "verified@example.com"
    })
    assert.equal(requestBody.attachment[0].name, "recording.m4a")
    assert.equal(requestBody.attachment[0].content, Buffer.from("audio-data").toString("base64"))
  })

  test("rejects an HTTP authentication failure", async () => {
    const transport = createBrevoApiTransport(config, async () => {
      return new Response("{}", { status: 401 })
    })

    await assert.rejects(() => transport.verify(), (error) => {
      assert.equal(error.code, "BREVO_HTTP_401")
      return true
    })
  })

  test("rejects a success response without a message ID", async () => {
    const transport = createBrevoApiTransport(config, async () => {
      return new Response("{}", {
        status: 201,
        headers: { "content-type": "application/json" }
      })
    })

    await assert.rejects(
      () => transport.sendMail({
        from: { name: "Voice Recorder Plus", address: "verified@example.com" },
        to: "recipient@example.com",
        subject: "Recording - 2026-07-13 10:20:30",
        text: "Recording attached.",
        attachments: []
      }),
      (error) => {
        assert.equal(error.code, "BREVO_INVALID_RESPONSE")
        return true
      }
    )
  })
})

describe("Google Apps Script transport", () => {
  const googleConfig = {
    googleAppsScriptUrl: "https://script.google.com/macros/s/deployment-id/exec",
    googleAppsScriptSecret: "test-google-apps-script-secret"
  }

  test("sends a protected base64 attachment", async () => {
    let request
    const transport = createGoogleAppsScriptTransport(googleConfig, async (url, options) => {
      request = { url, options, body: JSON.parse(options.body) }
      return new Response(JSON.stringify({ success: true }), {
        status: 200,
        headers: { "content-type": "application/json" }
      })
    })

    const result = await transport.sendMail({
      to: "recipient@example.com",
      subject: "Recording - 2026-07-13 10:20:30",
      text: "Recording attached.",
      attachments: [{
        filename: "recording.m4a",
        content: Buffer.from("audio-data"),
        contentType: "audio/mp4"
      }]
    })

    assert.deepEqual(result.accepted, ["recipient@example.com"])
    assert.equal(request.url, googleConfig.googleAppsScriptUrl)
    assert.equal(request.body.secret, googleConfig.googleAppsScriptSecret)
    assert.equal(request.body.attachment.content, Buffer.from("audio-data").toString("base64"))
    assert.equal(request.options.redirect, "follow")
  })

  test("rejects a negative relay response", async () => {
    const transport = createGoogleAppsScriptTransport(googleConfig, async () => {
      return new Response(JSON.stringify({ success: false }), {
        status: 200,
        headers: { "content-type": "application/json" }
      })
    })

    await assert.rejects(
      () => transport.sendMail({
        to: "recipient@example.com",
        subject: "Recording - 2026-07-13 10:20:30",
        text: "Recording attached.",
        attachments: [{
          filename: "recording.m4a",
          content: Buffer.from("audio-data"),
          contentType: "audio/mp4"
        }]
      }),
      (error) => {
        assert.equal(error.code, "GOOGLE_APPS_SCRIPT_REJECTED")
        return true
      }
    )
  })
})
