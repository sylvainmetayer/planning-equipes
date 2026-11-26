import { Location, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  OnInit,
  effect,
  signal,
  untracked,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { protectionApplies } from '../../core/constraint-protection';
import { errorPrefix } from '../../core/error-message';
import { ModifiedFormsRegistry } from '../../core/formulaires-modifies';
import {
  IMPORTANCES,
  Importance,
  POIDS_DUR,
  POIDS_IMPORTANCE,
  POIDS_MAX,
  POIDS_MIN,
  clampWeight,
  importanceOf,
} from '../../core/importance';
import { intlLocale } from '../../core/locale';
import {
  ConstraintView,
  ConstraintsView,
  NiveauContrainte,
  NiveauRegle,
  ParametreContrainte,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { SolverJobService } from '../../core/solver-job.service';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { LegalText } from '../../shared/legal-text';
import { reglagesAvances } from '../../core/reglages-avances';
import { ReglagesAvancesToggle } from '../../shared/reglages-avances';
import { RelancerCalcul } from '../../shared/relancer-calcul';
import { StatusMessage } from '../../shared/status-message';
import { ViolationDetailsDialog } from '../../shared/violation-details-dialog';
import { AdHocConstraintsPage } from '../ad-hoc-constraints/ad-hoc-constraints-page';
import { ConsignesPage } from '../consignes/consignes-page';
import { VerrouillagesPage } from '../verrouillages/verrouillages-page';
import { LegalDisableConfirmService } from './legal-disable-dialog';
import {
  BLOCS_REGLES,
  BlocRegle,
  blocOfRule,
  OngletRegles,
  SEUILS,
  articlesOf,
  groupeLabel,
  isRuleTab,
  niveauEffectif,
  opensGroup,
  readOngletRegles,
  rulesOfBloc,
} from './regles';

/** Called lazily (never at module scope): the level of a rule in the words of the screen. */
function niveauLabel(niveau: NiveauContrainte): string {
  switch (niveau) {
    case 'HARD':
      return $localize`:@@regles.niveau.hard:Obligatoire : un plan qui l'enfreint n'est pas valable`;
    case 'MEDIUM':
      return $localize`:@@regles.niveau.medium:Qualité : pèse avant les préférences`;
    case 'SOFT':
      return $localize`:@@regles.niveau.soft:Préférence : départage deux plans valables`;
    default:
      return niveau;
  }
}

/** The levels an organisation rule can take, the most binding first. */
const NIVEAUX_REGLE: readonly NiveauRegle[] = [
  'OBLIGATOIRE',
  'PREFERENCE_FORTE',
  'PREFERENCE_NORMALE',
  'PREFERENCE_LEGERE',
];

/** Called lazily: a level in the words of the screen. */
function niveauRegleLabel(niveau: NiveauRegle): string {
  switch (niveau) {
    case 'OBLIGATOIRE':
      return $localize`:@@regles.niveauRegle.obligatoire:Obligatoire`;
    case 'PREFERENCE_FORTE':
      return $localize`:@@regles.niveauRegle.forte:Forte`;
    case 'PREFERENCE_NORMALE':
      return $localize`:@@regles.niveauRegle.normale:Normale`;
    case 'PREFERENCE_LEGERE':
      return $localize`:@@regles.niveauRegle.legere:Légère`;
  }
}

/** Called lazily: the three positions in the words of the screen. */
function importanceLabel(importance: Importance): string {
  switch (importance) {
    case 'FAIBLE':
      return $localize`:@@regles.importance.faible:Faible`;
    case 'NORMALE':
      return $localize`:@@regles.importance.normale:Normale`;
    case 'FORTE':
      return $localize`:@@regles.importance.forte:Forte`;
  }
}

/**
 * « Règles du planning » (issue #720): everything the next solve must
 * respect, on one screen, in tabs carried by `?onglet=` — first the three of
 * the former « Consignes au solveur » (issue #719): the adjustments (the
 * default), the locks and the consignes, each emitting `changed` after a
 * write, upon which « Relancer le calcul » is offered in place; then
 * « Règles », the rules themselves in three blocks:
 *
 * - « Obligations légales » — read only: what the law imposes, its article, the
 *   thresholds it reads as stored, and a link to Paramètres › Légal, where they
 *   are set. A threshold is edited in one place only.
 * - « Politique de l'organisation » — the rules whose level the edition
 *   chooses: obligatoire, or a preference (forte, normale, légère). The exact
 *   weight opens in the panel and is offered only to a preference: a mandatory
 *   rule counts, it is not weighed.
 * - « Réglages avancés » (folded, `?avance=1`) — the rest: assignment, adjustments,
 *   locks, preferences. Which block holds a rule comes from the catalogue
 *   (`legale`, `niveauReglable`), never from its name.
 *
 * The detail — the long text, the history of its settings — opens in a panel
 * beside the blocks, which `?regle=<name>` opens directly: the stable address
 * the Diagnostic and the reading of the score link to. Nothing is written as it
 * is typed: the switches, levels and weights share one set of pending changes
 * and one « Enregistrer ». A navigation away with changes pending asks first,
 * and the Solveur asks too before « Calculer ».
 */
@Component({
  selector: 'app-regles-page',
  imports: [
    AdHocConstraintsPage,
    ConsignesPage,
    FeasibilityBanner,
    LegalText,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule,
    NgTemplateOutlet,
    ReglagesAvancesToggle,
    RelancerCalcul,
    RouterLink,
    StatusMessage,
    VerrouillagesPage,
  ],
  templateUrl: './regles-page.html',
  styleUrls: ['./regles-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglesPage implements OnInit {
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly dialog = inject(MatDialog);
  private readonly legalDisable = inject(LegalDisableConfirmService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly problemes = inject(ProblemesStore);
  protected readonly jobs = inject(SolverJobService);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly view = signal<ConstraintsView | null>(null);

  /* ------------------------------ Tab and rule ------------------------------ */

  /** `null` until the URL or a click chose one: the page then follows the rule it was asked. */
  private readonly ongletChoisi = signal<OngletRegles | null>(null);
  /** The rule whose panel is open — `?regle=`. */
  protected readonly regle = signal<string | null>(null);

  protected readonly selectedRule = computed(() => {
    const name = this.regle();
    return name ? (this.view()?.contraintes.find((rule) => rule.name === name) ?? null) : null;
  });

  /**
   * The tab on screen: the one chosen, else « Règles » when the address
   * named a rule, else the adjustments.
   */
  protected readonly onglet = computed<OngletRegles>(
    () => this.ongletChoisi() ?? (this.selectedRule() ? 'regles' : 'ajustements'),
  );

  private readonly selectedBloc = computed(() => {
    const rule = this.selectedRule();
    return rule ? blocOfRule(rule) : null;
  });

  protected readonly isRuleTab = isRuleTab;

  /** The rules nobody tunes every day — assignment, adjustments, locks, preferences — are folded. */
  protected readonly avance = reglagesAvances();

  /**
   * How « Relancer le calcul » runs once an adjustment, a lock or a consigne
   * was written on its tab, `null` before: a correction while only consignes
   * were — a band shut or reopened removes or adds seats, which is what a
   * correction re-fills —, the full solve as soon as anything else was, since
   * an incremental re-solve pins every seat still valid and would re-open
   * nothing after a forced assignment, an incompatibility or a lock lifted.
   */
  protected readonly relance = signal<'correction' | 'calcul' | null>(null);

  protected onConsigneChanged(onglet: OngletRegles): void {
    this.relance.update((mode) =>
      onglet === 'consignes' && mode !== 'calcul' ? 'correction' : 'calcul',
    );
  }

  /** The rows of each block, in reading order. */
  protected readonly blocs = computed(() => {
    const contraintes = this.view()?.contraintes ?? [];
    return BLOCS_REGLES.map((bloc) => ({ bloc, rows: rulesOfBloc(contraintes, bloc) }));
  });
  protected readonly legalRows = computed(() => this.rowsOf('legal'));
  protected readonly organisationRows = computed(() => this.rowsOf('organisation'));
  protected readonly avanceRows = computed(() => this.rowsOf('avance'));

  private rowsOf(bloc: BlocRegle): ConstraintView[] {
    return this.blocs().find((each) => each.bloc === bloc)?.rows ?? [];
  }

  /* ---------------------------- Pending changes ----------------------------- */

  private readonly actifsDraft = signal<Readonly<Record<string, boolean>>>({});
  private readonly poidsDraft = signal<Readonly<Record<string, number>>>({});
  private readonly niveauxDraft = signal<Readonly<Record<string, NiveauRegle>>>({});

  private readonly actifsChanges = computed(() =>
    (this.view()?.contraintes ?? []).filter(
      (rule) => rule.name in this.actifsDraft() && this.actifsDraft()[rule.name] !== rule.actif,
    ),
  );
  private readonly poidsChanges = computed(() =>
    (this.view()?.contraintes ?? []).filter(
      (rule) => rule.name in this.poidsDraft() && this.poidsDraft()[rule.name] !== rule.poids,
    ),
  );
  private readonly niveauxChanges = computed(() =>
    (this.view()?.contraintes ?? []).filter((rule) => rule.name in this.niveauxDraft()),
  );

  /** How many things « Enregistrer » would write: rules switched, re-levelled and reweighted. */
  protected readonly pendingCount = computed(
    () => this.actifsChanges().length + this.poidsChanges().length + this.niveauxChanges().length,
  );
  protected readonly dirty = computed(() => this.pendingCount() > 0);

  /* ------------------------------ Read-outs --------------------------------- */

  protected readonly feasibility = computed(() => this.view()?.faisabilite ?? null);
  protected readonly hardScore = computed(() => this.view()?.hardScore ?? null);
  protected readonly hardIssues = computed(
    () =>
      this.view()
        ?.contraintes.filter(
          (rule) => niveauEffectif(rule) === 'HARD' && (rule.matchCount ?? 0) > 0,
        )
        .map((rule) => ({ name: rule.name, matchCount: rule.matchCount ?? 0 })) ?? [],
  );
  /** Shared with the Solveur screen: see `ProblemesStore.alerteReglesLegales`. */
  protected readonly alerteReglesLegales = computed(() => this.problemes.alerteReglesLegales());

  protected readonly analysedAt = computed(() => {
    const at = this.view()?.analysedAt;
    return at ? new Date(at).toLocaleString(intlLocale()) : '';
  });

  protected readonly niveaux = NIVEAUX_REGLE.map((niveau) => ({
    value: niveau,
    label: niveauRegleLabel(niveau),
  }));
  protected readonly importances = IMPORTANCES.map((importance) => ({
    value: importance,
    label: importanceLabel(importance),
    poids: POIDS_IMPORTANCE[importance],
  }));
  /** Why « Relancer le calcul » waits while something is typed. */
  protected readonly saveFirst = $localize`:@@regles.enregistrerDAbord:Enregistrez d'abord : le calcul lit les règles enregistrées.`;
  protected readonly poidsMin = POIDS_MIN;
  protected readonly poidsDur = POIDS_DUR;
  protected readonly poidsMax = POIDS_MAX;
  protected readonly protectionApplies = protectionApplies;

  constructor() {
    // Followed rather than read once: a link to another rule of this page — the
    // Diagnostic's « Voir la règle » while this page is open — reuses the component.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.ongletChoisi.set(readOngletRegles(params.get('onglet')));
      this.regle.set(params.get('regle'));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'ajustements' ? null : this.onglet(),
      regle: optionalParam(this.regle()),
    }));
    // `?regle=` naming a rule of the folded block opens the fold, once: a rule asked for is never hidden.
    // Tracked through the block's name, not the rule object: a reload hands out new objects, and must not
    // reopen a fold the reader just closed.
    effect(() => {
      if (this.selectedBloc() === 'avance' && !untracked(() => this.avance.ouvert())) {
        untracked(() => this.avance.basculer());
      }
    });
    // A solve that lands writes a fresh analysis: its breaches are the column
    // this screen reads. Unregistered with the page, rebuilt on every visit.
    const destroyRef = inject(DestroyRef);
    for (const type of ['SOLVE', 'SOLVE_INCREMENTAL'] as const) {
      destroyRef.onDestroy(this.jobs.onResult(type, () => void this.reloadCatalogue()));
    }
    destroyRef.onDestroy(
      inject(ModifiedFormsRegistry).register(
        'regles-du-planning',
        $localize`:@@regles.saisie.label:les règles du planning`,
        this.dirty,
      ),
    );
  }

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.apply(await this.constraintsApi.catalogue());
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async reloadCatalogue(): Promise<void> {
    try {
      this.apply(await this.constraintsApi.catalogue());
    } catch (error) {
      this.error.set(errorPrefix(error));
    }
  }

  /**
   * « Actualiser »: re-derives the analysis from the plan currently persisted.
   * Nothing is solved, so it stays available while a solve runs.
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

  /** Every write of the view goes through here: the Solveur's « rule switched off » alert reads the store's copy. */
  private apply(view: ConstraintsView): void {
    this.view.set(view);
    this.problemes.shareConstraints(view);
  }

  protected changerOnglet(onglet: OngletRegles): void {
    // A hosted tab's own keys (`?personne=`, `?date=`, `?nouvelle=`, `?ids=`) mean
    // nothing to the next one: the address is reset before it reads it, so it opens on its defaults.
    if (onglet !== this.onglet() && (!isRuleTab(onglet) || !isRuleTab(this.onglet()))) {
      const [chemin] = this.location.path().split('?');
      this.location.replaceState(chemin || '/');
    }
    this.ongletChoisi.set(onglet);
    if (!isRuleTab(onglet)) {
      this.regle.set(null);
    }
  }

  protected select(rule: ConstraintView): void {
    this.regle.set(this.regle() === rule.name ? null : rule.name);
  }

  protected fermer(): void {
    this.regle.set(null);
  }

  /* ------------------------------ A rule's row ------------------------------ */

  protected isActif(rule: ConstraintView): boolean {
    const draft = this.actifsDraft();
    return rule.name in draft ? draft[rule.name] : rule.actif;
  }

  protected poidsOf(rule: ConstraintView): number {
    const draft = this.poidsDraft();
    return rule.name in draft ? draft[rule.name] : rule.poids;
  }

  protected importanceOf(rule: ConstraintView): Importance | null {
    return importanceOf(this.poidsOf(rule));
  }

  protected articles(rule: ConstraintView): string[] {
    return articlesOf(rule.description);
  }

  protected readonly niveauLabel = niveauLabel;
  protected readonly niveauEffectifOf = niveauEffectif;
  protected readonly opensGroup = opensGroup;
  protected readonly groupeLabel = groupeLabel;

  protected libelle(rule: ConstraintView): string {
    return rule.libelleCourt || rule.categorie;
  }

  /** « 3 écarts », « tenue », « pas encore évaluée » — the last analysis, in words. */
  protected ecartsLabel(rule: ConstraintView): string {
    if (rule.score === null) {
      return $localize`:@@regles.ecarts.nonEvaluee:pas encore évaluée`;
    }
    const count = rule.matchCount ?? 0;
    return count > 0
      ? $localize`:@@regles.ecarts.count:${count}:count: écart(s)`
      : $localize`:@@regles.ecarts.aucun:tenue`;
  }

  /**
   * Switches a rule in the pending changes. Switching off a rule that founds
   * the plan in law asks first, as it did when the switch wrote at once: the
   * solver would then return a plan scoring zero hard that still breaks the
   * Code du travail. The switch is given back its state by hand on a refusal —
   * it flipped itself on click, and `[checked]` still says what it said.
   */
  protected async toggle(rule: ConstraintView, event: MatSlideToggleChange): Promise<void> {
    const actif = event.checked;
    if (!actif && !(await this.legalDisable.allowsDisabling(rule))) {
      event.source.checked = true;
      return;
    }
    this.actifsDraft.update((draft) => withEntry(draft, rule.name, actif, rule.actif));
  }

  /** Switches a legal rule back on: the legal block has no switch, but a rule left off must not stay stuck. */
  protected reactivate(rule: ConstraintView): void {
    this.actifsDraft.update((draft) => withEntry(draft, rule.name, true, rule.actif));
  }

  protected reactivateLabel(rule: ConstraintView): string {
    const regle = this.libelle(rule);
    return $localize`:@@regles.reactiver.aria:Réactiver — ${regle}:regle:`;
  }

  /** The level an organisation rule shows: the one pending, else the one the edition stored. */
  protected niveauOf(rule: ConstraintView): NiveauRegle | null {
    const draft = this.niveauxDraft();
    return rule.name in draft ? draft[rule.name] : (rule.niveauChoisi ?? null);
  }

  /** Whether the rule counts as a hard one as it shows — a mandatory rule is counted, never weighed. */
  protected obligatoire(rule: ConstraintView): boolean {
    return rule.niveauReglable
      ? this.niveauOf(rule) === 'OBLIGATOIRE'
      : niveauEffectif(rule) === 'HARD';
  }

  /**
   * Chooses the level of an organisation rule, on « Enregistrer » like the rest.
   * A level writes the weight of its position, so a weight typed meanwhile is
   * dropped; choosing the stored level again is no change, unless the rule
   * carries a weight of its own that the position would bring back to its figure.
   */
  protected setNiveau(rule: ConstraintView, niveau: NiveauRegle): void {
    const same =
      niveau === (rule.niveauChoisi ?? null) &&
      (niveau === 'OBLIGATOIRE' || importanceOf(rule.poids) !== null);
    this.niveauxDraft.update((draft) => withEntry(draft, rule.name, niveau, same ? niveau : null));
    this.poidsDraft.update((draft) => withoutEntry(draft, rule.name));
  }

  protected setImportance(rule: ConstraintView, importance: Importance): void {
    this.setPoids(rule, POIDS_IMPORTANCE[importance]);
  }

  /** The exact weight of the panel: brought into the range the server accepts, the field rewritten with it. */
  protected onPoidsTyped(rule: ConstraintView, field: HTMLInputElement): void {
    const poids = clampWeight(Number(field.value), this.poidsOf(rule));
    field.value = String(poids);
    this.setPoids(rule, poids);
  }

  /**
   * A hard rule carrying a weight of its own: stored before a hard breach
   * stopped being dosed (ADR 0057), and otherwise invisible on this screen.
   */
  protected hardWeightCustomised(rule: ConstraintView): boolean {
    // A rule shipped medium and made mandatory keeps its preference weight for the way back: it does not apply.
    return (
      rule.niveau === 'HARD' && niveauEffectif(rule) === 'HARD' && this.poidsOf(rule) !== POIDS_DUR
    );
  }

  /** Brings a hard rule back to the weight every hard rule carries, on « Enregistrer » like the rest. */
  protected resetHardWeight(rule: ConstraintView): void {
    this.setPoids(rule, POIDS_DUR);
  }

  private setPoids(rule: ConstraintView, poids: number): void {
    this.poidsDraft.update((draft) => withEntry(draft, rule.name, poids, rule.poids));
  }

  /* ---------------------- The settings a rule reads ------------------------- */

  /**
   * Where a setting is edited, in Paramètres: the legal ceilings in « Légal », the
   * comfort thresholds in « Organisation ». `null` for a key this screen does not
   * know — its value is shown, with nowhere to go.
   */
  protected sectionOf(parametre: ParametreContrainte): 'legal' | 'organisation' | null {
    const descriptor = SEUILS[parametre.cle ?? ''];
    if (!descriptor) {
      return null;
    }
    return descriptor.source === 'legaux' ? 'legal' : 'organisation';
  }

  /**
   * The section of Paramètres a row links to: « Légal » for every legal rule —
   * the whole block points there, a rule with no figure of its own included —,
   * else the one that holds a setting the rule reads, `null` when it reads none.
   */
  protected parametresLien(rule: ConstraintView, bloc: BlocRegle): 'legal' | 'organisation' | null {
    if (bloc === 'legal') {
      return 'legal';
    }
    for (const parametre of rule.parametres ?? []) {
      const section = this.sectionOf(parametre);
      if (section) {
        return section;
      }
    }
    return null;
  }

  protected lienParametresLabel(rule: ConstraintView): string {
    const regle = this.libelle(rule);
    return $localize`:@@regles.lienParametres.aria:Régler dans Paramètres — ${regle}:regle:`;
  }

  /* ---------------------------------- Save ---------------------------------- */

  protected cancel(): void {
    this.actifsDraft.set({});
    this.poidsDraft.set({});
    this.niveauxDraft.set({});
    this.error.set('');
  }

  /**
   * Writes the pending changes, one after the other, each cleared once
   * stored — a refusal on the third leaves the first two saved and says why.
   * The levels go first: a level writes the weight of its position.
   */
  protected async save(): Promise<void> {
    if (!this.dirty() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      for (const rule of this.niveauxChanges()) {
        await this.constraintsApi.setNiveau(rule.name, this.niveauxDraft()[rule.name]);
        this.niveauxDraft.update((draft) => withoutEntry(draft, rule.name));
      }
      for (const rule of this.actifsChanges()) {
        await this.constraintsApi.setActive(rule.name, this.actifsDraft()[rule.name]);
        this.actifsDraft.update((draft) => withoutEntry(draft, rule.name));
      }
      for (const rule of this.poidsChanges()) {
        await this.constraintsApi.setWeight(rule.name, this.poidsDraft()[rule.name]);
        this.poidsDraft.update((draft) => withoutEntry(draft, rule.name));
      }
      this.notifications.notify({
        title: $localize`:@@regles.saved:Règles enregistrées : le prochain calcul en tiendra compte.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      // The values a rule shows beside its row, and the switches, as stored now.
      await this.reloadCatalogue();
      this.saving.set(false);
    }
  }

  /** Asked by the router before leaving: pending changes are dropped only on purpose. */
  canLeave(): Promise<boolean> {
    if (!this.dirty()) {
      return Promise.resolve(true);
    }
    return this.confirm.ask({
      title: $localize`:@@regles.quitter.titre:Abandonner les modifications ?`,
      message: $localize`:@@regles.quitter.message:Des règles ont été modifiées sans être enregistrées : le prochain calcul ne les verrait pas.`,
      confirmLabel: $localize`:@@regles.quitter.label:Abandonner`,
      danger: true,
    });
  }

  /* --------------------------------- Detail --------------------------------- */

  /** Who, what, when — only ever offered for a hard rule with breaches, whose lines the server lists. */
  protected showViolations(rule: ConstraintView): void {
    this.dialog.open(ViolationDetailsDialog, {
      data: {
        constraintName: this.libelle(rule),
        description: rule.description,
        matchCount: rule.matchCount ?? rule.violations.length,
        violations: rule.violations,
      },
      width: '36rem',
    });
  }

  /** The share of what the rule evaluated that it matched: « mesure une donnée absente » in figures. */
  protected plancherLabel(rule: ConstraintView): string {
    const plancher = rule.plancher;
    if (!plancher) {
      return '';
    }
    const pourcent = Math.round(plancher.ratio * 100);
    const count = rule.postesEvalues ?? 0;
    return $localize`:@@constraints.plancher.ratio:${pourcent}:pct: % des ${count}:count: éléments évalués sont en écart.`;
  }
}

/** `draft` with `name` set to `value` — or dropped, when `value` is what is stored. */
function withEntry<T>(
  draft: Readonly<Record<string, T>>,
  name: string,
  value: T,
  stored: T | null,
): Readonly<Record<string, T>> {
  return value === stored ? withoutEntry(draft, name) : { ...draft, [name]: value };
}

function withoutEntry<T>(
  draft: Readonly<Record<string, T>>,
  name: string,
): Readonly<Record<string, T>> {
  const next = { ...draft };
  delete next[name];
  return next;
}
