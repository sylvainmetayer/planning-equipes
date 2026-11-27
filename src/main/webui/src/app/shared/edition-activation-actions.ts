// The two gestures that hand the outside world over (ADR 0072) — activating an
// edition after a preview of what it closes in the outgoing one, leaving none
// active — with their confirmation. Shared by the Éditions page and the banner
// of the shell, so both say the same thing before the same click.

import { Injectable, inject } from '@angular/core';
import { EditionsApi } from '../core/api/editions-api';
import { EditionStore } from '../core/edition.store';
import { errorMessage } from '../core/error-message';
import { ActivationPreview, Edition } from '../core/models';
import { NotificationService } from '../core/notification.service';
import { ConfirmService } from './confirm-dialog';

@Injectable({ providedIn: 'root' })
export class EditionActivationActions {
  private readonly editionsApi = inject(EditionsApi);
  private readonly store = inject(EditionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  /**
   * Makes `edition` the active one after a confirmation that says what the
   * switch closes in the edition active today: links that stop working, swap
   * requests left open, solves queued. A running solve refuses the switch,
   * and the dialog says so before the click. Reloads the store; `true` when done.
   */
  async activate(edition: Edition): Promise<boolean> {
    let apercu: ActivationPreview;
    try {
      apercu = await this.editionsApi.activationPreview(edition.id);
    } catch (error) {
      this.notifications.notify({ title: errorMessage(error), variant: 'error' });
      return false;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@editions.activer.title:Activer l'édition ${edition.nom}:nom: ?`,
      message: activationMessage(apercu),
      confirmLabel: $localize`:@@editions.activer.confirm:Activer`,
    });
    if (!confirme) {
      return false;
    }
    return this.run(async () => {
      await this.editionsApi.activate(edition.id);
      this.notifications.notify({
        title: $localize`:@@editions.activer.done:${edition.nom}:nom: est désormais l'édition active.`,
        variant: 'success',
        timeout: 4000,
      });
    });
  }

  /** Leaves no edition active: between two events, nothing reaches outside. */
  async deactivate(edition: Edition): Promise<boolean> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@editions.desactiver.title:Désactiver l'édition ${edition.nom}:nom: ?`,
      message: $localize`:@@editions.desactiver.message:Plus aucune édition ne sera active : rien ne sera publié ni envoyé, et les liens d'espace, les calendriers et l'affichage mural cesseront de répondre.`,
      confirmLabel: $localize`:@@editions.desactiver.confirm:Désactiver`,
    });
    return confirme && this.run(() => this.editionsApi.deactivate(edition.id));
  }

  private async run(gesture: () => Promise<unknown>): Promise<boolean> {
    try {
      await gesture();
      await this.store.reload();
      return true;
    } catch (error) {
      this.notifications.notify({ title: errorMessage(error), variant: 'error' });
      return false;
    }
  }
}

/**
 * The confirmation of an activation, in plain words: what stops in the
 * outgoing edition, and whether a running solve will refuse the switch.
 */
export function activationMessage(apercu: ActivationPreview): string {
  const lignes: string[] = [
    $localize`:@@editions.activer.effet:Elle seule publiera, enverra des courriels et ouvrira l'espace animateur, les calendriers et l'affichage mural.`,
  ];
  if (apercu.sortante) {
    const nom = apercu.sortante.nom;
    const liens = apercu.liensAnimateurs;
    const muraux = apercu.liensMuraux;
    lignes.push(
      $localize`:@@editions.activer.sortante:${nom}:nom: cessera de l'être : ${liens}:liens: lien(s) d'espace et de calendrier et ${muraux}:muraux: affichage(s) mural(aux) ne répondront plus.`,
    );
    if (apercu.demandesOuvertes > 0) {
      const demandes = apercu.demandesOuvertes;
      lignes.push(
        $localize`:@@editions.activer.demandes:${demandes}:demandes: demande(s) d'échange y resteront sans réponse.`,
      );
    }
  }
  if (apercu.jobsEnFile > 0) {
    const jobs = apercu.jobsEnFile;
    lignes.push(
      $localize`:@@editions.activer.jobs:${jobs}:jobs: résolution(s) en file sur ces éditions.`,
    );
  }
  if (apercu.resolutionEnCours) {
    lignes.push(
      $localize`:@@editions.activer.refus:Une résolution est en cours sur l'une des deux éditions : l'activation sera refusée tant qu'elle n'est pas terminée.`,
    );
  }
  return lignes.join(' ');
}
