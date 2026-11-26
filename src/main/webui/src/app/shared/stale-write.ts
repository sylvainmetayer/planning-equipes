// The answer to a 409 `MODIFICATION_CONCURRENTE` (issue #362) for the screens
// that write without going through `ReferenceCrudService`: the instance's
// webhooks and the consignes. Same three-way dialog, same wording, so the user
// meets one behaviour wherever a row was written by another session.

import { ApiError } from '../core/api.service';
import { intlLocale } from '../core/locale';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';

/** « (dernière écriture le 06/09/2026 à 17:34) », in the reader's own time zone. */
export function quand(modifieLe: string | null): string {
  if (!modifieLe) {
    return '';
  }
  const moment = new Date(modifieLe).toLocaleString(intlLocale());
  return ' ' + $localize`:@@crud.concurrent.moment:(dernière écriture le ${moment}:moment:)`;
}

/** What the user chose: send the write again without its precondition, reload, or neither. */
export type StaleChoice = 'overwrite' | 'reload' | 'dismiss';

/** True for the 409 that means « someone else wrote this row since you loaded it ». */
export function isStaleWrite(error: unknown): error is ApiError {
  return error instanceof ApiError && error.modificationConcurrente;
}

/**
 * Asks what to do about a stale write. Dismissing (Escape, the backdrop) is
 * neither of the two actions — « Recharger » throws away what the user typed,
 * so the gesture that means « I did not decide » must not perform it.
 */
export async function askStaleChoice(
  confirm: Pick<ConfirmService, 'askThreeWay'>,
  error: ApiError,
): Promise<StaleChoice> {
  const choix = await confirm.askThreeWay({
    title: $localize`:@@crud.error.conflit:Modifiée entre-temps`,
    message: error.message + quand(error.modifieLe),
    confirmLabel: $localize`:@@crud.concurrent.overwrite:Écraser quand même`,
    cancelLabel: $localize`:@@crud.concurrent.reload:Recharger`,
    danger: true,
  });
  if (choix === true) {
    return 'overwrite';
  }
  return choix === null ? 'dismiss' : 'reload';
}

/** What follows a « Recharger »: nothing was written, and the user is told how to carry their edit over. */
export function notifyStaleReloaded(notifications: NotificationService): void {
  notifications.notify({
    title: $localize`:@@crud.concurrent.reloaded:Fiche rechargée, vos modifications n'ont pas été enregistrées.`,
    message: $localize`:@@crud.concurrent.reloadedHint:Rouvrez-la pour les reporter sur la version actuelle.`,
    variant: 'warning',
    timeout: 8000,
  });
}

/** What a write of a whole section of settings needs to answer a 409. */
export interface StaleSectionDeps {
  readonly confirm: Pick<ConfirmService, 'askThreeWay'>;
  readonly notifications: Pick<NotificationService, 'notify'>;
}

/**
 * Writes a section of the edition's parameters with the stamp the screen
 * loaded, and answers a stale 409 with the usual dialog.
 *
 * - « Écraser quand même » runs `write(true)`: the caller re-reads the section
 *   and sends the typed fields over it, without a stamp.
 * - « Recharger » runs `reload`, says nothing was saved, and returns `null`:
 *   the caller stops there.
 * - Dismissing the dialog rethrows the 409, which the caller shows as any
 *   refusal, the typed values kept.
 *
 * Any other error is the caller's.
 */
export async function writeOrAskStale<T>(
  deps: StaleSectionDeps,
  write: (overwrite: boolean) => Promise<T>,
  reload: () => Promise<void>,
): Promise<{ saved: T } | null> {
  try {
    return { saved: await write(false) };
  } catch (error) {
    if (!isStaleWrite(error)) {
      throw error;
    }
    const choice = await askStaleChoice(deps.confirm, error);
    if (choice === 'overwrite') {
      return { saved: await write(true) };
    }
    if (choice === 'reload') {
      await reload();
      deps.notifications.notify({
        title: $localize`:@@parametres.concurrent.reloaded:Paramètres rechargés, vos modifications n'ont pas été enregistrées.`,
        message: $localize`:@@parametres.concurrent.reloadedHint:Saisissez-les à nouveau sur la version actuelle.`,
        variant: 'warning',
        timeout: 8000,
      });
      return null;
    }
    throw error;
  }
}
