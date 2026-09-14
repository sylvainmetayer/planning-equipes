// The question asked before a stale snapshot is put back (issue #170).
//
// Two screens restore a snapshot — the Instantanés page and the post-solve
// recap's "Revenir au plan d'avant" — and the server refuses both the same
// way. Wording the refusal once is what keeps them from saying two different
// things about the same 409, and what stops the second one from silently
// losing the override.

import { intlLocale } from '../core/locale';
import { InstantanePerimeError } from '../core/plan-snapshot.store';
import { ConfirmService } from './confirm-dialog';

/**
 * Asks whether to restore anyway, naming the change that made the snapshot
 * stale. `true` means the caller should retry with `forcer`.
 */
export function confirmStaleRestore(
  confirm: ConfirmService,
  erreur: InstantanePerimeError,
): Promise<boolean> {
  const moment = erreur.referenceModifieLe
    ? new Date(erreur.referenceModifieLe).toLocaleString(intlLocale())
    : '';
  return confirm.ask({
    title: $localize`:@@snapshots.stale.title:Cet instantané est périmé`,
    message: moment
      ? $localize`:@@snapshots.stale.message:Le référentiel a été modifié le ${moment}:moment:, après cette capture. Remettre ce plan en place annulerait la prise en compte de ces changements. Restaurer quand même ?`
      : $localize`:@@snapshots.stale.messageSansDate:Le référentiel a été modifié après cette capture. Remettre ce plan en place annulerait la prise en compte de ces changements. Restaurer quand même ?`,
    confirmLabel: $localize`:@@snapshots.stale.confirm:Restaurer quand même`,
    danger: true,
  });
}
