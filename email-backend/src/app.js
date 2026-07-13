import crypto from "node:crypto"
import path from "node:path"
import express from "express"
import { rateLimit } from "express-rate-limit"
import multer from "multer"
import validator from "validator"

const SUPPORTED_MIME_TYPES = new Set([
  "audio/mp4",
  "audio/m4a",
  "audio/x-m4a",
  "audio/ogg",
  "audio/opus",
  "application/ogg"
])

function bearerMatches(header, expectedToken) {
  const prefix = "Bearer "
  if (!header?.startsWith(prefix)) {
    return false
  }

  const supplied = Buffer.from(header.slice(prefix.length), "utf8")
  const expected = Buffer.from(expectedToken, "utf8")
  return supplied.length === expected.length && crypto.timingSafeEqual(supplied, expected)
}

function isValidTimestamp(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(value)
  if (!match) {
    return false
  }

  const [, year, month, day, hour, minute, second] = match.map(Number)
  const date = new Date(Date.UTC(year, month - 1, day, hour, minute, second))
  return date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day &&
    date.getUTCHours() === hour &&
    date.getUTCMinutes() === minute &&
    date.getUTCSeconds() === second
}

function safeFileName(value) {
  return path.basename(value || "recording").replace(/[\r\n"\\]/g, "_")
}

function fieldValue(body, name) {
  const value = body?.[name]
  return typeof value === "string" ? value.trim() : ""
}

function failure(res, status, message) {
  return res.status(status).json({ success: false, message })
}

export function createApp({ config, mailer, rateLimitEnabled = true }) {
  const app = express()
  app.disable("x-powered-by")
  app.set("trust proxy", 1)

  app.get("/health", (_req, res) => {
    res.json({ status: "ok" })
  })

  const emailLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    limit: 10,
    standardHeaders: "draft-8",
    legacyHeaders: false,
    skip: () => !rateLimitEnabled,
    handler: (_req, res) => failure(res, 429, "Too many requests")
  })

  const upload = multer({
    storage: multer.memoryStorage(),
    limits: {
      fileSize: config.maxUploadBytes,
      files: 1,
      fields: 5,
      parts: 6
    }
  })

  app.post(
    "/email",
    emailLimiter,
    (req, res, next) => {
      if (!bearerMatches(req.get("authorization"), config.backendToken)) {
        res.set("WWW-Authenticate", "Bearer")
        return failure(res, 401, "Authentication failed")
      }
      return next()
    },
    upload.single("recording"),
    async (req, res) => {
      const recipient = fieldValue(req.body, "recipient")
      const timestamp = fieldValue(req.body, "timestamp")
      const mimeType = fieldValue(req.body, "mimeType").toLowerCase()
      const subject = fieldValue(req.body, "subject")

      if (!validator.isEmail(recipient, { allow_utf8_local_part: false })) {
        return failure(res, 422, "Invalid recipient")
      }
      if (!isValidTimestamp(timestamp)) {
        return failure(res, 422, "Invalid timestamp")
      }

      const expectedSubject = `Recording - ${timestamp}`
      if (subject !== expectedSubject) {
        return failure(res, 422, "Invalid subject")
      }
      if (!req.file?.buffer?.length) {
        return failure(res, 422, "Recording is required")
      }
      if (!SUPPORTED_MIME_TYPES.has(mimeType) || req.file.mimetype.toLowerCase() !== mimeType) {
        return failure(res, 415, "Unsupported recording type")
      }

      try {
        const info = await mailer.sendMail({
          from: {
            name: config.smtpFromName,
            address: config.smtpFromEmail
          },
          to: recipient,
          subject: expectedSubject,
          text: "Your Voice Recorder Plus recording is attached.",
          attachments: [{
            filename: safeFileName(req.file.originalname),
            content: req.file.buffer,
            contentType: mimeType
          }]
        })

        const accepted = (info.accepted || []).map((value) => `${value}`.toLowerCase())
        if (!accepted.includes(recipient.toLowerCase())) {
          return failure(res, 502, "Email was not accepted")
        }
        return res.json({ success: true, message: "Email sent" })
      } catch (error) {
        console.error("Brevo SMTP delivery failed", { code: error?.code || "UNKNOWN" })
        return failure(res, 502, "Email delivery failed")
      }
    }
  )

  app.use((error, _req, res, _next) => {
    if (error instanceof multer.MulterError && error.code === "LIMIT_FILE_SIZE") {
      return failure(res, 413, "Recording is too large")
    }
    if (error instanceof multer.MulterError) {
      return failure(res, 400, "Invalid upload")
    }
    console.error("Email backend request failed", { name: error?.name || "Error" })
    return failure(res, 500, "Request failed")
  })

  return app
}
