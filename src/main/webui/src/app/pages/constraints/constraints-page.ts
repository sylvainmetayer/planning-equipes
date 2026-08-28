import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { urlLegifrance } from '../../core/legifrance';
import { intlLocale } from '../../core/locale';
import {
  DUREE_HEBDOMADAIRE_MAX_HEURES,
  DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES,
  ConstraintView,
  ConstraintsView,
  NiveauContrainte,
  ParametresLegaux
} from '../../core/models';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { LegalText } from '../../shared/legal-text';
import { StatusMessage } from '../../shared/status-message';
import { ViolationDetailsDialog } from '../../shared/violation-details-dialog';
import { errorPrefix } from '../../core/error-message';
import { LegalDisableConfirmService } from './legal-disable-dialog';

/** Called lazily (never at module scope, see `app.ts`'s `buildNavGroups`). */
function niveauLabel(niveau: NiveauContrainte): string {
  switch (niveau) {
    case 'HARD':
      return $localize`:@@constraints.niveau.hard:Dure (bloquante)`;
    case 'MEDIUM':
      return $localize`:@@constraints.niveau.medium:Moyenne (fortement pénalisée)`;
    case 'SOFT':
      return $localize`:@@constraints.niveau.soft:Souple (optimisée en dernier)`;
    default:
      return niveau;
  }
}

interface ConstraintGroup {
  categorie: string;
  items: ConstraintView[];
  /**
   * The group holds rules meant to be dosed rather than switched off — today
   * the MEDIUM ones of « Qualité d'organisation ». Its header then says what
   * weighting them against one another means, once, instead of thirteen times.
   */
  dosable: boolean;
}

/** Lowest weight the server accepts: zero is refused, switching off goes through the toggle. */
const POIDS_MIN = 1;

/** Highest weight the server accepts — mirrors `ParametresValidator.CONSTRAINT_WEIGHT_MAX`. */
const POIDS_MAX = 100;

/**
 * Business catalogue of the solver rules (`GET /api/constraints`), enriched
 * with the score of the latest analysis when one is available.
 */
@Component({
  selector: 'app-constraints-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule,
    FeasibilityBanner,
    LegalText,
    StatusMessage
  ],
  templateUrl: './constraints-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ConstraintsPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly view = signal<ConstraintsView | null>(null);
  protected readonly togglingConstraint = signal<string | null>(null);

  protected readonly poidsMin = POIDS_MIN;
  protected readonly poidsMax = POIDS_MAX;

  protected readonly parametresLoading = signal(false);
  protected readonly parametresError = signal('');
  protected readonly parametresSaved = signal(false);
  protected readonly dureeHebdomadaireMaxHeures = signal<number | null>(null);
  protected readonly dureeHebdomadaireMaxMineurHeures = signal<number | null>(null);

  /** Ordre public ceilings, mirrored from the server-side validation. */
  protected readonly plafondMajeurHeures = DUREE_HEBDOMADAIRE_MAX_HEURES;
  protected readonly plafondMineurHeures = DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES;

  // The two ceilings are ordre public: the page states which article says so,
  // and links it, rather than asking the reader to take our word for it.
  protected readonly articlePlafondMajeur = urlLegifrance('L3121-20');
  protected readonly articlePlafondMineur = urlLegifrance('L3162-1');

  protected readonly feasibility = computed(() => this.view()?.faisabilite ?? null);
  protected readonly hardScore = computed(() => this.view()?.hardScore ?? null);
  protected readonly hardIssues = computed(
    () =>
      this.view()?.contraintes
        .filter((constraint) => constraint.niveau === 'HARD' && (constraint.matchCount ?? 0) > 0)
        .map((constraint) => ({ name: constraint.name, matchCount: constraint.matchCount ?? 0 })) ?? []
  );

  protected readonly jobs = inject(SolverJobService);
  private readonly problemes = inject(ProblemesStore);

  /** Shared with the Solveur screen: see `ProblemesStore.alerteReglesLegales`. */
  protected readonly alerteReglesLegales = computed(() => this.problemes.alerteReglesLegales());

  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly legalDisable = inject(LegalDisableConfirmService);

  protected readonly summary = computed(() => {
    const view = this.view();
    if (!view) {
      return '';
    }
    if (!view.analysedAt) {
      return $localize`:@@constraints.summary.none:Aucune analyse pour le moment — lancez une résolution depuis la page Solveur pour voir le score de chaque règle.`;
    }
    const analysedAt = new Date(view.analysedAt).toLocaleString(intlLocale());
    const score = view.scoreGlobal;
    const postesNonPourvus = view.postesNonPourvus;
    return $localize`:@@constraints.summary.latest:Dernière analyse ${analysedAt}:date: — score ${score}:score:, ${postesNonPourvus}:count: poste(s) non pourvu(s).`;
  });

  /** Sort key of the constraint cards: by score, the "what costs most" question. */
  protected readonly triParScore = signal(false);

  protected basculerTri(): void {
    this.triParScore.update((actif) => !actif);
  }

  protected readonly groups = computed<ConstraintGroup[]>(() => {
    const constraints = this.view()?.contraintes ?? [];
    const groups = new Map<string, ConstraintView[]>();
    constraints.forEach((constraint) => {
      const items = groups.get(constraint.categorie) ?? [];
      items.push(constraint);
      groups.set(constraint.categorie, items);
    });
    const parScore = this.triParScore();
    return Array.from(groups.entries()).map(([categorie, items]) => ({
      categorie,
      items: parScore ? [...items].sort((a, b) => (b.matchCount ?? 0) - (a.matchCount ?? 0)) : items,
      dosable: items.some((constraint) => constraint.dosable)
    }));
  });

  constructor() {
    void this.loadConstraints();
    void this.loadParametresLegaux();
    // Every solve writes a fresh analysis server-side: reload the scored view
    // once one lands, whichever browser started it.
    // SolverJobService.reportFinishedJob already raises the feasibility
    // notification itself (it must run whether or not this page is mounted).
    // Unregistered on destroy: this page is lazy-loaded and rebuilt on every
    // navigation, so keeping the handler would stack one more copy per visit.
    const destroyRef = inject(DestroyRef);
    for (const type of ['SOLVE', 'SOLVE_INCREMENTAL'] as const) {
      destroyRef.onDestroy(this.jobs.onResult(type, () => void this.loadConstraints()));
    }
  }

  private async loadConstraints(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.apply(await this.api.get<ConstraintsView>('/api/constraints'));
    } catch (error) {
      this.view.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Refresh button: re-derives the analysis from the plan currently persisted
   * and shows it. It used to submit a full solve whose result was thrown away
   * — minutes of solver time, and a score describing a planning no screen
   * would ever display. Nothing is solved here, so the button stays available
   * while a solve runs; what it scores is simply the plan that solve is about
   * to replace.
   */
  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.apply(await this.api.post<ConstraintsView>('/api/constraints/diagnostic', {}));
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  private apply(view: ConstraintsView): void {
    this.view.set(view);
    // Feeds the shared "legal rules disabled" alert, which the Solveur screen
    // also reads.
    this.problemes.constraints.set(view);
  }

  protected async loadParametresLegaux(): Promise<void> {
    this.parametresLoading.set(true);
    this.parametresError.set('');
    try {
      const parametres = await this.api.get<ParametresLegaux>('/api/parametres-legaux');
      this.dureeHebdomadaireMaxHeures.set(parametres.dureeHebdomadaireMaxMinutes / 60);
      this.dureeHebdomadaireMaxMineurHeures.set(parametres.dureeHebdomadaireMaxMineurMinutes / 60);
    } catch (error) {
      this.parametresError.set(errorPrefix(error));
    } finally {
      this.parametresLoading.set(false);
    }
  }

  /**
   * Saves both weekly ceilings. The bounds mirror the server-side check
   * (`ReferenceDataService.updateParametresLegaux`): a value above the ordre
   * public maximum is refused here too, so the administrator gets an
   * explanation rather than an HTTP 500. A lower value stays free — it is more
   * protective than the law.
   */
  protected async saveParametresLegaux(): Promise<void> {
    const heures = this.dureeHebdomadaireMaxHeures();
    const heuresMineur = this.dureeHebdomadaireMaxMineurHeures();
    if (heures === null || heures <= 0 || heuresMineur === null || heuresMineur <= 0) {
      return;
    }
    if (heures > this.plafondMajeurHeures) {
      this.parametresError.set(
        $localize`:@@constraints.legal.error.plafondMajeur:La durée hebdomadaire maximale des majeurs ne peut pas dépasser ${this.plafondMajeurHeures}:hours: h (Code du travail art. L3121-20, disposition d'ordre public).`
      );
      return;
    }
    if (heuresMineur > this.plafondMineurHeures) {
      this.parametresError.set(
        $localize`:@@constraints.legal.error.plafondMineur:La durée hebdomadaire maximale des mineurs ne peut pas dépasser ${this.plafondMineurHeures}:hours: h (Code du travail art. L3162-1).`
      );
      return;
    }
    this.parametresLoading.set(true);
    this.parametresError.set('');
    this.parametresSaved.set(false);
    try {
      const parametres = await this.api.put<ParametresLegaux>('/api/parametres-legaux', {
        dureeHebdomadaireMaxMinutes: Math.round(heures * 60),
        dureeHebdomadaireMaxMineurMinutes: Math.round(heuresMineur * 60)
      });
      this.dureeHebdomadaireMaxHeures.set(parametres.dureeHebdomadaireMaxMinutes / 60);
      this.dureeHebdomadaireMaxMineurHeures.set(parametres.dureeHebdomadaireMaxMineurMinutes / 60);
      this.parametresSaved.set(true);
    } catch (error) {
      this.parametresError.set(errorPrefix(error));
    } finally {
      this.parametresLoading.set(false);
    }
  }

  /**
   * Toggles a constraint on/off for the next solve. Applied optimistically so
   * the switch reacts instantly; rolled back if the save fails.
   *
   * Switching off a rule that founds the plan in law goes through a
   * confirmation first (`LegalDisableConfirmService`): the solver would then
   * return a plan scoring zero hard that still breaks the Code du travail, and
   * nothing else would say so. Re-enabling never asks — putting a legal rule
   * back needs no ceremony.
   *
   * The switch is given back its previous state by hand on every path that
   * does not end on `actif`: it flipped itself on click, while `[checked]`
   * still evaluates to the value it already had, so Angular sees no change and
   * writes nothing back to it. Left alone it would show « désactivée » for a
   * rule that stayed active.
   */
  protected async toggleConstraint(constraint: ConstraintView, event: MatSlideToggleChange): Promise<void> {
    const actif = event.checked;
    if (!actif && !(await this.legalDisable.allowsDisabling(constraint))) {
      event.source.checked = !actif;
      return;
    }
    this.setConstraintActif(constraint.name, actif);
    this.togglingConstraint.set(constraint.name);
    this.error.set('');
    try {
      await this.api.put<{ actif: boolean }>(`/api/constraints/${encodeURIComponent(constraint.name)}`, { actif });
    } catch (error) {
      this.setConstraintActif(constraint.name, !actif);
      event.source.checked = !actif;
      this.error.set(errorPrefix(error));
    } finally {
      this.togglingConstraint.set(null);
    }
  }

  protected actifLabel(actif: boolean): string {
    return actif ? $localize`:@@constraints.active:Active` : $localize`:@@constraints.disabled:Désactivée`;
  }

  /**
   * Reads what was typed, brings it back into the range the server accepts and
   * saves it. The field itself is rewritten with the value actually sent, so it
   * never keeps showing a number nobody stored — an empty field, a stray letter
   * or a 0 all fall back to the current weight or to the nearest bound.
   */
  protected async onPoidsChange(constraint: ConstraintView, field: HTMLInputElement): Promise<void> {
    const saisi = Number(field.value);
    const borne =
      field.value.trim() === '' || Number.isNaN(saisi)
        ? constraint.poids
        : Math.min(Math.max(Math.round(saisi), POIDS_MIN), POIDS_MAX);
    field.value = String(borne);
    await this.setPoids(constraint, borne);
  }

  /**
   * Saves the weight of one rule for the current edition. Same optimistic
   * shape as the toggle: the value stays where the user left it, and rolls back
   * with an error message if the save fails.
   */
  protected async setPoids(constraint: ConstraintView, poids: number): Promise<void> {
    const borne = Math.min(Math.max(Math.round(poids), POIDS_MIN), POIDS_MAX);
    const precedent = constraint.poids;
    if (borne === precedent) {
      return;
    }
    this.patchConstraint(constraint.name, { poids: borne });
    this.error.set('');
    try {
      const enregistre = await this.api.put<{ poids: number }>(
        `/api/constraints/${encodeURIComponent(constraint.name)}/poids`,
        { poids: borne }
      );
      this.patchConstraint(constraint.name, { poids: enregistre.poids });
    } catch (error) {
      this.patchConstraint(constraint.name, { poids: precedent });
      this.error.set(errorPrefix(error));
    }
  }

  private setConstraintActif(name: string, actif: boolean): void {
    this.patchConstraint(name, { actif });
  }

  private patchConstraint(name: string, patch: Partial<ConstraintView>): void {
    const view = this.view();
    if (!view) {
      return;
    }
    this.view.set({
      ...view,
      contraintes: view.contraintes.map((constraint) =>
        constraint.name === name ? { ...constraint, ...patch } : constraint
      )
    });
  }

  protected niveauLabel(niveau: NiveauContrainte): string {
    return niveauLabel(niveau);
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

  /** Opens the who/what/when detail popup — only ever called for a HARD constraint with matches (see the template). */
  protected showViolations(constraint: ConstraintView): void {
    this.dialog.open(ViolationDetailsDialog, {
      data: {
        constraintName: constraint.name,
        description: constraint.description,
        matchCount: constraint.matchCount ?? constraint.violations.length,
        violations: constraint.violations
      },
      width: '36rem'
    });
  }

  protected resultLabel(constraint: ConstraintView): string {
    if (constraint.score === null || constraint.score === undefined) {
      return $localize`:@@constraints.result.notEvaluated:Pas encore évaluée.`;
    }
    const matchCount = constraint.matchCount ?? 0;
    const score = constraint.score;
    return matchCount > 0
      ? $localize`:@@constraints.result.matches:${matchCount}:count: correspondance(s) — score ${score}:score:`
      : $localize`:@@constraints.result.satisfied:Satisfaite — score ${score}:score:`;
  }
}
