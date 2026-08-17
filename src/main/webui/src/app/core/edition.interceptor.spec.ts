import { HttpRequest } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { editionInterceptor } from './edition.interceptor';
import { clearStoredEditionId } from './edition-courante';

/** Runs the interceptor and hands back the request that reached the next handler. */
function intercept(url: string): HttpRequest<unknown> {
  const next = vi.fn().mockReturnValue(null);
  editionInterceptor(new HttpRequest('GET', url), next);
  return next.mock.calls[0][0] as HttpRequest<unknown>;
}

describe('editionInterceptor', () => {
  afterEach(() => clearStoredEditionId());

  it('sends no header until an edition has been picked', () => {
    expect(intercept('/api/stands').headers.has('X-Edition-Id')).toBe(false);
  });

  it('tags backend calls with the stored edition', () => {
    localStorage.setItem('planning-equipes.editionId', 'ANNEE-2026');
    expect(intercept('/api/stands').headers.get('X-Edition-Id')).toBe('ANNEE-2026');
  });

  it('leaves non-API requests alone', () => {
    localStorage.setItem('planning-equipes.editionId', 'ANNEE-2026');
    expect(intercept('/i18n/messages.en.json').headers.has('X-Edition-Id')).toBe(false);
  });
});
