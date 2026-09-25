import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { ApiError, CODE_MODIFICATION_CONCURRENTE, attachmentName, toError } from './api.service';

describe('toError', () => {
  it('uses the server-provided message when the error body carries one', () => {
    const response = new HttpErrorResponse({
      status: 400,
      error: { message: 'Créneau invalide' },
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

describe('toError — le code du corps', () => {
  it('garde le code MODIFICATION_CONCURRENTE que porte le 409 d’une écriture périmée', () => {
    const response = new HttpErrorResponse({
      status: 409,
      error: {
        message: 'Modifiée par une autre session',
        code: 'MODIFICATION_CONCURRENTE',
        modifieLe: '2026-09-06T10:00:00Z',
      },
    });
    const error = toError(response) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe(CODE_MODIFICATION_CONCURRENTE);
    expect(error.modificationConcurrente).toBe(true);
  });

  it('ne prend pas un 409 sans code pour une modification concurrente', () => {
    const response = new HttpErrorResponse({ status: 409, error: { message: 'Solveur occupé' } });
    const error = toError(response) as ApiError;
    expect(error.code).toBeNull();
    expect(error.modificationConcurrente).toBe(false);
  });
});

describe('attachmentName', () => {
  it('reads the name the server gave the file, and nothing when it gave none', () => {
    expect(attachmentName('attachment; filename="archive-annee-2026-2026-09-25.zip"')).toBe(
      'archive-annee-2026-2026-09-25.zip',
    );
    expect(attachmentName('attachment')).toBeNull();
    expect(attachmentName(null)).toBeNull();
  });
});
