import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { intlLocale } from '../../core/locale';
import { NiveauProbleme, niveauProblemeLabel } from '../../core/problemes';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { LegalText } from '../../shared/legal-text';

/**
 * Every known problem of the current dataset, most blocking first: the
 * solver-free feasibility causes (available before any solve) merged with the
 * violations of the last analysed solve.
 *
 * Read-only: fixing a problem happens on the page that owns the data (stands,
 * animateurs, créneaux, contraintes), which badges the very rows named here.
 */
@Component({
  selector: 'app-problemes-page',
  imports: [
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressBarModule,
    LegalText,
  ],
  templateUrl: './problemes-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProblemesPage {
  protected readonly store = inject(ProblemesStore);
  protected readonly jobs = inject(SolverJobService);

  /** Pre-labelled rows, so the template never calls a function per row. */
  protected readonly problemes = computed(() =>
    this.store.problemes().map((probleme) => ({
      ...probleme,
      niveauLabel: niveauProblemeLabel(probleme.niveau),
      badgeClass: this.badgeClass(probleme.niveau),
    })),
  );

  protected readonly comptage = computed(() => this.store.comptage());

  /** Causes hidden by the server-side cap of `FeasibilityReport.causes`. */
  protected readonly causesRestantes = computed(() => {
    const report = this.store.report();
    return report ? Math.max(0, report.totalCauses - report.causes.length) : 0;
  });

  protected readonly resumeFaisabilite = computed(() => {
    const report = this.store.report();
    if (!report) {
      return $localize`:@@problemes.feasibility.unavailable:Le diagnostic de faisabilité n'a pas pu être chargé.`;
    }
    return report.feasible
      ? $localize`:@@problemes.feasibility.ok:Aucun problème de capacité détecté sur les données actuelles.`
      : report.message;
  });

  protected readonly resumeAnalyse = computed(() => {
    const constraints = this.store.constraints();
    if (!constraints?.analysedAt) {
      return $localize`:@@problemes.analysis.none:Aucune analyse de résolution pour le moment : lancez une résolution depuis la page Solveur pour voir les règles en défaut.`;
    }
    const analysedAt = new Date(constraints.analysedAt).toLocaleString(intlLocale());
    const score = constraints.scoreGlobal ?? '—';
    return $localize`:@@problemes.analysis.latest:Dernière analyse ${analysedAt}:date: — score ${score}:score:.`;
  });

  constructor() {
    void this.store.reload();
    // A solve started from anywhere (this browser or another) rewrites both
    // sources: refresh once it lands. Unregistered on destroy, like every
    // other lazy-loaded page's handler.
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(this.jobs.onResult('SOLVE', () => void this.store.reload()));
  }

  protected refresh(): void {
    void this.store.reload();
  }

  private badgeClass(niveau: NiveauProbleme): string {
    return `probleme-badge probleme-badge-${niveau.toLowerCase()}`;
  }
}
