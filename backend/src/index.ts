import { createApp } from './app';
import { env } from './config/env';
import { closePool } from './db/pool';
import { migrate } from './db/migrate';
import { logger } from './lib/logger';

export async function start(): Promise<void> {
  if (!env.jwtSecret || !env.adminPassword) {
    logger.warn('JWT_SECRET or ADMIN_PASSWORD missing: admin login is disabled until both are set');
  }
  await migrate();
  const server = createApp().listen(env.port, '0.0.0.0', () => {
    logger.info({ port: env.port }, 'uhurumax_api_listening');
  });

  const shutdown = () => {
    server.close(() => {
      void closePool().finally(() => process.exit(0));
    });
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

if (require.main === module) {
  start().catch((err) => {
    logger.error({ err }, 'startup_failed');
    process.exit(1);
  });
}
