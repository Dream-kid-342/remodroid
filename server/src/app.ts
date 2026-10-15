import { createServer as createHttpServer, type Server } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import type { Logger } from "pino";
import type { Config } from "./config/index.js";
import { createRequestHandler } from "./http/routes.js";
import { IceServerService } from "./services/iceServers.js";
import { SignalingService } from "./services/signalingService.js";
import { SignalingGateway } from "./signaling/gateway.js";

export interface App {
  readonly server: Server;
  readonly service: SignalingService;
  readonly gateway: SignalingGateway;
  listen(): Promise<AddressInfo>;
  close(): Promise<void>;
}

export function createApp(config: Config, logger: Logger): App {
  const ice = new IceServerService(config.ice);
  const service = new SignalingService(config, logger, ice);
  const gateway = new SignalingGateway(config, logger, service);
  let shuttingDown = false;

  const handler = createRequestHandler({
    isShuttingDown: () => shuttingDown,
    stats: () => ({ ...service.stats(), connections: gateway.connectionCount }),
    turnConfigured: ice.turnConfigured,
  });

  const server: Server = config.tls
    ? createHttpsServer({ cert: readFileSync(config.tls.certFile), key: readFileSync(config.tls.keyFile) }, handler)
    : createHttpServer(handler);
  server.headersTimeout = 10_000;
  server.requestTimeout = 15_000;
  server.on("upgrade", (req, socket, head) => gateway.handleUpgrade(req, socket, head));

  const sweeper = setInterval(() => service.sweep(), config.sweepIntervalMs);
  sweeper.unref();

  return {
    server,
    service,
    gateway,
    listen: () =>
      new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(config.port, config.host, () => {
          server.off("error", reject);
          gateway.start();
          resolve(server.address() as AddressInfo);
        });
      }),
    close: async () => {
      if (shuttingDown) return;
      shuttingDown = true;
      clearInterval(sweeper);
      service.shutdown();
      await gateway.close();
      await new Promise<void>((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections();
      });
    },
  };
}
