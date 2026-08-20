import { HttpInterceptorFn } from '@angular/common/http';
import { getStoredEditionId } from './edition-courante';

/**
 * Tags every backend call with the edition this browser is working in
 * (`X-Edition-Id`), so the server scopes its reference-data queries to it.
 *
 * Absent when nothing was ever picked: the server then falls back to its
 * default edition, which is also what it does when the header names an
 * edition that no longer exists. The header is never a hard requirement —
 * see `EditionContext` on the backend.
 */
export const editionInterceptor: HttpInterceptorFn = (request, next) => {
  const editionId = getStoredEditionId();
  // A caller that set the header itself is deliberately reading ANOTHER
  // edition (e.g. the import-impact counts for a scenario's target edition):
  // never overwrite that explicit choice with the browser's ambient one.
  if (!editionId || !request.url.startsWith('/api/') || request.headers.has('X-Edition-Id')) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { 'X-Edition-Id': editionId } }));
};
