import { readFileSync } from 'node:fs';

import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';

import { env } from '../config/env';
import { HttpError } from '../lib/http';
import { logger } from '../lib/logger';

type ServiceAccount = { project_id?: string; client_email?: string; private_key?: string };

function loadServiceAccount(): ServiceAccount | null {
  if (env.fcmServiceAccountJson) return JSON.parse(env.fcmServiceAccountJson) as ServiceAccount;
  if (env.fcmServiceAccountPath) {
    return JSON.parse(readFileSync(env.fcmServiceAccountPath, 'utf8')) as ServiceAccount;
  }
  return null;
}

export function pushConfigured(): boolean {
  return Boolean(env.fcmServiceAccountJson || env.fcmServiceAccountPath);
}

function ensureApp(): void {
  if (getApps().length > 0) return;
  const sa = loadServiceAccount();
  if (!sa?.project_id || !sa.client_email || !sa.private_key) {
    throw new HttpError(
      503,
      'Push notifications are not configured. Set FCM_SERVICE_ACCOUNT_JSON on the server.',
      'PUSH_DISABLED',
    );
  }
  initializeApp({
    credential: cert({
      projectId: sa.project_id,
      clientEmail: sa.client_email,
      privateKey: sa.private_key.replace(/\\n/g, '\n'),
    }),
  });
}

const ANDROID = {
  priority: 'high' as const,
  ttl: 7 * 24 * 60 * 60 * 1000,
  notification: { channelId: 'uhurumax_alerts', defaultSound: true },
};

export type PushResult = { recipients: number; delivered: number; failed: number; invalidTokens: string[] };

const BATCH_SIZE = 500; // FCM multicast limit
const PARALLEL_BATCHES = 10;

/**
 * Sends to device tokens in batches of 500, several batches at a time so a
 * large audience is reached together rather than one batch after another.
 * A message without [title] is data-only, e.g. a silent "refresh" command.
 */
export async function sendToTokens(
  tokens: string[],
  message: { title?: string; body?: string; data?: Record<string, string> },
): Promise<PushResult> {
  const unique = [...new Set(tokens.filter(Boolean))];
  const result: PushResult = { recipients: unique.length, delivered: 0, failed: 0, invalidTokens: [] };
  if (unique.length === 0) return result;
  ensureApp();

  const batches: string[][] = [];
  for (let i = 0; i < unique.length; i += BATCH_SIZE) batches.push(unique.slice(i, i + BATCH_SIZE));

  const sendBatch = async (batch: string[]) => {
    try {
      const res = await getMessaging().sendEachForMulticast({
        tokens: batch,
        notification: message.title ? { title: message.title, body: message.body ?? '' } : undefined,
        data: { source: 'uhuru_admin', ...(message.data ?? {}) },
        android: message.title ? ANDROID : { priority: 'high' },
      });
      result.delivered += res.successCount;
      result.failed += res.failureCount;
      res.responses.forEach((r, idx) => {
        const code = r.error?.code ?? '';
        if (code.includes('registration-token-not-registered') || code.includes('invalid-argument')) {
          result.invalidTokens.push(batch[idx]!);
        }
      });
    } catch (err) {
      result.failed += batch.length;
      firstError ??= err;
      logger.error({ err, size: batch.length }, 'push_batch_failed');
    }
  };

  let firstError: unknown;
  for (let i = 0; i < batches.length; i += PARALLEL_BATCHES) {
    await Promise.all(batches.slice(i, i + PARALLEL_BATCHES).map(sendBatch));
  }
  if (result.delivered === 0 && firstError) {
    const reason = firstError instanceof Error ? firstError.message : String(firstError);
    throw new HttpError(502, `Firebase rejected the notification: ${reason}`, 'PUSH_FAILED');
  }
  logger.info({ ...result, invalidTokens: result.invalidTokens.length }, 'push_sent');
  return result;
}
