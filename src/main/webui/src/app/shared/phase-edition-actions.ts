// The gestures that move the phase of the edition (ADR 0115) — declaring a
// phase, unblocking the structure of a published edition, freezing it again —
// with what each owes the organiser: a confirmation saying what the new phase
// freezes or opens, and how it went. Shared by the home screen, the Éditions
// page and the padlocks of the forms, so they all say the same thing.

import { Injectable, inject, signal } from '@angular/core';
import { EditionsApi } from '../core/api/editions-api';
import { EditionStore } from '../core/edition.store';
import { errorMessage } from '../core/error-message';
import { GelReferentielStore } from '../core/gel-referentiel.store';
import { EditionPhase, EtatEdition } from '../core/models';
import { NotificationService } from '../core/notification.service';
import { phaseEffect, phaseLabel } from '../core/phase-edition';
import { ConfirmService } from './confirm-dialog';

/** The sentence recalling what a reopened referential would move, empty before any plan. */
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
export class PhaseEditionActions {
  private readonly editionsApi = inject(EditionsApi);
  private readonly editionStore = inject(EditionStore);
  private readonly gel = inject(GelReferentielStore);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  private readonly _busy = signal(false);
  readonly busy = this._busy.asReadonly();

  /** Reads the edition and the freeze again: a phase moves every family at once. */
  async refresh(): Promise<void> {
    await Promise.all([this.editionStore.reload(), this.gel.reload()]);
  }

  /** Declares `phase` after a confirmation; `false` when cancelled or refused. */
  async changePhase(phase: EditionPhase): Promise<boolean> {
    const label = phaseLabel(phase);
    // Going back to Préparation opens what a computed or published plan was computed from.
    const opens = phase === 'PREPARATION';
    return this.run({
      title: $localize`:@@phase.confirmer.titre:Passer l'édition en phase « ${label}:phase: » ?`,
      message: phaseEffect(phase),
      confirmLabel: $localize`:@@phase.confirmer.bouton:Passer en ${label}:phase:`,
      danger: opens,
      withReminder: opens,
      gesture: () => this.editionsApi.setPhase(phase),
      done: $localize`:@@phase.ok:Édition en phase « ${label}:phase: »`,
      failed: $localize`:@@phase.echec:Phase non changée`,
    });
  }

  async unblockStructure(): Promise<boolean> {
    return this.run({
      title: $localize`:@@phase.debloquer.titre:Débloquer la structure ?`,
      message: $localize`:@@phase.debloquer.message:Stands, créneaux, typologies et emplacements redeviennent modifiables par tous les chemins. L'édition reste publiée ; la publication suivante refige la structure.`,
      confirmLabel: $localize`:@@phase.debloquer.bouton:Débloquer la structure`,
      danger: true,
      withReminder: true,
      gesture: () => this.editionsApi.unblockStructure(),
      done: $localize`:@@phase.debloquer.ok:Structure débloquée`,
      failed: $localize`:@@phase.debloquer.echec:Structure non débloquée`,
    });
  }

  async refreezeStructure(): Promise<boolean> {
    return this.run({
      title: $localize`:@@phase.refiger.titre:Refiger la structure ?`,
      message: $localize`:@@phase.refiger.message:Les stands, créneaux, typologies et emplacements ne se modifient plus, par aucun chemin, jusqu'à un nouveau déblocage ou un changement de phase.`,
      confirmLabel: $localize`:@@phase.refiger.bouton:Refiger`,
      danger: false,
      withReminder: false,
      gesture: () => this.editionsApi.refreezeStructure(),
      done: $localize`:@@phase.refiger.ok:Structure refigée`,
      failed: $localize`:@@phase.refiger.echec:Structure non refigée`,
    });
  }

  private async run(step: {
    title: string;
    message: string;
    confirmLabel: string;
    danger: boolean;
    withReminder: boolean;
    gesture: () => Promise<unknown>;
    done: string;
    failed: string;
  }): Promise<boolean> {
    const confirmed = await this.confirm.ask({
      title: step.title,
      message: step.message,
      confirmLabel: step.confirmLabel,
      danger: step.danger,
      detail: step.withReminder ? this.editionsApi.etat().then(phaseReminder) : undefined,
    });
    if (!confirmed) {
      return false;
    }
    this._busy.set(true);
    try {
      await step.gesture();
    } catch (error) {
      this.notifications.notify({
        title: step.failed,
        message: errorMessage(error),
        variant: 'error',
      });
      // A refusal often means another session moved the phase: show where it stands.
      void this.refresh().catch(() => undefined);
      return false;
    } finally {
      this._busy.set(false);
    }
    // The gesture went through: a failed re-read must not say it did not.
    await this.refresh().catch(() => undefined);
    this.notifications.notify({ title: step.done, variant: 'success' });
    return true;
  }
}
