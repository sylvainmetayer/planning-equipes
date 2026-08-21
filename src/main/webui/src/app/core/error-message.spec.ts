import { describe, expect, it } from 'vitest';
import { errorMessage, errorPrefix } from './error-message';

describe('errorMessage', () => {
  it('returns the message of an Error, which is the normalised case', () => {
    expect(errorMessage(new Error('Stand introuvable'))).toBe('Stand introuvable');
  });

  it('keeps the message of an Error subclass rather than its class name', () => {
    class SessionExpireeError extends Error {}
    expect(errorMessage(new SessionExpireeError('Session expirée'))).toBe('Session expirée');
  });

  it('stringifies a thrown value that is not an Error rather than dropping it', () => {
    expect(errorMessage('boom')).toBe('boom');
    expect(errorMessage(404)).toBe('404');
  });

  it('never returns an empty string for a null or undefined rejection', () => {
    expect(errorMessage(null)).toBe('null');
    expect(errorMessage(undefined)).toBe('undefined');
  });
});

describe('errorPrefix', () => {
  it('puts the message behind the shared prefix', () => {
    expect(errorPrefix(new Error('Import refusé'))).toBe('Erreur : Import refusé');
  });

  it('applies the same narrowing as errorMessage', () => {
    expect(errorPrefix('boom')).toBe('Erreur : boom');
  });
});
