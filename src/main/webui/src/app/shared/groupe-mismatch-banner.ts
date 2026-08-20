import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PlanSnapshotStore, ReferencesManquantesError } from '../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../core/planning-resolution.store';
import { SolverJobService } from '../core/solver-job.service';

/**
 * App-wide warning shown on every screen once the persisted planning
 * (calendars, exports, ...) no longer matches the active groupe de créneaux:
 * the last solve was computed for a different group, so its result is stale
 * for the one now active. Silent otherwise, so it adds no noise on the
 * nominal path. Self-injects its data (like `app-data-stale-indicator`) so it can be
 * dropped once in the app shell instead of being wired into every page.
 *
 * Since issue #138 it also offers the remedy, not just the diagnosis: when a
 * snapshot exists for the active group, it can be restored right here instead
 * of re-solving from scratch.
 */
@Component({
  selector: 'app-groupe-mismatch-banner',
  imports: [MatButtonModule, MatCardModule, MatIconModule],
  template: `
    @if (message()) {
      <mat-card appearance="outlined" class="groupe-mismatch-banner">
        <mat-card-content>
          <mat-icon>warning</mat-icon>
          <p>{{ message() }}</p>
          @if (snapshotDuGroupeActif(); as snapshot) {
            <button
              matButton
              class="groupe-mismatch-banner-action"
              [disabled]="restauration() || jobs.solverBusy()"
              (click)="restaurer(snapshot.id)"
            >
              <mat-icon>restore</mat-icon>
              <span i18n="@@groupeMismatch.restore">Restaurer l'instantané de ce groupe</span>
            </button>
          }
        </mat-card-content>
        @if (erreur()) {
          <mat-card-content class="groupe-mismatch-banner-error">{{ erreur() }}</mat-card-content>
        }
      </mat-card>
    }
  `,
  styles: `
    .groupe-mismatch-banner {
      --mdc-outlined-card-container-color: var(--mat-sys-error-container);
      margin-bottom: 1rem;
    }
    .groupe-mismatch-banner mat-card-content {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      color: var(--mat-sys-on-error-container);
    }
    .groupe-mismatch-banner mat-icon {
      color: var(--mat-sys-error);
      flex-shrink: 0;
    }
    .groupe-mismatch-banner p {
      margin: 0;
    }
    .groupe-mismatch-banner-action {
      margin-left: auto;
      flex-shrink: 0;
    }
    .groupe-mismatch-banner-error {
      font: var(--mat-sys-body-small);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GroupeMismatchBanner {
  protected readonly jobs = inject(SolverJobService);
  protected readonly restauration = signal(false);
  protected readonly erreur = signal('');

  private readonly resolution = inject(PlanningResolutionStore);
  private readonly snapshots = inject(PlanSnapshotStore);

  protected readonly message = computed(() => {
    if (!this.resolution.stale()) {
      return '';
    }
    const solvedNom =
      this.resolution.resolution()?.groupeCreneauNom ?? $localize`:@@groupeMismatch.deletedGroup:groupe supprimé`;
    const activeNom = this.resolution.activeGroupe()?.nom ?? '';
    const constat = $localize`:@@groupeMismatch.constat:Le dernier calcul du planning a été effectué pour le groupe de créneaux « ${solvedNom}:solvedNom: », mais le groupe actif est désormais « ${activeNom}:activeNom: ».`;
    // Two very different situations behind one mismatch (issue #167): a
    // pre-solved group is one click away, an unsolved one needs the solver.
    const remede = this.snapshotDuGroupeActif()
      ? $localize`:@@groupeMismatch.remedeSnapshot:Un instantané résolu de ce groupe est disponible : restaurez-le, sans re-résolution.`
      : $localize`:@@groupeMismatch.remedeSolve:Ce groupe n'a pas encore été résolu : relancez le solveur, ou « Résoudre tous les groupes » (page Solveur) pour préparer chaque bascule à l'avance.`;
    return `${constat} ${remede}`;
  });

  /** Most recent snapshot computed for the group that is active now, if any. */
  protected readonly snapshotDuGroupeActif = computed(() => {
    const actif = this.resolution.activeGroupe()?.id;
    return actif ? (this.snapshots.parGroupe().get(actif) ?? null) : null;
  });

  /** True once the list has been asked for, so a failed load is not retried on every tick. */
  private demande = false;

  constructor() {
    // Loaded lazily, and only when a mismatch actually appears: the resolution
    // is fetched by the shell after this component is built, so this waits for
    // `stale()` to flip rather than looking once at construction time.
    effect(() => {
      if (this.resolution.stale() && !this.demande) {
        this.demande = true;
        this.snapshots.reload().catch(() => {
          // A banner must never break the page it sits on: without the list it
          // simply falls back to its original "diagnosis only" behaviour.
        });
      }
    });
  }

  protected async restaurer(id: number): Promise<void> {
    this.restauration.set(true);
    this.erreur.set('');
    try {
      await this.snapshots.restaurer(id);
      await this.resolution.reload();
      location.reload();
    } catch (error) {
      this.erreur.set(
        error instanceof ReferencesManquantesError
          ? $localize`:@@groupeMismatch.restoreFailed:Restauration impossible : ${error.references.join(', ')}:references: n'existent plus.`
          : $localize`:@@common.errorPrefix:Erreur : ${error instanceof Error ? error.message : String(error)}:message:`
      );
    } finally {
      this.restauration.set(false);
    }
  }
}
