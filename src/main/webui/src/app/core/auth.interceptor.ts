import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

/**
 * Sends the admin to /login whenever an API call answers 401 — the session is
 * missing or expired (issue #165). The espace animateur and the auth endpoints
 * are left alone: they are public by design, a 401 there would be a real error
 * to surface, not a login redirect.
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
        !request.url.startsWith('/api/auth/')
      ) {
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
