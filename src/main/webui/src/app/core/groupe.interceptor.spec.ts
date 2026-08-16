import { HttpRequest } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { groupeInterceptor } from './groupe.interceptor';
import { clearStoredGroupeId } from './groupe-courant';

/** Runs the interceptor and hands back the request that reached the next handler. */
function intercept(url: string): HttpRequest<unknown> {
  const next = vi.fn().mockReturnValue(null);
  groupeInterceptor(new HttpRequest('GET', url), next);
  return next.mock.calls[0][0] as HttpRequest<unknown>;
}

describe('groupeInterceptor', () => {
  afterEach(() => clearStoredGroupeId());

  it('sends no header until an edition has been picked', () => {
    expect(intercept('/api/stands').headers.has('X-Groupe-Id')).toBe(false);
  });

  it('tags backend calls with the stored edition', () => {
    localStorage.setItem('planning-equipes.groupeId', 'ANNEE-2026');
    expect(intercept('/api/stands').headers.get('X-Groupe-Id')).toBe('ANNEE-2026');
  });

  it('leaves non-API requests alone', () => {
    localStorage.setItem('planning-equipes.groupeId', 'ANNEE-2026');
    expect(intercept('/i18n/messages.en.json').headers.has('X-Groupe-Id')).toBe(false);
  });
});
