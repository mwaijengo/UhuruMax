import { query } from '../db/pool';
import { HttpError } from '../lib/http';
import { sendToTokens, type PushResult } from './push';

export const NOTIFICATION_TARGETS = ['all', 'premium', 'free', 'expired', 'expiring', 'user'] as const;
export type NotificationTarget = (typeof NOTIFICATION_TARGETS)[number];

const TARGET_SQL: Record<Exclude<NotificationTarget, 'user'>, string> = {
  all: 'true',
  premium: 'expires_at > now()',
  free: '(expires_at IS NULL OR expires_at <= now())',
  expired: 'expires_at <= now()',
  expiring: "expires_at > now() AND expires_at <= now() + interval '3 days'",
};

async function tokensFor(target: NotificationTarget, userId?: number): Promise<string[]> {
  if (target === 'user') {
    if (!userId) throw new HttpError(400, 'userId is required', 'VALIDATION');
    const rows = await query<{ fcm_token: string }>(
      `SELECT fcm_token FROM users WHERE id = $1 AND fcm_token <> ''`,
      [userId],
    );
    return rows.map((r) => r.fcm_token);
  }
  const rows = await query<{ fcm_token: string }>(
    `SELECT fcm_token FROM users WHERE fcm_token <> '' AND NOT blocked AND ${TARGET_SQL[target]}`,
  );
  return rows.map((r) => r.fcm_token);
}

async function forgetTokens(tokens: string[]): Promise<void> {
  if (tokens.length === 0) return;
  await query(`UPDATE users SET fcm_token = '' WHERE fcm_token = ANY($1)`, [tokens]);
}

export async function sendNotification(input: {
  title: string;
  body: string;
  target: NotificationTarget;
  userId?: number;
}): Promise<PushResult> {
  const tokens = await tokensFor(input.target, input.userId);
  const result = await sendToTokens(tokens, {
    title: input.title,
    body: input.body,
    data: { type: 'notification' },
  });
  await forgetTokens(result.invalidTokens);
  await query(
    `INSERT INTO notifications (title, body, target, recipients, delivered, failed)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [
      input.title,
      input.body,
      input.target === 'user' ? `user:${input.userId}` : input.target,
      result.recipients,
      result.delivered,
      result.failed,
    ],
  );
  return result;
}

export const REMINDERS = {
  expired: {
    title: 'Kifurushi chako kimeisha',
    body: 'Lipia sasa uendelee kutazama chaneli zote za UhuruMax.',
  },
  expiring: {
    title: 'Kifurushi chako kinakaribia kuisha',
    body: 'Ongezea siku za matumizi sasa kabla hujazimiwa huduma.',
  },
};

/** Silent push telling apps to reload; apps also notice on their next sync. */
export async function pushRefreshCommand(): Promise<void> {
  const rows = await query<{ fcm_token: string }>(`SELECT fcm_token FROM users WHERE fcm_token <> ''`);
  const result = await sendToTokens(
    rows.map((r) => r.fcm_token),
    { data: { type: 'refresh' } },
  );
  await forgetTokens(result.invalidTokens);
}
