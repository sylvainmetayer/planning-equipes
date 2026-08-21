import { Injectable, inject } from '@angular/core';
import { NotificationService } from '../core/notification.service';
import { PlanSnapshotStore } from '../core/plan-snapshot.store';
import { ConfirmService } from './confirm-dialog';
import { errorMessage } from '../core/error-message';

/**
 * Offers to save the current plan before an action that destroys it.
 *
 * Destructive actions (emptying the database on the Debug page, replaying a
 * SQL dump on the Data page) rewrite the dataset, and the plan is the
 * expensive part: it costs a solve to rebuild. Snapshots exist now, so these
 * pages propose one rather than letting the user discover afterwards that the
 * only copy is gone. Declining is fine — this is a safety net, not a gate —
 * and a snapshot that fails to save never blocks the action.
 */
@Injectable({ providedIn: 'root' })
export class InstantaneAvantAction {
  private readonly confirm = inject(ConfirmService);
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly notifications = inject(NotificationService);

  async proposer(intitule: string): Promise<void> {
    const veut = await this.confirm.ask({
      title: $localize`:@@dataSetup.snapshotBefore.title:Enregistrer le plan actuel d'abord ?`,
      message: $localize`:@@dataSetup.snapshotBefore.message:${intitule}:action: va remplacer les données, et avec elles le planning résolu. Un instantané permet de le retrouver ensuite.`,
      confirmLabel: $localize`:@@dataSetup.snapshotBefore.confirm:Enregistrer un instantané`
    });
    if (!veut) {
      return;
    }
    try {
      await this.snapshots.capturer(
        $localize`:@@dataSetup.snapshotBefore.libelle:Avant ${intitule}:action:`
      );
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@dataSetup.snapshotBefore.failed:Instantané non enregistré`,
        message: errorMessage(error),
        variant: 'error'
      });
    }
  }
}
