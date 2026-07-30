import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api.service';
import { ConstraintView, ConstraintsView, NiveauContrainte } from '../../core/models';
import { FeasibilityBanner } from '../../shared/feasibility-banner';

const NIVEAU_LABELS: Record<NiveauContrainte, string> = {
  HARD: 'Dure (bloquante)',
  MEDIUM: 'Medium (fortement pénalisée)',
  SOFT: 'Souple (optimisée en dernier)'
};

interface ConstraintGroup {
  categorie: string;
  items: ConstraintView[];
}

/**
 * Business catalogue of the solver rules (`GET /api/constraints`), enriched
 * with the score of the latest analysis when one is available.
 */
@Component({
  selector: 'app-constraints-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, MatChipsModule, MatProgressBarModule, FeasibilityBanner],
  templateUrl: './constraints-page.html'
})
export class ConstraintsPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly view = signal<ConstraintsView | null>(null);

  protected readonly feasibility = computed(() => this.view()?.faisabilite ?? null);

  private readonly api = inject(ApiService);

  protected readonly summary = computed(() => {
    const view = this.view();
    if (!view) {
      return '';
    }
    if (!view.analysedAt) {
      return 'No analysis yet — run "Analyze" from the Solver page to see how each rule scored.';
    }
    const analysedAt = new Date(view.analysedAt).toLocaleString();
    return `Last analysis ${analysedAt} — score ${view.scoreGlobal}, ${view.postesNonPourvus} unfilled seats.`;
  });

  protected readonly groups = computed<ConstraintGroup[]>(() => {
    const constraints = this.view()?.contraintes ?? [];
    const groups = new Map<string, ConstraintView[]>();
    constraints.forEach((constraint) => {
      const items = groups.get(constraint.categorie) ?? [];
      items.push(constraint);
      groups.set(constraint.categorie, items);
    });
    return Array.from(groups.entries()).map(([categorie, items]) => ({ categorie, items }));
  });

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.view.set(await this.api.get<ConstraintsView>('/api/constraints'));
    } catch (error) {
      this.view.set(null);
      this.error.set(`Error: ${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.loading.set(false);
    }
  }

  protected niveauLabel(niveau: NiveauContrainte): string {
    return NIVEAU_LABELS[niveau] ?? niveau;
  }

  protected badgeClass(niveau: NiveauContrainte): string {
    return `constraint-badge constraint-badge-${niveau.toLowerCase()}`;
  }

  protected resultClass(constraint: ConstraintView): string {
    if (constraint.score === null || constraint.score === undefined) {
      return 'constraint-result constraint-result-empty';
    }
    return `constraint-result ${(constraint.matchCount ?? 0) > 0 ? 'constraint-result-hit' : 'constraint-result-clean'}`;
  }

  protected resultLabel(constraint: ConstraintView): string {
    if (constraint.score === null || constraint.score === undefined) {
      return 'Not evaluated yet.';
    }
    return (constraint.matchCount ?? 0) > 0
      ? `${constraint.matchCount} match(es) — score ${constraint.score}`
      : `Satisfied — score ${constraint.score}`;
  }
}
