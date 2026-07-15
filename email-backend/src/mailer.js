import nodemailer from "nodemailer"

const BREVO_API_BASE_URL = "https://api.brevo.com/v3"

function apiFailure(code) {
  const error = new Error("Email transport request failed")
  error.code = code
  return error
}

function googleRelayFailureCode(body) {
  const relayCode = typeof body?.code === "string" ? body.code.trim() : ""
  return /^[A-Z0-9_]+$/.test(relayCode)
    ? `GOOGLE_APPS_SCRIPT_${relayCode}`
    : "GOOGLE_APPS_SCRIPT_REJECTED"
}

async function requireSuccessfulResponse(response) {
  if (!response.ok) {
    throw apiFailure(`BREVO_HTTP_${response.status}`)
  }
  return response
}

export function createBrevoTransport(config) {
  return nodemailer.createTransport({
    host: config.smtpHost,
    port: config.smtpPort,
    secure: config.smtpSecure,
    requireTLS: !config.smtpSecure,
    auth: {
      user: config.smtpUser,
      pass: config.smtpPass
    },
    connectionTimeout: 15_000,
    greetingTimeout: 15_000,
    socketTimeout: 45_000
  })
}

export function createBrevoApiTransport(config, fetchImplementation = fetch) {
  const headers = {
    accept: "application/json",
    "api-key": config.brevoApiKey,
    "content-type": "application/json"
  }

  return {
    async verify() {
      const response = await fetchImplementation(`${BREVO_API_BASE_URL}/account`, {
        headers,
        signal: AbortSignal.timeout(15_000)
      })
      await requireSuccessfulResponse(response)
      return true
    },

    async sendMail(message) {
      const response = await fetchImplementation(`${BREVO_API_BASE_URL}/smtp/email`, {
        method: "POST",
        headers,
        signal: AbortSignal.timeout(45_000),
        body: JSON.stringify({
          sender: {
            name: message.from.name,
            email: message.from.address
          },
          to: [{ email: message.to }],
          subject: message.subject,
          textContent: message.text,
          attachment: message.attachments.map((attachment) => ({
            name: attachment.filename,
            content: attachment.content.toString("base64")
          }))
        })
      })
      await requireSuccessfulResponse(response)

      const body = await response.json()
      if (typeof body.messageId !== "string" || !body.messageId.trim()) {
        throw apiFailure("BREVO_INVALID_RESPONSE")
      }
      return {
        accepted: [message.to],
        rejected: [],
        messageId: body.messageId
      }
    },

    close() {}
  }
}

export function createGoogleAppsScriptTransport(config, fetchImplementation = fetch) {
  return {
    async verify() {
      return true
    },

    async sendMail(message) {
      const response = await fetchImplementation(config.googleAppsScriptUrl, {
        method: "POST",
        headers: {
          accept: "application/json",
          "content-type": "application/json"
        },
        redirect: "follow",
        signal: AbortSignal.timeout(90_000),
        body: JSON.stringify({
          secret: config.googleAppsScriptSecret,
          to: message.to,
          subject: message.subject,
          text: message.text,
          attachment: {
            filename: message.attachments[0].filename,
            contentType: message.attachments[0].contentType,
            content: message.attachments[0].content.toString("base64")
          }
        })
      })

      if (!response.ok) {
        throw apiFailure(`GOOGLE_APPS_SCRIPT_HTTP_${response.status}`)
      }

      let body
      try {
        body = await response.json()
      } catch {
        throw apiFailure("GOOGLE_APPS_SCRIPT_INVALID_RESPONSE")
      }
      if (body?.success !== true) {
        throw apiFailure(googleRelayFailureCode(body))
      }

      return {
        accepted: [message.to],
        rejected: [],
        messageId: "google-apps-script"
      }
    },

    close() {}
  }
}

export function createEmailTransport(config, fetchImplementation = fetch) {
  if (config.emailTransport === "api") {
    return createBrevoApiTransport(config, fetchImplementation)
  }
  if (config.emailTransport === "google_apps_script") {
    return createGoogleAppsScriptTransport(config, fetchImplementation)
  }
  return createBrevoTransport(config)
}
