import { createApp } from "./app.js"
import { loadConfig } from "./config.js"
import { createEmailTransport } from "./mailer.js"

const config = loadConfig()
const mailer = createEmailTransport(config)

try {
  await mailer.verify()
} catch (error) {
  console.error("Brevo SMTP verification failed", { code: error?.code || "UNKNOWN" })
  process.exit(1)
}

const server = createApp({ config, mailer }).listen(config.port, "0.0.0.0", () => {
  console.log(`Voice Recorder Plus email backend listening on port ${config.port}`)
})

function shutdown() {
  server.close(() => {
    mailer.close()
    process.exit(0)
  })
}

process.on("SIGINT", shutdown)
process.on("SIGTERM", shutdown)
