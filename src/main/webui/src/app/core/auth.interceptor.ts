import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

/**
 * Sends the visitor to /login whenever an admin API call answers 401: the
 * session is missing or expired (issue #165). A 403 is not a login problem —
 * an organiser is refused the instance's settings and keeps working — so it
 * stays an error the screen shows; the person signed in with no right at all is
 * taken to /login by `SessionStore` once the session is read. The espace
 * animateur, the wall display and the auth endpoints are left alone: they are
 * public by design, a 401 there would be a real error to surface, not a login
 * redirect.
 *
 * The drafts are left alone: recovering an entry after the session expired
 * is what they are for, and the fiche animateur's lives in this tab's
 * sessionStorage, which nobody else reads. Only the explicit logout purges
 * them (site/src/docs/installer/rgpd.md §7).
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        request.url.startsWith('/api/') &&
        !request.url.startsWith('/api/espace-animateur/') &&
        !request.url.startsWith('/api/mural/') &&
        !request.url.startsWith('/api/auth/')
      ) {
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
