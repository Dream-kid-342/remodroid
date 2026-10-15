import { createApp } from "./app.js";
import { ConfigError, loadConfig } from "./config/index.js";
import { createLogger } from "./logger.js";

async function main(): Promise<void> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      console.error(err.message);
      process.exit(1);
    }
    throw err;
  }

  const logger = createLogger(config);
  if (config.env === "production" && !config.tls && !config.trustProxy) {
    logger.warn(
      "Running in production without TLS or TRUST_PROXY. Clients must reach this server through an HTTPS/WSS terminating proxy or tunnel.",
    );
  }
  if (config.ice.turnUrls.length === 0) {
    logger.warn("No TURN server configured; sessions between restrictive networks (e.g. some mobile carriers) may fail.");
  }

  const app = createApp(config, logger);
  const address = await app.listen();
  logger.info(
    {
      address: address.address,
      port: address.port,
      tls: Boolean(config.tls),
      publicUrl: config.publicSignalingUrl ?? null,
      env: config.env,
    },
    "signaling server listening",
  );

  let stopping = false;
  const shutdown = (signal: string) => {
    if (stopping) return;
    stopping = true;
    logger.info({ signal }, "shutting down");
    const force = setTimeout(() => {
      logger.error("graceful shutdown timed out; exiting");
      process.exit(1);
    }, 10_000);
    force.unref();
    app
      .close()
      .then(() => {
        logger.info("shutdown complete");
        process.exit(0);
      })
      .catch((err: unknown) => {
        logger.error({ err }, "error during shutdown");
        process.exit(1);
      });
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("unhandledRejection", (err) => logger.error({ err }, "unhandled promise rejection"));
}

void main();
