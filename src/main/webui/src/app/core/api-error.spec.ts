// The status of a business refusal, kept instead of thrown away.
//
// Deliberately a new file rather than additions to `api.service.spec.ts`: that
// spec is the success criterion of this refactoring and must keep passing
// exactly as it was written.

import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { ApiError, SessionExpireeError, toError } from './api.service';

function refus(status: number, message?: string): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: message ? { message } : null });
}

describe('toError', () => {
  it('keeps the status alongside the message', () => {
    const error = toError(refus(404, 'Stand introuvable'));

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect(error.message).toBe('Stand introuvable');
  });

  it('mirrors the backend hierarchy: 400 invalid, 404 notFound, 409 conflict', () => {
    expect((toError(refus(400)) as ApiError).kind).toBe('invalid');
    expect((toError(refus(404)) as ApiError).kind).toBe('notFound');
    expect((toError(refus(409)) as ApiError).kind).toBe('conflict');
  });

  it('treats anything else as technical rather than guessing a meaning', () => {
    expect((toError(refus(500)) as ApiError).kind).toBe('technical');
    expect((toError(refus(503)) as ApiError).kind).toBe('technical');
    expect((toError(refus(418)) as ApiError).kind).toBe('technical');
  });

  it('still turns a 401 into a SessionExpireeError, which is now one kind among the others', () => {
    const error = toError(refus(401));

    expect(error).toBeInstanceOf(SessionExpireeError);
    // The subclass keeps working for the callers that stay silent about it...
    expect(error).toBeInstanceOf(ApiError);
    // ...and joins the same switch as the rest.
    expect((error as ApiError).kind).toBe('session');
    expect((error as ApiError).status).toBe(401);
  });

  it('falls back to the status code when the server sent no message', () => {
    expect(toError(refus(500)).message).toContain('500');
  });

  it('leaves a non-HTTP error alone: it has no status to carry', () => {
    const original = new Error('boom');

    expect(toError(original)).toBe(original);
    expect(toError(original)).not.toBeInstanceOf(ApiError);
  });

  it('wraps a thrown non-Error value rather than losing it', () => {
    expect(toError('boom').message).toBe('boom');
  });
});
