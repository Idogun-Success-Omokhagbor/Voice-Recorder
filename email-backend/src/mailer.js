import nodemailer from "nodemailer"

const BREVO_API_BASE_URL = "https://api.brevo.com/v3"

function apiFailure(code) {
  const error = new Error("Brevo API request failed")
  error.code = code
  return error
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

export function createEmailTransport(config, fetchImplementation = fetch) {
  return config.emailTransport === "api"
    ? createBrevoApiTransport(config, fetchImplementation)
    : createBrevoTransport(config)
}
