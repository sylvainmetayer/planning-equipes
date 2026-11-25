import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { clearStoredEditionId, getStoredEditionId } from './edition-courante';
import { CODE_EDITION_INACTIVE, editionInactivePhrase } from './edition-inactive';
import { EditionStore } from './edition.store';
import { NotificationService } from './notification.service';

/** The codes of a request refused because it names no edition, or a deleted one (ADR 0072). */
const CODES_CHOIX_EDITION = new Set(['EDITION_REQUISE', 'EDITION_INCONNUE']);

/** Session key that keeps a refused choice from reloading the page in a loop. */
const RELOAD_KEY = 'planning-equipes.editionReload';

/**
 * Tags every backend call with the edition this browser is working in
 * (`X-Edition-Id`), so the server scopes its reference-data queries to it.
 *
 * The server never falls back on another edition (ADR 0072): a call that names
 * none, or one someone has since deleted, is refused with `EDITION_REQUISE` /
 * `EDITION_INCONNUE`. The stored choice is then forgotten and the page
 * reloaded once, so `editionChosenGuard` picks again before any screen
 * loads — at most once a minute, so a refusal that persists is shown rather
 * than looped on.
 *
 * `EDITION_INACTIVE` (409) is the third refusal: the buttons are greyed out
 * upstream, so one that still gets here (a stale tab, a second window) is
 * told to the user whatever screen sent it — several of them only wrote the
 * error into a panel at the bottom of the page. The editions are read again
 * too, so the buttons of a tab that missed the deactivation grey out now.
 */
export const editionInterceptor: HttpInterceptorFn = (request, next) => {
  const notifications = inject(NotificationService);
  // Looked up on the refusal only: EditionStore reaches HttpClient, which runs this interceptor.
  const injector = inject(Injector);
  const editionId = getStoredEditionId();
  // A caller that set the header itself is deliberately reading ANOTHER
  // edition (e.g. the import-impact counts for a scenario's target edition):
  // never overwrite that explicit choice with the browser's ambient one.
  const tagged =
    !editionId || !request.url.startsWith('/api/') || request.headers.has('X-Edition-Id')
      ? request
      : request.clone({ setHeaders: { 'X-Edition-Id': editionId } });
  return next(tagged).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 400 &&
        !request.headers.has('X-Edition-Id') &&
        CODES_CHOIX_EDITION.has((error.error as { code?: unknown } | null)?.code as string)
      ) {
        startOverEditionChoice();
      }
      if (
        error instanceof HttpErrorResponse &&
        error.status === 409 &&
        (error.error as { code?: unknown } | null)?.code === CODE_EDITION_INACTIVE
      ) {
        notifications.notify({
          title: $localize`:@@edition.inactive.titre:Action refusée`,
          message: editionInactivePhrase(),
          variant: 'error',
          timeout: 0,
          lien: { route: '/editions', libelle: $localize`:@@edition.inactive.ouvrir:Éditions` },
        });
        void injector
          .get(EditionStore)
          .reload()
          .catch(() => undefined);
      }
      return throwError(() => error);
    }),
  );
};

function startOverEditionChoice(): void {
  clearStoredEditionId();
  try {
    const last = Number(sessionStorage.getItem(RELOAD_KEY) ?? 0);
    if (Date.now() - last < 60_000) {
      return;
    }
    sessionStorage.setItem(RELOAD_KEY, String(Date.now()));
  } catch {
    return;
  }
  location.reload();
}
