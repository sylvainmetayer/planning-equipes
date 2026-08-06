import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PlanningResolutionStore } from '../core/planning-resolution.store';

/**
 * App-wide warning shown on every screen once the persisted planning
 * (calendars, exports, ...) no longer matches the active groupe de créneaux:
 * the last solve was computed for a different group, so its result is stale
 * for the one now active. Silent otherwise, so it adds no noise on the
 * nominal path. Self-injects its data (like `app-job-monitor`) so it can be
 * dropped once in the app shell instead of being wired into every page.
 */
@Component({
  selector: 'app-groupe-mismatch-banner',
  imports: [MatCardModule, MatIconModule],
  template: `
    @if (message()) {
      <mat-card appearance="outlined" class="groupe-mismatch-banner">
        <mat-card-content>
          <mat-icon>warning</mat-icon>
          <p>{{ message() }}</p>
        </mat-card-content>
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GroupeMismatchBanner {
  private readonly resolution = inject(PlanningResolutionStore);

  protected readonly message = computed(() => {
    if (!this.resolution.stale()) {
      return '';
    }
    const solvedNom =
      this.resolution.resolution()?.groupeCreneauNom ?? $localize`:@@groupeMismatch.deletedGroup:groupe supprimé`;
    const activeNom = this.resolution.activeGroupe()?.nom ?? '';
    return $localize`:@@groupeMismatch.message:Le dernier calcul du planning a été effectué pour le groupe de créneaux « ${solvedNom}:solvedNom: », mais le groupe actif est désormais « ${activeNom}:activeNom: ». Relancez le solveur pour obtenir un résultat à jour pour ce groupe.`;
  });
}
