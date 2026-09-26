import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
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
  ParametreContrainte,
  ParametresLegaux,
  ParametresQualite,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { LegalText } from '../../shared/legal-text';
import { RelancerCalcul } from '../../shared/relancer-calcul';
import { StatusMessage } from '../../shared/status-message';
import { ViolationDetailsDialog } from '../../shared/violation-details-dialog';
import { EcartsPivotCard } from './ecarts-pivot-card';
import { LegalDisableConfirmService } from './legal-disable-dialog';
import {
  OngletRegles,
  SEUILS,
  SettingsRecord,
  articlesOf,
  hasChanges,
  mergeSettings,
  ongletOfRule,
  readOngletRegles,
  rulesOfTab,
  shownValue,
  storedValue,
} from './regles';
import { ReglesCalcul } from './regles-calcul';
import { WeightHistoryView } from './weight-history-view';

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
 * « Règles du planning » (issue #720): every setting that decides the plan,
 * on one screen, in three tabs carried by `?onglet=` —
 *
 * - « Légal » (default) — one row per hard rule: its short label, the article
 *   that founds it, on/off, the thresholds it reads edited on the row itself
 *   (weekly caps, the break, the meal window…), the breaches of the last
 *   analysis. The detail — the long text, the history of its settings — opens
 *   in a panel beside the table, which `?regle=<name>` opens directly: the
 *   stable address the Diagnostic and the reading of the score link to.
 * - « Qualité » — the same table for the medium and soft rules, with their
 *   importance in three positions (faible / normale / forte, weights 1 / 5 /
 *   25) and the exact weight in the panel for whoever wants it.
 * - « Calcul » — the solve budget, the start of the evening, the ninja
 *   typologie: `ReglesCalcul`.
 *
 * Nothing is written as it is typed: « Légal » and « Qualité » share one set
 * of pending changes — a threshold like the meal window is read by a rule of
 * each — and each tab carries its « Enregistrer ». A navigation away with
 * changes pending asks first, and the Solveur asks too before « Calculer ».
 */
@Component({
  selector: 'app-regles-page',
  imports: [
    EcartsPivotCard,
    FeasibilityBanner,
    LegalText,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatSlideToggleModule,
    MatTooltipModule,
    RelancerCalcul,
    ReglesCalcul,
    RouterLink,
    StatusMessage,
    WeightHistoryView,
  ],
  templateUrl: './regles-page.html',
  styleUrls: ['../../../styles/heatmap.css', './regles-page.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReglesPage {
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly dialog = inject(MatDialog);
  private readonly legalDisable = inject(LegalDisableConfirmService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly route = inject(ActivatedRoute);
  private readonly problemes = inject(ProblemesStore);
  protected readonly jobs = inject(SolverJobService);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly view = signal<ConstraintsView | null>(null);
  private readonly legaux = signal<ParametresLegaux | null>(null);
  private readonly qualite = signal<ParametresQualite | null>(null);

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
   * The tab on screen: the one chosen, else the tab of the rule the address
   * named — `/regles?regle=<a quality rule>` opens on « Qualité » —, else
   * « Légal ». A rule open on a tab that does not list it moves the tab.
   */
  protected readonly onglet = computed<OngletRegles>(() => {
    const choisi = this.ongletChoisi();
    const rule = this.selectedRule();
    if (rule && choisi !== 'calcul' && choisi !== ongletOfRule(rule)) {
      return ongletOfRule(rule);
    }
    return choisi ?? 'legal';
  });

  /** The rows of the tab on screen. */
  protected readonly rows = computed(() => {
    const onglet = this.onglet();
    return onglet === 'calcul' ? [] : rulesOfTab(this.view()?.contraintes ?? [], onglet);
  });

  /* ---------------------------- Pending changes ----------------------------- */

  private readonly actifsDraft = signal<Readonly<Record<string, boolean>>>({});
  private readonly poidsDraft = signal<Readonly<Record<string, number>>>({});
  private readonly legauxDraft = signal<SettingsRecord>({});
  private readonly qualiteDraft = signal<SettingsRecord>({});

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
  private readonly legauxDirty = computed(() =>
    hasChanges(this.legaux() as SettingsRecord | null, this.legauxDraft()),
  );
  private readonly qualiteDirty = computed(() =>
    hasChanges(this.qualite() as SettingsRecord | null, this.qualiteDraft()),
  );

  /** How many things « Enregistrer » would write: rules switched, reweighted, and the records touched. */
  protected readonly pendingCount = computed(
    () =>
      this.actifsChanges().length +
      this.poidsChanges().length +
      (this.legauxDirty() ? 1 : 0) +
      (this.qualiteDirty() ? 1 : 0),
  );
  protected readonly dirty = computed(() => this.pendingCount() > 0);

  private readonly calcul = viewChild(ReglesCalcul);

  /* ------------------------------ Read-outs --------------------------------- */

  protected readonly feasibility = computed(() => this.view()?.faisabilite ?? null);
  protected readonly hardScore = computed(() => this.view()?.hardScore ?? null);
  protected readonly hardIssues = computed(
    () =>
      this.view()
        ?.contraintes.filter((rule) => rule.niveau === 'HARD' && (rule.matchCount ?? 0) > 0)
        .map((rule) => ({ name: rule.name, matchCount: rule.matchCount ?? 0 })) ?? [],
  );
  /** Shared with the Solveur screen: see `ProblemesStore.alerteReglesLegales`. */
  protected readonly alerteReglesLegales = computed(() => this.problemes.alerteReglesLegales());

  protected readonly analysedAt = computed(() => {
    const at = this.view()?.analysedAt;
    return at ? new Date(at).toLocaleString(intlLocale()) : '';
  });

  protected readonly importances = IMPORTANCES.map((importance) => ({
    value: importance,
    label: importanceLabel(importance),
    poids: POIDS_IMPORTANCE[importance],
  }));
  /** Why « Relancer le calcul » waits while something is typed. */
  protected readonly saveFirst = $localize`:@@regles.enregistrerDAbord:Enregistrez d'abord : le calcul lit les règles enregistrées.`;
  protected readonly poidsMin = POIDS_MIN;
  protected readonly poidsMax = POIDS_MAX;
  protected readonly protectionApplies = protectionApplies;

  constructor() {
    // Followed rather than read once: a link to another rule of this page — the
    // pivot's « Régler cette contrainte » — reuses the component.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.ongletChoisi.set(readOngletRegles(params.get('onglet')));
      this.regle.set(params.get('regle'));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'legal' ? null : this.onglet(),
      regle: optionalParam(this.regle()),
    }));
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
        computed(() => this.dirty() || (this.calcul()?.dirty() ?? false)),
      ),
    );
    void this.load();
    // The pivot names stands and animateurs, « Calcul » lists the typologies.
    void inject(ReferenceDataStore)
      .reload()
      .catch(() => undefined);
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [view, legaux, qualite] = await Promise.all([
        this.constraintsApi.catalogue(),
        this.constraintsApi.legalParameters(),
        this.constraintsApi.qualityParameters(),
      ]);
      this.apply(view);
      this.legaux.set(legaux);
      this.qualite.set(qualite);
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
    this.ongletChoisi.set(onglet);
    const rule = this.selectedRule();
    if (rule && onglet !== ongletOfRule(rule)) {
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

  protected niveauLabel(niveau: NiveauContrainte): string {
    return niveauLabel(niveau);
  }

  protected libelle(rule: ConstraintView): string {
    return rule.libelleCourt || rule.categorie;
  }

  /** « 3 écarts », « tenue », « pas encore évaluée » — the last analysis, in words. */
  protected ecartsLabel(rule: ConstraintView): string {
    if (rule.score === null || rule.score === undefined) {
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

  protected setImportance(rule: ConstraintView, importance: Importance): void {
    this.setPoids(rule, POIDS_IMPORTANCE[importance]);
  }

  /** The exact weight of the panel: brought into the range the server accepts, the field rewritten with it. */
  protected onPoidsTyped(rule: ConstraintView, field: HTMLInputElement): void {
    const poids = clampWeight(Number(field.value), this.poidsOf(rule));
    field.value = String(poids);
    this.setPoids(rule, poids);
  }

  private setPoids(rule: ConstraintView, poids: number): void {
    this.poidsDraft.update((draft) => withEntry(draft, rule.name, poids, rule.poids));
  }

  /* ---------------------- The settings a rule reads ------------------------- */

  /** Whether the row can edit this setting; an unknown key is shown read-only, never dropped. */
  protected editable(parametre: ParametreContrainte): boolean {
    return parametre.cle !== undefined && parametre.cle in SEUILS;
  }

  protected seuil(parametre: ParametreContrainte) {
    return SEUILS[parametre.cle ?? ''];
  }

  protected valeurSeuil(parametre: ParametreContrainte, field: string): string | number | null {
    const descriptor = SEUILS[parametre.cle ?? ''];
    return descriptor.source === 'legaux'
      ? shownValue(descriptor, field, this.legaux() as SettingsRecord | null, this.legauxDraft())
      : shownValue(descriptor, field, this.qualite() as SettingsRecord | null, this.qualiteDraft());
  }

  protected onSeuilTyped(parametre: ParametreContrainte, field: string, typed: string): void {
    const descriptor = SEUILS[parametre.cle ?? ''];
    const draft = descriptor.source === 'legaux' ? this.legauxDraft : this.qualiteDraft;
    draft.update((current) => ({ ...current, [field]: storedValue(descriptor, typed) }));
  }

  /** A field read out of context by a screen reader: the setting, and for a window which end. */
  protected seuilLabel(
    parametre: ParametreContrainte,
    rule: ConstraintView,
    index: number,
  ): string {
    const descriptor = SEUILS[parametre.cle ?? ''];
    const regle = this.libelle(rule);
    const libelle = parametre.libelle;
    if (descriptor.kind === 'plage') {
      return index === 0
        ? $localize`:@@regles.seuil.debut:${libelle}:libelle:, début — ${regle}:regle:`
        : $localize`:@@regles.seuil.fin:${libelle}:libelle:, fin — ${regle}:regle:`;
    }
    return $localize`:@@regles.seuil.aria:${libelle}:libelle: — ${regle}:regle:`;
  }

  protected uniteSeuil(parametre: ParametreContrainte): string {
    switch (SEUILS[parametre.cle ?? '']?.kind) {
      case 'heures':
        return $localize`:@@regles.unite.heures:h`;
      case 'minutes':
        return $localize`:@@regles.unite.minutes:min`;
      default:
        return '';
    }
  }

  /* ------------------------------ Grid check -------------------------------- */

  /**
   * The two legal settings no rule reads: the grid check of the timeslots
   * warns with them (a daily rest the grid leaves no room for, a shift that
   * needs a relay). They were on the legal card, and stay on the legal tab.
   */
  protected valeurGrille(field: 'reposQuotidienMinimalMinutes' | 'dureeVacationMaxMinutes') {
    const descriptor = { source: 'legaux' as const, kind: 'heures' as const, fields: [field] };
    return shownValue(
      descriptor,
      field,
      this.legaux() as SettingsRecord | null,
      this.legauxDraft(),
    );
  }

  protected onGrilleTyped(
    field: 'reposQuotidienMinimalMinutes' | 'dureeVacationMaxMinutes',
    typed: string,
  ): void {
    const descriptor = { source: 'legaux' as const, kind: 'heures' as const, fields: [field] };
    this.legauxDraft.update((current) => ({ ...current, [field]: storedValue(descriptor, typed) }));
  }

  /* ---------------------------------- Save ---------------------------------- */

  protected cancel(): void {
    this.actifsDraft.set({});
    this.poidsDraft.set({});
    this.legauxDraft.set({});
    this.qualiteDraft.set({});
    this.error.set('');
  }

  /**
   * Writes the pending changes, one record after the other, each cleared once
   * stored — a refusal on the third leaves the first two saved and says why.
   * The two parameter records are re-read just before being written: the
   * server replaces a whole record, and « Calcul » writes a field of the legal
   * one too.
   */
  protected async save(): Promise<void> {
    if (!this.dirty() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set('');
    try {
      if (this.legauxDirty()) {
        const stored = await this.constraintsApi.legalParameters();
        this.legaux.set(
          await this.constraintsApi.saveLegalParameters(mergeSettings(stored, this.legauxDraft())),
        );
        this.legauxDraft.set({});
      }
      if (this.qualiteDirty()) {
        const stored = await this.constraintsApi.qualityParameters();
        this.qualite.set(
          await this.constraintsApi.saveQualityParameters(
            mergeSettings(stored, this.qualiteDraft()),
          ),
        );
        this.qualiteDraft.set({});
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
  async canLeave(): Promise<boolean> {
    if (!this.dirty() && !(this.calcul()?.dirty() ?? false)) {
      return true;
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
  stored: T,
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
