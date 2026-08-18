import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { intlLocale } from '../../core/locale';
import { PlanSnapshot } from '../../core/models';
import { PlanSnapshotStore, ReferencesManquantesError } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';

/**
 * Saved plans (issue #138). Until they existed, a single plan was persisted per
 * edition and every solve overwrote it: solving on another groupe de créneaux
 * destroyed the previous result for good.
 *
 * Restoring is disabled while a solve runs — it would be overwritten seconds
 * later — and refused by the server when the snapshot names stands, créneaux or
 * animateurs the referential no longer holds.
 */
@Component({
  selector: 'app-snapshots-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './snapshots-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SnapshotsPage {
  protected readonly columns = ['libelle', 'groupe', 'score', 'affectations', 'creeLe', 'actions'];
  protected readonly store = inject(PlanSnapshotStore);
  protected readonly jobs = inject(SolverJobService);

  protected readonly error = signal('');
  protected readonly message = signal('');
  protected readonly enCours = signal<number | 'capture' | null>(null);
  protected readonly locked = computed(() => this.jobs.solverBusy());

  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.error.set('');
    try {
      await this.store.reload();
    } catch (error) {
      this.error.set(this.messageErreur(error));
    }
  }

  protected async capturer(): Promise<void> {
    const libelle = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@snapshots.capture.title:Enregistrer le plan actuel`,
      label: $localize`:@@snapshots.capture.label:Nom de l'instantané`,
      confirmLabel: $localize`:@@snapshots.capture.confirm:Enregistrer`
    });
    if (!libelle) {
      return;
    }
    this.enCours.set('capture');
    this.error.set('');
    this.message.set('');
    try {
      await this.store.capturer(libelle);
      this.message.set($localize`:@@snapshots.captured:Instantané « ${libelle}:libelle: » enregistré.`);
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.enCours.set(null);
    }
  }

  protected async restaurer(snapshot: PlanSnapshot): Promise<void> {
    if (this.locked()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@snapshots.restore.title:Restaurer cet instantané ?`,
      message: $localize`:@@snapshots.restore.message:Le plan actuellement enregistré est remplacé par « ${snapshot.libelle}:libelle: ». Enregistrez-le d'abord si vous voulez le garder.`,
      confirmLabel: $localize`:@@snapshots.restore.confirm:Restaurer`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    this.enCours.set(snapshot.id);
    this.error.set('');
    this.message.set('');
    try {
      const resultat = await this.store.restaurer(snapshot.id);
      await this.resolution.reload();
      this.message.set(
        $localize`:@@snapshots.restored:${resultat.affectations}:count: affectation(s) restaurée(s) depuis « ${snapshot.libelle}:libelle: ».`
      );
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.enCours.set(null);
    }
  }

  protected async supprimer(snapshot: PlanSnapshot): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@snapshots.delete.title:Supprimer cet instantané ?`,
      message: $localize`:@@snapshots.delete.message:« ${snapshot.libelle}:libelle: » sera définitivement perdu.`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    this.enCours.set(snapshot.id);
    this.error.set('');
    try {
      await this.store.supprimer(snapshot.id);
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.enCours.set(null);
    }
  }

  protected dateLabel(snapshot: PlanSnapshot): string {
    return snapshot.creeLe ? new Date(snapshot.creeLe).toLocaleString(intlLocale()) : '';
  }

  protected groupeLabel(snapshot: PlanSnapshot): string {
    return snapshot.groupeNom ?? snapshot.groupeCreneauId ?? $localize`:@@snapshots.groupeInconnu:groupe inconnu`;
  }

  /** A refused restore names what is missing: that list is the actionable part. */
  private messageErreur(error: unknown): string {
    if (error instanceof ReferencesManquantesError) {
      return $localize`:@@snapshots.error.references:${error.message}:message: Références manquantes : ${error.references.join(', ')}:references:`;
    }
    return $localize`:@@common.errorPrefix:Erreur : ${error instanceof Error ? error.message : String(error)}:message:`;
  }
}
