import assert from "node:assert/strict"
import { describe, test } from "node:test"
import { loadConfig } from "../src/config.js"

const validEnvironment = {
  BACKEND_BEARER_TOKEN: "backend-token-that-is-at-least-32-chars",
  SMTP_USER: "smtp-user",
  SMTP_PASS: "smtp-password",
  SMTP_FROM_EMAIL: "verified@example.com"
}

describe("email backend configuration", () => {
  test("loads secure Brevo defaults", () => {
    const config = loadConfig(validEnvironment)

    assert.equal(config.smtpHost, "smtp-relay.brevo.com")
    assert.equal(config.smtpPort, 587)
    assert.equal(config.smtpSecure, false)
    assert.equal(config.maxUploadBytes, 14 * 1024 * 1024)
  })

  test("rejects a missing SMTP key", () => {
    assert.throws(
      () => loadConfig({ ...validEnvironment, SMTP_PASS: "" }),
      /SMTP_PASS/
    )
  })

  test("rejects a weak backend token", () => {
    assert.throws(
      () => loadConfig({ ...validEnvironment, BACKEND_BEARER_TOKEN: "short" }),
      /at least 32 characters/
    )
  })

  test("rejects an invalid verified sender address", () => {
    assert.throws(
      () => loadConfig({ ...validEnvironment, SMTP_FROM_EMAIL: "invalid" }),
      /valid email address/
    )
  })

  test("loads HTTPS API transport without SMTP credentials", () => {
    const config = loadConfig({
      BACKEND_BEARER_TOKEN: validEnvironment.BACKEND_BEARER_TOKEN,
      SMTP_FROM_EMAIL: validEnvironment.SMTP_FROM_EMAIL,
      EMAIL_TRANSPORT: "api",
      BREVO_API_KEY: "test-api-key"
    })

    assert.equal(config.emailTransport, "api")
    assert.equal(config.smtpUser, null)
    assert.equal(config.smtpPass, null)
  })

  test("requires a Brevo API key for API transport", () => {
    assert.throws(
      () => loadConfig({
        BACKEND_BEARER_TOKEN: validEnvironment.BACKEND_BEARER_TOKEN,
        SMTP_FROM_EMAIL: validEnvironment.SMTP_FROM_EMAIL,
        EMAIL_TRANSPORT: "api"
      }),
      /BREVO_API_KEY/
    )
  })
})
