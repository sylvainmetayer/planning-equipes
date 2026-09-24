import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

/**
 * Sends the visitor to /login whenever an admin API call answers 401 or 403.
 *
 * <p><b>401</b>: the session is missing or expired (issue #165).</p>
 *
 * <p><b>403</b>: the session exists but lacks the `admin` realm role — an
 * animateur signed in with Keycloak who followed a bookmark into the
 * administration. `/api/*` answers 403 for that and nothing else (business
 * refusals are 400, 404 or 409). Left on the admin shell, they would watch
 * every panel fail one after another with nothing saying why; the login page
 * reads `/api/auth/me` and explains instead.</p>
 *
 * <p>The espace animateur, the wall display and the auth endpoints are left
 * alone: a 401 there is an answer the screen itself renders, not a login
 * redirect. So is the MCP key reveal, whose 401 means "wrong password" or "sign-in too old" while the
 * session itself is fine — redirecting there threw an administrator out for a
 * typo.</p>
 *
 * <p>The drafts are left alone: recovering an entry after the session expired
 * is what they are for, and the fiche animateur's lives in this tab's
 * sessionStorage, which nobody else reads. Only the explicit logout purges
 * them (docs/rgpd.md §7).</p>
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        (error.status === 401 || error.status === 403) &&
        request.url.startsWith('/api/') &&
        !request.url.startsWith('/api/espace-animateur/') &&
        !request.url.startsWith('/api/mural/') &&
        !request.url.startsWith('/api/auth/') &&
        request.url !== '/api/mcp/cle'
      ) {
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
