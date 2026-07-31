import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { toError } from './api.service';

describe('toError', () => {
  it('uses the server-provided message when the error body carries one', () => {
    const response = new HttpErrorResponse({
      status: 400,
      error: { message: 'Créneau invalide' }
    });
    expect(toError(response).message).toBe('Créneau invalide');
  });

  it('falls back to the status code when the body has no message', () => {
    const response = new HttpErrorResponse({ status: 500, error: 'boom' });
    expect(toError(response).message).toBe('Échec de la requête (code 500)');
  });

  it('falls back to the status code when the body is null', () => {
    const response = new HttpErrorResponse({ status: 409, error: null });
    expect(toError(response).message).toBe('Échec de la requête (code 409)');
  });

  it('returns a non-HTTP Error unchanged', () => {
    const original = new Error('offline');
    expect(toError(original)).toBe(original);
  });

  it('wraps a non-Error value into an Error', () => {
    const result = toError('unexpected');
    expect(result).toBeInstanceOf(Error);
    expect(result.message).toBe('unexpected');
  });
});
