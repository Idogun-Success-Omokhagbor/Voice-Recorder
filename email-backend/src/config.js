import validator from "validator"

const SUPPORTED_TRANSPORTS = new Set(["api", "google_apps_script", "smtp"])

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
  const backendToken = requiredValue(env, "BACKEND_BEARER_TOKEN")
  if (backendToken.length < 32 || /\s/.test(backendToken)) {
    throw new Error("BACKEND_BEARER_TOKEN must be at least 32 characters without whitespace")
  }

  const smtpPort = parseInteger(env.SMTP_PORT, "SMTP_PORT", 587)
  const smtpSecure = env.SMTP_SECURE?.toLowerCase() === "true" || smtpPort === 465
  const emailTransport = env.EMAIL_TRANSPORT?.trim().toLowerCase() || "smtp"
  if (!SUPPORTED_TRANSPORTS.has(emailTransport)) {
    throw new Error("EMAIL_TRANSPORT must be api, google_apps_script, or smtp")
  }

  const googleAppsScriptUrl = emailTransport === "google_apps_script"
    ? requiredValue(env, "GOOGLE_APPS_SCRIPT_URL")
    : null
  if (googleAppsScriptUrl) {
    const parsedUrl = new URL(googleAppsScriptUrl)
    if (parsedUrl.protocol !== "https:" || parsedUrl.hostname !== "script.google.com") {
      throw new Error("GOOGLE_APPS_SCRIPT_URL must be an HTTPS script.google.com URL")
    }
  }

  const googleAppsScriptSecret = emailTransport === "google_apps_script"
    ? requiredValue(env, "GOOGLE_APPS_SCRIPT_SECRET")
    : null
  if (googleAppsScriptSecret && (googleAppsScriptSecret.length < 32 || /\s/.test(googleAppsScriptSecret))) {
    throw new Error("GOOGLE_APPS_SCRIPT_SECRET must be at least 32 characters without whitespace")
  }

  const smtpFromEmail = requiredValue(env, "SMTP_FROM_EMAIL")
  if (!validator.isEmail(smtpFromEmail, { allow_utf8_local_part: false })) {
    throw new Error("SMTP_FROM_EMAIL must be a valid email address")
  }

  return Object.freeze({
    port: parseInteger(env.PORT, "PORT", 3000),
    backendToken,
    emailTransport,
    brevoApiKey: emailTransport === "api" ? requiredValue(env, "BREVO_API_KEY") : null,
    googleAppsScriptUrl,
    googleAppsScriptSecret,
    smtpHost: env.SMTP_HOST?.trim() || "smtp-relay.brevo.com",
    smtpPort,
    smtpSecure,
    smtpUser: emailTransport === "smtp" ? requiredValue(env, "SMTP_USER") : null,
    smtpPass: emailTransport === "smtp" ? requiredValue(env, "SMTP_PASS") : null,
    smtpFromEmail,
    smtpFromName: env.SMTP_FROM_NAME?.trim() || "Voice Recorder Plus",
    maxUploadBytes: 14 * 1024 * 1024
  })
}
