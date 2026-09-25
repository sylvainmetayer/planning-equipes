// Freezing and lifting a family of the referential (ADR 0052), with what
// each gesture owes the organiser: lifting asks first and recalls the phase
// the edition is in — a published plan moves under an edit —, both say how
// it went. Shared by the padlock of the forms, the home card and the
// Paramètres switches, so the three say the same thing.

import { Injectable, inject } from '@angular/core';
import { EditionsApi } from '../core/api/editions-api';
import { errorMessage } from '../core/error-message';
import { familyLabel } from '../core/gel-referentiel-labels';
import { GelReferentielStore } from '../core/gel-referentiel.store';
import { EtatEdition, FreezeFamily } from '../core/models';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';

/** The sentence recalling the phase the edition is in, empty before any plan. */
export function phaseReminder(etat: Pick<EtatEdition, 'publication' | 'resolution'>): string {
  if (!etat.publication.jamaisPublie) {
    return $localize`:@@gel.lever.phase.publie:Le planning est publié : toute modification fera bouger des plannings envoyés.`;
  }
  if (etat.resolution.resolue) {
    return $localize`:@@gel.lever.phase.resolu:Un planning est calculé : toute modification le rendra obsolète jusqu'au prochain calcul.`;
  }
  return '';
}

@Injectable({ providedIn: 'root' })
export class GelReferentielActions {
  private readonly store = inject(GelReferentielStore);
  private readonly confirm = inject(ConfirmService);
  private readonly editionsApi = inject(EditionsApi);
  private readonly notifications = inject(NotificationService);

  async freeze(family: FreezeFamily): Promise<boolean> {
    const label = familyLabel(family);
    try {
      await this.store.freeze(family);
      this.notifications.notify({
        title: $localize`:@@gel.fige.ok:« ${label}:famille: » figé`,
        message: $localize`:@@gel.fige.okDetail:Plus aucun chemin ne modifie ces fiches jusqu'à la levée du gel.`,
        variant: 'success',
      });
      return true;
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@gel.fige.echec:Gel non posé`,
        message: errorMessage(error),
        variant: 'error',
      });
      return false;
    }
  }

  /** Asks first, recalling the phase; `false` when the organiser changed their mind or the lift failed. */
  async lift(family: FreezeFamily): Promise<boolean> {
    const label = familyLabel(family);
    const confirmed = await this.confirm.ask({
      title: $localize`:@@gel.lever.titre:Lever le gel de « ${label}:famille: » ?`,
      message: $localize`:@@gel.lever.message:Ces fiches redeviennent modifiables par tous les chemins : formulaires, imports et assistant. Pour fermer une bande tard, une consigne suffit sans lever le gel.`,
      confirmLabel: $localize`:@@gel.lever.confirmer:Lever le gel`,
      danger: true,
      detail: this.editionsApi.etat().then(phaseReminder),
    });
    if (!confirmed) {
      return false;
    }
    try {
      await this.store.lift(family);
      this.notifications.notify({
        title: $localize`:@@gel.leve.ok:Gel de « ${label}:famille: » levé`,
        variant: 'success',
      });
      return true;
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@gel.leve.echec:Gel non levé`,
        message: errorMessage(error),
        variant: 'error',
      });
      return false;
    }
  }
}
