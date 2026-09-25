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
import { protectionApplies } from './constraint-protection';
import { AnalysesApi } from './api/analyses-api';
import { ConstraintsApi } from './api/constraints-api';
import {
  CauseInfaisabilite,
  ConstraintsView,
  FeasibilityReport,
  GroupedArrivalReport,
  WalkSequenceReport,
  RapportPauses,
} from './models';
import { compterProblemes, construireProblemes } from './problemes';
import { ReferenceDataStore } from './reference-data.store';
import { animateurNames, standNames } from './reference-labels';
import { errorMessage } from './error-message';

@Injectable({ providedIn: 'root' })
export class ProblemesStore {
  /** `null` until loaded, or when the request failed (see `error`). */
  private readonly _report = signal<FeasibilityReport | null>(null);
  readonly report = this._report.asReadonly();
  private readonly _constraints = signal<ConstraintsView | null>(null);
  readonly constraints = this._constraints.asReadonly();
  /** The breaks of the persisted plan, for the relay-less ones; null until loaded or when the request failed. */
  private readonly _pauses = signal<RapportPauses | null>(null);
  readonly pauses = this._pauses.asReadonly();
  /** The tight walks of the persisted plan; null until loaded or when the request failed. */
  private readonly _walks = signal<WalkSequenceReport | null>(null);
  readonly walks = this._walks.asReadonly();
  /** The grouped arrivals of the persisted plan; null until loaded or when the request failed. */
  private readonly _groupedArrivals = signal<GroupedArrivalReport | null>(null);
  readonly groupedArrivals = this._groupedArrivals.asReadonly();
  private readonly _loading = signal(false);
  readonly loading = this._loading.asReadonly();
  private readonly _error = signal('');
  readonly error = this._error.asReadonly();

  readonly causes = computed<CauseInfaisabilite[]>(() => this.report()?.causes ?? []);

  /**
   * The CRITIQUE causes a solve cannot do anything about: a contradiction
   * between two hand-entered exceptions, or a forced assignment nobody can
   * honour. Each one guarantees a negative hard score whatever the time budget.
   *
   * A shortfall of animateurs is deliberately left out although it can be
   * CRITIQUE too: the solver still mitigates it, and the pre-solve banner has
   * always let that one through — this list is what the Solveur screen asks a
   * confirmation for, and asking on a short-staffed evening would make the
   * question meaningless.
   *
   * Reading `causes` is enough although the server caps that list at ten: they
   * are ranked with these first, so any that exists is in it.
   */
  readonly causesBloquantes = computed<CauseInfaisabilite[]>(() =>
    this.causes().filter(
      (cause) => cause.severite === 'CRITIQUE' && cause.type !== 'CRENEAU_SOUS_EFFECTIF',
    ),
  );
  /** True only once a report has actually been loaded and says so. */
  readonly infeasible = computed(() => this.report()?.feasible === false);

  private readonly referentiel = inject(ReferenceDataStore);
  /**
   * The causes name their stands by id; this names them as the Stands screen
   * does, from whatever the referential holds — an id the store does not know
   * (not loaded yet, deleted since) stays on screen as it is.
   */
  private readonly nomsStands = computed(() => standNames(this.referentiel.stands()));
  /** The readings name their animateurs by id; the organiser reads names. */
  private readonly nomsAnimateurs = computed(() => animateurNames(this.referentiel.animateurs()));

  readonly problemes = computed(() =>
    construireProblemes(
      this.report(),
      this.constraints()?.contraintes ?? [],
      this.constraints()?.contraintesAdHocEnCause ?? [],
      this.pauses(),
      this.nomsStands(),
      this.nomsAnimateurs(),
      this.walks(),
      this.groupedArrivals(),
    ),
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
    (this.constraints()?.contraintes ?? []).filter(
      // `protectionApplies` and not just `!actif`: a rule the catalogue ships
      // switched off (issue #595) is not a rule somebody switched off, and the
      // banner, the badge and the confirmation all cover the same set.
      (contrainte) => protectionApplies(contrainte) && !contrainte.actif,
    ),
  );

  /** The one line the Solveur screen says about relay-less breaks, empty when there is none. */
  readonly alertePausesSansRelais = computed(() => {
    const manquants = this.pauses()?.relaisManquants ?? 0;
    if (manquants === 0) {
      return '';
    }
    return $localize`:@@problemes.pauses.alerte:${manquants}:count: pause(s) légale(s) sans relais : une personne seule sur son stand pendant sa pause. Voir l'écran Pauses.`;
  });

  readonly alerteReglesLegales = computed(() => {
    const disabled = this.reglesLegalesDesactivees();
    if (disabled.length === 0) {
      return '';
    }
    const noms = disabled.map((contrainte) => contrainte.name).join(', ');
    return $localize`:@@constraints.legalDisabled:${disabled.length}:count: règle(s) légale(s) ou de sécurité désactivée(s) : ${noms}:noms:. Le solveur peut produire un planning contraire au Code du travail tout en affichant un score dur à zéro.`;
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
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly analysesApi = inject(AnalysesApi);

  /**
   * The Contraintes screen has just diagnosed the constraints itself: it
   * hands the view over so the shared « legal rules disabled » alert, which
   * the Solveur screen also reads, says the same thing without a second call.
   */
  shareConstraints(view: ConstraintsView): void {
    this._constraints.set(view);
  }

  /**
   * Reloads the pre-solve diagnostic only — what the reference pages and the
   * Données page need. Failures are swallowed into `error`: a page badging its
   * rows must keep working when the diagnostic is unavailable.
   */
  async reloadFeasibility(): Promise<void> {
    this._loading.set(true);
    try {
      this._report.set(await this.api.get<FeasibilityReport>('/api/feasibility'));
      this._error.set('');
    } catch (error) {
      this._report.set(null);
      this._error.set(errorMessage(error));
    } finally {
      this._loading.set(false);
    }
  }

  /** Reloads both sources, for the screens showing the full problem list. */
  async reload(): Promise<void> {
    this._loading.set(true);
    const [feasibility, constraints, pauses, walks, groupedArrivals] = await Promise.all([
      this.api.get<FeasibilityReport>('/api/feasibility').catch((error: unknown) => error as Error),
      this.constraintsApi.catalogue().catch((error: unknown) => error as Error),
      // Without the breaks the list is merely shorter: never a failure of the screen.
      this.analysesApi.breaks().catch(() => null),
      // Same for the tight walks.
      this.analysesApi.walks().catch(() => null),
      this.analysesApi.groupedArrivals().catch(() => null),
    ]);
    this._report.set(feasibility instanceof Error ? null : feasibility);
    this._constraints.set(constraints instanceof Error ? null : constraints);
    this._pauses.set(pauses && typeof pauses === 'object' && 'journees' in pauses ? pauses : null);
    this._walks.set(walks && typeof walks === 'object' && 'walks' in walks ? walks : null);
    this._groupedArrivals.set(
      groupedArrivals && typeof groupedArrivals === 'object' && 'groups' in groupedArrivals
        ? groupedArrivals
        : null,
    );
    const failure = [feasibility, constraints].find(
      (result): result is Error => result instanceof Error,
    );
    this._error.set(failure ? failure.message : '');
    this._loading.set(false);
  }
}
