// Cross-page state for the alerting UI: the solver-free feasibility diagnostic
// (`GET /api/feasibility`) and the diagnostic of the last analysed solve
// (`GET /api/constraints`), merged into one severity-ranked problem list.
//
// Shared rather than duplicated per page because five screens read the same
// data: the Problèmes page, the summary banner of the Solveur page, the
// pre-solve banner of the Données page and the row badges of the three
// reference tables. Every page reloads what it needs on entry, so navigating
// back to a screen never shows a diagnostic from a previous dataset.
//
// Nothing here is persisted to localStorage/sessionStorage: it is server state,
// re-read on demand.

import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { CauseInfaisabilite, ConstraintsView, FeasibilityReport } from './models';
import { compterProblemes, construireProblemes } from './problemes';
import { errorMessage } from './error-message';

@Injectable({ providedIn: 'root' })
export class ProblemesStore {
  /** `null` until loaded, or when the request failed (see `error`). */
  readonly report = signal<FeasibilityReport | null>(null);
  readonly constraints = signal<ConstraintsView | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');

  readonly causes = computed<CauseInfaisabilite[]>(() => this.report()?.causes ?? []);
  /** True only once a report has actually been loaded and says so. */
  readonly infeasible = computed(() => this.report()?.feasible === false);

  readonly problemes = computed(() =>
    construireProblemes(
      this.report(),
      this.constraints()?.contraintes ?? [],
      this.constraints()?.contraintesAdHocEnCause ?? []
    )
  );
  readonly comptage = computed(() => compterProblemes(this.problemes()));

  /**
   * Protected rules currently switched off, and the sentence that says so.
   *
   * Disabling one lets the solver return a plan with a hard score of zero that
   * still breaks the Code du travail, and nothing records the decision beyond
   * the confirmation asked at the time (`LegalDisableDialog`): no reason typed,
   * nothing journalled. The state itself is therefore kept visible wherever a
   * solve is launched or judged — the Solveur and Contraintes screens both
   * read this.
   *
   * `protegee` rather than a match on the category label: the server decides
   * which rules found the plan in law or in the minors' safety policy
   * (`ConstraintCatalog.CATEGORIES_PROTEGEES`), and this banner must cover
   * exactly the set the confirmation covers — the dialog promises as much.
   */
  readonly reglesLegalesDesactivees = computed(() =>
    (this.constraints()?.contraintes ?? []).filter((contrainte) => contrainte.protegee && !contrainte.actif)
  );

  readonly alerteReglesLegales = computed(() => {
    const desactivees = this.reglesLegalesDesactivees();
    if (desactivees.length === 0) {
      return '';
    }
    const noms = desactivees.map((contrainte) => contrainte.name).join(', ');
    return $localize`:@@constraints.legalDisabled:${desactivees.length}:count: règle(s) légale(s) ou de sécurité désactivée(s) : ${noms}:noms:. Le solveur peut produire un planning contraire au Code du travail tout en affichant un score dur à zéro.`;
  });

  /**
   * Stand id → the first cause naming it, for the row badges. Keys are the raw
   * stand ids; a stand can appear in several causes, only the most severe one
   * (causes are ranked server-side) is kept, since a tooltip shows one message.
   */
  readonly causeParStandId = computed<Map<string, CauseInfaisabilite>>(() => {
    const index = new Map<string, CauseInfaisabilite>();
    for (const cause of this.causes()) {
      for (const standId of cause.standIds ?? []) {
        if (!index.has(standId)) {
          index.set(standId, cause);
        }
      }
    }
    return index;
  });

  /** Créneau id → its first cause. Keys are stringified: the backend sends a number. */
  readonly causeParCreneauId = computed<Map<string, CauseInfaisabilite>>(() => {
    const index = new Map<string, CauseInfaisabilite>();
    for (const cause of this.causes()) {
      if (cause.creneauId === null || cause.creneauId === undefined) {
        continue;
      }
      const key = String(cause.creneauId);
      if (!index.has(key)) {
        index.set(key, cause);
      }
    }
    return index;
  });

  /**
   * Ad hoc constraint id → the first cause naming it, for the row badges of the
   * ad hoc screen. A contradiction names two or three exceptions and each of
   * them is badged: which one to delete is the user's arbitration, not ours.
   */
  readonly causeParContrainteAdHocId = computed<Map<string, CauseInfaisabilite>>(() => {
    const index = new Map<string, CauseInfaisabilite>();
    for (const cause of this.causes()) {
      for (const contrainteId of cause.contrainteIds ?? []) {
        if (!index.has(contrainteId)) {
          index.set(contrainteId, cause);
        }
      }
    }
    return index;
  });

  /** ISO date → its first CRITIQUE cause, to flag animateurs unavailable that day. */
  readonly causeCritiqueParDate = computed<Map<string, CauseInfaisabilite>>(() => {
    const index = new Map<string, CauseInfaisabilite>();
    for (const cause of this.causes()) {
      if (cause.severite !== 'CRITIQUE' || !cause.date) {
        continue;
      }
      if (!index.has(cause.date)) {
        index.set(cause.date, cause);
      }
    }
    return index;
  });

  private readonly api = inject(ApiService);

  /**
   * Reloads the pre-solve diagnostic only — what the reference pages and the
   * Données page need. Failures are swallowed into `error`: a page badging its
   * rows must keep working when the diagnostic is unavailable.
   */
  async reloadFeasibility(): Promise<void> {
    this.loading.set(true);
    try {
      this.report.set(await this.api.get<FeasibilityReport>('/api/feasibility'));
      this.error.set('');
    } catch (error) {
      this.report.set(null);
      this.error.set(errorMessage(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** Reloads both sources, for the screens showing the full problem list. */
  async reload(): Promise<void> {
    this.loading.set(true);
    const [feasibility, constraints] = await Promise.all([
      this.api.get<FeasibilityReport>('/api/feasibility').catch((error: unknown) => error as Error),
      this.api.get<ConstraintsView>('/api/constraints').catch((error: unknown) => error as Error)
    ]);
    this.report.set(feasibility instanceof Error ? null : feasibility);
    this.constraints.set(constraints instanceof Error ? null : constraints);
    const failure = [feasibility, constraints].find((result): result is Error => result instanceof Error);
    this.error.set(failure ? failure.message : '');
    this.loading.set(false);
  }
}
