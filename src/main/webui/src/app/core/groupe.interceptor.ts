import { HttpInterceptorFn } from '@angular/common/http';
import { getStoredGroupeId } from './groupe-courant';

/**
 * Tags every backend call with the edition this browser is working in
 * (`X-Groupe-Id`), so the server scopes its reference-data queries to it.
 *
 * Absent when nothing was ever picked: the server then falls back to its
 * default group, which is also what it does when the header names a group that
 * no longer exists. The header is never a hard requirement — see
 * `GroupeContext` on the backend.
 */
export const groupeInterceptor: HttpInterceptorFn = (request, next) => {
  const groupeId = getStoredGroupeId();
  if (!groupeId || !request.url.startsWith('/api/')) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { 'X-Groupe-Id': groupeId } }));
};
