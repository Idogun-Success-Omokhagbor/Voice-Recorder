import nodemailer from "nodemailer"

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
