import type { NextFunction, Request, RequestHandler, Response } from 'express';
import { ZodError, type ZodTypeAny, type z } from 'zod';

import { logger } from './logger';

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code = 'ERROR',
  ) {
    super(message);
  }
}

/** Lets async route handlers throw instead of calling `next(err)`. */
export function route(
  fn: (req: Request, res: Response) => Promise<unknown>,
): RequestHandler {
  return (req, res, next) => {
    fn(req, res).catch(next);
  };
}

export function parse<T extends ZodTypeAny>(schema: T, data: unknown): z.infer<T> {
  return schema.parse(data);
}

export function intParam(req: Request, name: string): number {
  const value = Number(req.params[name]);
  if (!Number.isInteger(value) || value <= 0) {
    throw new HttpError(400, `Invalid ${name}`, 'BAD_ID');
  }
  return value;
}

export function notFound(_req: Request, _res: Response, next: NextFunction): void {
  next(new HttpError(404, 'Not found', 'NOT_FOUND'));
}

export function errorHandler(
  err: unknown,
  _req: Request,
  res: Response,
  _next: NextFunction,
): void {
  if (err instanceof ZodError) {
    const issue = err.issues[0];
    res.status(400).json({
      ok: false,
      code: 'VALIDATION',
      error: issue ? `${issue.path.join('.') || 'body'}: ${issue.message}` : 'Invalid request',
    });
    return;
  }
  if (err instanceof HttpError) {
    res.status(err.status).json({ ok: false, code: err.code, error: err.message });
    return;
  }
  logger.error({ err }, 'unhandled_error');
  res.status(500).json({ ok: false, code: 'INTERNAL', error: 'Something went wrong' });
}
