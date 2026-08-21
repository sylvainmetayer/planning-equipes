import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { intlLocale } from '../../core/locale';
import { PersistenceStatus, PlanSnapshot, RestaurationSnapshot } from '../../core/models';
import { PlanSnapshotStore, ReferencesManquantesError } from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';
import { PromptDialog } from '../../shared/prompt-dialog';
import { errorPrefix } from '../../core/error-message';

/**
 * Saved plans (issue #138). Until they existed, a single plan was persisted per
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
    MatTooltipModule,
    StatusMessage
  ],
  templateUrl: './snapshots-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SnapshotsPage {
  protected readonly columns = ['libelle', 'score', 'affectations', 'creeLe', 'actions'];
  protected readonly columnsAuto = [
    'libelleAuto',
    'scoreAuto',
    'affectationsAuto',
    'creeLeAuto',
    'actionsAuto'
  ];

  /**
   * Hand-made snapshots first, automatic ones after. One capture is taken
   * before every solve, so within a day of work the automatic ones outnumber
   * the deliberate ones and bury them.
   */
  protected readonly instantanesManuels = computed(() =>
    this.store.snapshots().filter((snapshot) => !snapshot.automatique)
  );
  protected readonly instantanesAutomatiques = computed(() =>
    this.store.snapshots().filter((snapshot) => snapshot.automatique)
  );

  /** Assignments currently persisted, to compare a snapshot against. */
  protected readonly affectationsCourantes = signal<number | null>(null);


  /**
   * How a snapshot differs from the plan in place — restoring blind is exactly
   * what the screen should spare the user.
   */
  protected ecart(snapshot: PlanSnapshot): string {
    const courant = this.affectationsCourantes();
    if (courant === null) {
      return '';
    }
    const delta = snapshot.nombreAffectations - courant;
    if (delta === 0) {
      return $localize`:@@snapshots.delta.same:même nombre d'affectations qu'actuellement`;
    }
    return delta > 0
      ? $localize`:@@snapshots.delta.more:${delta}:delta: affectation(s) de plus qu'actuellement`
      : $localize`:@@snapshots.delta.less:${-delta}:delta: affectation(s) de moins qu'actuellement`;
  }
  protected readonly store = inject(PlanSnapshotStore);
  protected readonly jobs = inject(SolverJobService);

  protected readonly error = signal('');
  protected readonly message = signal('');
  protected readonly enCours = signal<number | 'capture' | null>(null);
  protected readonly locked = computed(() => this.jobs.editingLocked());

  private readonly api = inject(ApiService);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.recharger();
    void this.chargerAffectationsCourantes();
  }

  private async chargerAffectationsCourantes(): Promise<void> {
    try {
      const statut = await this.api.get<PersistenceStatus>('/api/planning/persisted/count');
      this.affectationsCourantes.set(statut.assignments);
    } catch {
      // Without it the delta column simply stays empty.
      this.affectationsCourantes.set(null);
    }
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
      const resultat = await this.restaurerSnapshot(snapshot);
      if (!resultat) {
        return;
      }
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

  private async restaurerSnapshot(snapshot: PlanSnapshot): Promise<RestaurationSnapshot | null> {
    return this.store.restaurer(snapshot.id);
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


  /** A refused restore names what is missing: that list is the actionable part. */
  private messageErreur(error: unknown): string {
    if (error instanceof ReferencesManquantesError) {
      return $localize`:@@snapshots.error.references:${error.message}:message: Références manquantes : ${error.references.join(', ')}:references:`;
    }
    return errorPrefix(error);
  }
}
