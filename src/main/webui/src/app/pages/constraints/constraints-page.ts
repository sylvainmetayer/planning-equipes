import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { protectionApplies } from '../../core/constraint-protection';
import { intlLocale } from '../../core/locale';
import {
  AxePivot,
  ConstraintView,
  ConstraintsView,
  NiveauContrainte,
  ParametreContrainte,
} from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { LegalText } from '../../shared/legal-text';
import { StatusMessage } from '../../shared/status-message';
import { ViolationDetailsDialog } from '../../shared/violation-details-dialog';
import { errorPrefix } from '../../core/error-message';
import { LegalDisableConfirmService } from './legal-disable-dialog';
import { classeCellule, ColonnePivot, buildPivot, cellLink } from './ecarts-pivot';
import { nextGridCell } from '../../core/grid-navigation';

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

/**
 * Anchor of a category card: « Légal (mineurs) » becomes
 * `categorie-legal-mineurs`. Prefixed, so a slug can never collide with the
 * anchor of a rule — which is the rule's own name, the identifier the API
 * serves.
 */
function categoryAnchor(categorie: string): string {
  const slug = categorie
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
  return `categorie-${slug}`;
}

interface ConstraintGroup {
  categorie: string;
  items: ConstraintView[];
  /** Id of the group's card, target of the summary link (see `categoryAnchor`). */
  ancre: string;
  /**
   * The group holds rules meant to be dosed rather than switched off — today
   * the MEDIUM ones of « Qualité d'organisation ». Its header then says what
   * weighting them against one another means, once, instead of thirteen times.
   */
  dosable: boolean;
}

/** The three readings of « où se concentrent les écarts » (issue #496). */
const AXES: AxePivot[] = ['JOUR', 'STAND', 'ANIMATEUR'];

/** Called lazily, like `niveauLabel`: never at module scope. */
function axeLabel(axe: AxePivot): string {
  switch (axe) {
    case 'JOUR':
      return $localize`:@@constraints.pivot.axe.jour:Par journée`;
    case 'STAND':
      return $localize`:@@constraints.pivot.axe.stand:Par stand`;
    case 'ANIMATEUR':
      return $localize`:@@constraints.pivot.axe.animateur:Par animateur`;
  }
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
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule,
    RouterLink,
    FeasibilityBanner,
    LegalText,
    StatusMessage,
  ],
  templateUrl: './constraints-page.html',
  styleUrls: ['../../../styles/heatmap.css', './constraints-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConstraintsPage {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly view = signal<ConstraintsView | null>(null);
  protected readonly togglingConstraint = signal<string | null>(null);

  protected readonly poidsMin = POIDS_MIN;
  protected readonly poidsMax = POIDS_MAX;

  protected readonly feasibility = computed(() => this.view()?.faisabilite ?? null);
  protected readonly hardScore = computed(() => this.view()?.hardScore ?? null);
  protected readonly hardIssues = computed(
    () =>
      this.view()
        ?.contraintes.filter(
          (constraint) => constraint.niveau === 'HARD' && (constraint.matchCount ?? 0) > 0,
        )
        .map((constraint) => ({ name: constraint.name, matchCount: constraint.matchCount ?? 0 })) ??
      [],
  );

  protected readonly jobs = inject(SolverJobService);
  private readonly problemes = inject(ProblemesStore);
  private readonly referentiel = inject(ReferenceDataStore);

  /* --------- Where the breaches concentrate (issue #496) --------- */

  protected readonly axes = AXES.map((axe) => ({ value: axe, label: axeLabel(axe) }));
  protected readonly axe = signal<AxePivot>('JOUR');
  /** The cell the reader opened, or null — its lines are listed under the table. */
  protected readonly openedCell = signal<{ contrainte: string; cle: string } | null>(null);
  /**
   * The pivot is folded until asked for. It answers « où » — a question one
   * only has once the list above has said « combien » — and unfolding a
   * forty-column table over the rules nobody came for is how a screen stops
   * being read.
   */
  protected readonly pivotOuvert = signal(false);

  /**
   * The cross-table of the selected axis. Days read chronologically, which is
   * the only order that answers « est-ce le week-end » ; stands and animateurs
   * read most-breaches-first, left to right.
   */
  protected readonly pivot = computed(() => {
    const axe = this.axe();
    return buildPivot(
      this.view()?.pivotEcarts ?? [],
      axe,
      (cle) => this.libellePivot(axe, cle),
      axe === 'JOUR' ? (a: ColonnePivot, b: ColonnePivot) => a.cle.localeCompare(b.cle) : undefined,
    );
  });

  protected classeCellule(ecarts: number, maximum: number): string {
    return classeCellule(ecarts, maximum);
  }

  /** « 4 » alone says neither which rule nor where: the cell names both. */
  protected cellLabel(contrainte: string, colonne: ColonnePivot, ecarts: number): string {
    return $localize`:@@constraints.pivot.cellLabel:${contrainte}:contrainte: — ${colonne.libelle}:colonne: : ${ecarts}:ecarts: écart(s)`;
  }

  /**
   * The cell the pivot hands the focus to (roving tabindex): one stop for the
   * whole table on Tab, then the arrows move inside it, through the same
   * `core/grid-navigation` as the marge grid. Clamped to the table on screen,
   * since a change of axis reshapes it and a position past the last row would
   * take the grid out of the tab order.
   */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });

  private readonly focusedPosition = computed(() => {
    const { lignes, colonnes } = this.pivot();
    const { ligne, colonne } = this.focusedCell();
    return {
      ligne: Math.min(Math.max(ligne, 0), Math.max(lignes.length - 1, 0)),
      colonne: Math.min(Math.max(colonne, 0), Math.max(colonnes.length - 1, 0)),
    };
  });

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected onCellKeydown(event: KeyboardEvent, ligne: number, colonne: number): void {
    const { lignes, colonnes } = this.pivot();
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      const row = lignes[ligne];
      if (row) {
        this.openCell(row.contrainte, colonnes[colonne], row.ecarts[colonne] ?? 0);
      }
      return;
    }
    const lastRow = lignes.length - 1;
    const lastColumn = colonnes.length - 1;
    const target = nextGridCell(event.key, { ligne, colonne }, lastRow, lastColumn);
    if (!target) {
      return;
    }
    event.preventDefault();
    this.focusedCell.set(target);
    this.host.nativeElement
      .querySelector<HTMLElement>(
        `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`,
      )
      ?.focus();
  }

  /** Whether this rule wears the badge — the same set the confirmation covers. */
  protected readonly protectionApplies = protectionApplies;

  /** What the reader sees in a column header: a date, a stand's name, a full name. */
  private libellePivot(axe: AxePivot, cle: string): string {
    if (axe === 'STAND') {
      const stand = this.referentiel.stands().find((candidate) => candidate.id === cle);
      return stand?.nom || cle;
    }
    if (axe === 'ANIMATEUR') {
      const animateur = this.referentiel.animateurs().find((candidate) => candidate.id === cle);
      return animateur ? `${animateur.prenom} ${animateur.nom}`.trim() : cle;
    }
    return cle;
  }

  protected openCell(contrainte: string, colonne: ColonnePivot, ecarts: number): void {
    if (ecarts === 0) {
      return;
    }
    const opened = this.openedCell();
    this.openedCell.set(
      opened && opened.contrainte === contrainte && opened.cle === colonne.cle
        ? null
        : { contrainte, cle: colonne.cle },
    );
  }

  /**
   * The lines behind the opened cell.
   *
   * Only the hard rules carry their lines: the server lists them for those
   * alone, medium and soft ones running into the thousands of matches. The
   * pivot itself covers every rule — counting is what is cheap — so a cell of a
   * medium rule opens on a count and a sentence saying why, rather than on
   * nothing.
   */
  protected readonly detailCellule = computed(() => {
    const opened = this.openedCell();
    if (opened === null) {
      return null;
    }
    const axe = this.axe();
    const contrainte = this.view()?.contraintes.find(
      (candidate) => candidate.name === opened.contrainte,
    );
    const ecarts =
      this.view()?.pivotEcarts.find(
        (cellule) =>
          cellule.axe === axe &&
          cellule.contrainte === opened.contrainte &&
          cellule.cle === opened.cle,
      )?.ecarts ?? 0;
    const lignes = (contrainte?.references ?? [])
      .filter((reference) => this.referenceTouche(axe, reference, opened.cle))
      .map((reference) => reference.texte);
    return {
      contrainte: opened.contrainte,
      colonne: this.libellePivot(axe, opened.cle),
      ecarts,
      lignes,
      listable: contrainte?.niveau === 'HARD',
      // What to do about it, where to go and do it, and what an easing costs:
      // « 210 écarts ici » is a measurement, and a reader who cannot act on a
      // screen stops opening it (review of issue #496).
      remediation: contrainte?.remediation ?? '',
      lien: cellLink(axe, opened.cle, this.libellePivot(axe, opened.cle)),
      poids: contrainte?.poids ?? 1,
      niveau: contrainte ? niveauLabel(contrainte.niveau) : '',
      dosable: contrainte?.dosable ?? false,
      protegee: contrainte?.protegee ?? false,
    };
  });

  /** Whether a violation line names that key on that axis — the ids the server sends with it. */
  private referenceTouche(
    axe: AxePivot,
    reference: { animateurId: string | null; standId: string | null; creneauId: number | null },
    cle: string,
  ): boolean {
    if (axe === 'ANIMATEUR') {
      return reference.animateurId === cle;
    }
    if (axe === 'STAND') {
      return reference.standId === cle;
    }
    const creneau = this.referentiel
      .creneaux()
      .find((candidate) => candidate.id === reference.creneauId);
    return creneau?.date === cle;
  }

  /** Shared with the Solveur screen: see `ProblemesStore.alerteReglesLegales`. */
  protected readonly alerteReglesLegales = computed(() => this.problemes.alerteReglesLegales());

  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly dialog = inject(MatDialog);
  private readonly legalDisable = inject(LegalDisableConfirmService);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);

  /**
   * Anchor the URL asked for, honoured once — and only once the catalogue has
   * rendered, since nothing on this page exists before the answer to
   * `GET /api/constraints` lands. Cleared as soon as it is used, so a later
   * reload (a solve finishing) does not scroll the reader away again.
   */
  private ancreDemandee = this.route.snapshot.fragment;

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

  /**
   * The score with the floors taken out, shown next to the raw one only when
   * they differ: a rule that penalises everything for lack of data costs a
   * constant no solve will move, and that constant is what makes
   * « -6 675 medium » unreadable. Empty when nothing is a floor.
   */
  protected readonly horsPlancherLabel = computed(() => {
    const view = this.view();
    if (!view?.scoreHorsPlancher || view.scoreHorsPlancher === view.scoreGlobal) {
      return '';
    }
    const score = view.scoreHorsPlancher;
    const count = view.contraintes.filter((constraint) => constraint.plancher !== null).length;
    return $localize`:@@constraints.summary.horsPlancher:Hors plancher : ${score}:score: — ${count}:count: règle(s) mesurent une donnée absente.`;
  });

  /** Sort key of the constraint cards: by score, the "what costs most" question. */
  protected readonly triParScore = signal(false);

  /** The other sort: floors first, by share of what they evaluated — "what will never move". */
  protected readonly sortByFloor = signal(false);

  protected basculerTri(): void {
    this.triParScore.update((actif) => !actif);
    if (this.triParScore()) {
      this.sortByFloor.set(false);
    }
  }

  protected basculerTriPlancher(): void {
    this.sortByFloor.update((actif) => !actif);
    if (this.sortByFloor()) {
      this.triParScore.set(false);
    }
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
    const byFloor = this.sortByFloor();
    return Array.from(groups.entries()).map(([categorie, items]) => ({
      categorie,
      ancre: categoryAnchor(categorie),
      items: byFloor
        ? [...items].sort(
            (a, b) =>
              (b.plancher?.ratio ?? -1) - (a.plancher?.ratio ?? -1) ||
              (b.matchCount ?? 0) - (a.matchCount ?? 0),
          )
        : parScore
          ? [...items].sort((a, b) => (b.matchCount ?? 0) - (a.matchCount ?? 0))
          : items,
      dosable: items.some((constraint) => constraint.dosable),
    }));
  });

  constructor() {
    void this.loadConstraints();
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
      this.apply(await this.constraintsApi.catalogue());
      this.honorerAncre();
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
      this.apply(await this.constraintsApi.diagnose());
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
    this.problemes.shareConstraints(view);
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
  protected async toggleConstraint(
    constraint: ConstraintView,
    event: MatSlideToggleChange,
  ): Promise<void> {
    const actif = event.checked;
    if (!actif && !(await this.legalDisable.allowsDisabling(constraint))) {
      event.source.checked = !actif;
      return;
    }
    this.setConstraintActif(constraint.name, actif);
    this.togglingConstraint.set(constraint.name);
    this.error.set('');
    try {
      await this.constraintsApi.setActive(constraint.name, actif);
    } catch (error) {
      this.setConstraintActif(constraint.name, !actif);
      event.source.checked = !actif;
      this.error.set(errorPrefix(error));
    } finally {
      this.togglingConstraint.set(null);
    }
  }

  protected actifLabel(actif: boolean): string {
    return actif
      ? $localize`:@@constraints.active:Active`
      : $localize`:@@constraints.disabled:Désactivée`;
  }

  /**
   * Reads what was typed, brings it back into the range the server accepts and
   * saves it. The field itself is rewritten with the value actually sent, so it
   * never keeps showing a number nobody stored — an empty field, a stray letter
   * or a 0 all fall back to the current weight or to the nearest bound.
   */
  protected async onPoidsChange(
    constraint: ConstraintView,
    field: HTMLInputElement,
  ): Promise<void> {
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
      const enregistre = await this.constraintsApi.setWeight(constraint.name, borne);
      this.patchConstraint(constraint.name, { poids: enregistre.poids });
    } catch (error) {
      this.patchConstraint(constraint.name, { poids: precedent });
      this.error.set(errorPrefix(error));
    }
  }

  private setConstraintActif(name: string, actif: boolean): void {
    this.patchConstraint(name, { actif });
  }

  /**
   * Every write of the view goes through `apply()`: the shared « legal rules
   * disabled » alert reads the store's copy, and a toggle that only rewrote
   * the page's own signal left the alert on the state before the click.
   */
  private patchConstraint(name: string, patch: Partial<ConstraintView>): void {
    const view = this.view();
    if (!view) {
      return;
    }
    this.apply({
      ...view,
      contraintes: view.contraintes.map((constraint) =>
        constraint.name === name ? { ...constraint, ...patch } : constraint,
      ),
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
        violations: constraint.violations,
      },
      width: '36rem',
    });
  }

  /**
   * The share of what the rule evaluated that it matched, at the rule's own
   * grain — seats for a per-seat rule, stand × créneau groups, pairs — which
   * is why the sentence says « éléments » rather than « postes ».
   */
  protected plancherRatioLabel(constraint: ConstraintView): string {
    const plancher = constraint.plancher;
    if (!plancher) {
      return '';
    }
    const pourcent = Math.round(plancher.ratio * 100);
    const count = constraint.postesEvalues ?? 0;
    return $localize`:@@constraints.plancher.ratio:${pourcent}:pct: % des ${count}:count: éléments évalués sont en écart.`;
  }

  /**
   * Goes to a rule or a category and leaves the caret there.
   *
   * Deliberately programmatic, for the two reasons the guide's summary already
   * documents: `index.html` declares `<base href="/">`, so the browser's own
   * jump to a bare fragment would resolve against the base URL, and what
   * scrolls in this shell is `mat-sidenav-content`, not the document, which is
   * what Angular's anchor scrolling would move. The `routerLink` next to it is
   * there for the URL alone — a rule's card is then a link one can paste.
   */
  protected scrollToAnchor(id: string): void {
    const carte = document.getElementById(id);
    // jsdom has no scrollIntoView, and neither does an old browser.
    carte?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    // `tabindex="-1"` makes the card focusable by script only: the focus
    // follows the eye without adding a tab stop to a page of fifty rules.
    carte?.focus?.({ preventScroll: true });
  }

  /** Honours `/constraints#uneRegle` on a direct load, once the cards exist. */
  private honorerAncre(): void {
    const ancre = this.ancreDemandee;
    if (!ancre) {
      return;
    }
    this.ancreDemandee = null;
    afterNextRender(() => this.scrollToAnchor(ancre), { injector: this.injector });
  }

  /** Label of the per-rule anchor link, read on its own by a screen reader. */
  protected ancreLabel(constraint: ConstraintView): string {
    const name = constraint.name;
    return $localize`:@@constraints.ancre.aria:Lien direct vers la règle ${name}:name:`;
  }

  /**
   * The link is the parameter's own label, so a reader clicking « Jours
   * travaillés d'affilée » lands on the field of that name. A screen reader
   * reads links out of context, where « Jours travaillés d'affilée » alone
   * says nothing about where it goes — hence the spelled-out name here.
   */
  protected parametreLabel(parametre: ParametreContrainte): string {
    const libelle = parametre.libelle;
    const valeur = parametre.valeur;
    return $localize`:@@constraints.parametres.aria:Régler « ${libelle}:libelle: », actuellement ${valeur}:valeur:`;
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
