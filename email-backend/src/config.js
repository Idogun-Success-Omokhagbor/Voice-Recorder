import validator from "validator"

const REQUIRED_KEYS = [
  "BACKEND_BEARER_TOKEN",
  "SMTP_USER",
  "SMTP_PASS",
  "SMTP_FROM_EMAIL"
]

function requiredValue(env, key) {
  const value = env[key]?.trim()
  if (!value) {
    throw new Error(`Missing required environment variable: ${key}`)
  }
  return value
}

function parseInteger(value, name, fallback) {
  const parsed = Number.parseInt(value ?? `${fallback}`, 10)
  if (!Number.isInteger(parsed) || parsed <= 0 || parsed > 65_535) {
    throw new Error(`${name} must be an integer from 1 to 65535`)
  }
  return parsed
}

export function loadConfig(env = process.env) {
  for (const key of REQUIRED_KEYS) {
    requiredValue(env, key)
  }

  const backendToken = requiredValue(env, "BACKEND_BEARER_TOKEN")
  if (backendToken.length < 32 || /\s/.test(backendToken)) {
    throw new Error("BACKEND_BEARER_TOKEN must be at least 32 characters without whitespace")
  }

  const smtpPort = parseInteger(env.SMTP_PORT, "SMTP_PORT", 587)
  const smtpSecure = env.SMTP_SECURE?.toLowerCase() === "true" || smtpPort === 465

  const smtpFromEmail = requiredValue(env, "SMTP_FROM_EMAIL")
  if (!validator.isEmail(smtpFromEmail, { allow_utf8_local_part: false })) {
    throw new Error("SMTP_FROM_EMAIL must be a valid email address")
  }

  return Object.freeze({
    port: parseInteger(env.PORT, "PORT", 3000),
    backendToken,
    smtpHost: env.SMTP_HOST?.trim() || "smtp-relay.brevo.com",
    smtpPort,
    smtpSecure,
    smtpUser: requiredValue(env, "SMTP_USER"),
    smtpPass: requiredValue(env, "SMTP_PASS"),
    smtpFromEmail,
    smtpFromName: env.SMTP_FROM_NAME?.trim() || "Voice Recorder Plus",
    maxUploadBytes: 25 * 1024 * 1024
  })
}
