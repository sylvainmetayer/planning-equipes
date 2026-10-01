import { DatePipe, formatDate } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  LOCALE_ID,
  OnInit,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import {
  countSince,
  defaultVisitStorage,
  readLastVisit,
  writeLastVisit,
} from '../../core/derniere-visite';
import { resumePublication } from '../../core/publication';
import { EchangesApi, ListMeasure, StatisticsPeriod } from '../../core/api/echanges-api';
import {
  decisionNonCommuniquee,
  statutDemandeClasse,
  statutDemandeLabel,
} from '../../core/demande-echange-labels';
import { DemandeEchangeView, EchangeSimulation, HardMediumSoftScore } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { formatDeltaScore } from '../../core/score-format';
import { ConfirmService } from '../../shared/confirm-dialog';
import { GuichetEtat } from '../../shared/guichet-etat';
import { PromptDialog } from '../../shared/prompt-dialog';
import { errorMessage } from '../../core/error-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { FilterChip, FilterChips } from '../../shared/filter-chips';
import { EchangeStatisticsTab } from './echanges-statistiques';
import {
  EchangesTab,
  ListFilter,
  NO_LIST_FILTER,
  REMOVED_TIMESLOT,
  TO_ARBITRATE,
  isListFiltered,
  listFilterParams,
  matchesListFilter,
  oldestWaitingFirst,
  readDay,
  readEchangesTab,
  readListFilter,
  readMeasure,
  readToArbitrate,
} from './echanges-filter';
import { readStatisticsPeriod, statisticsPeriodParams } from './statistics-format';
import { HhmmPipe } from '../../shared/hhmm-pipe';

interface DemandeRow extends DemandeEchangeView {
  statutLabel: string;
  statutClasse: string;
  /**
   * Decided, and the publication that announces it has not left (issue #531):
   * what is left to publish. Said of a refusal as well as of an acceptation —
   * on this screen it is a list of pending work, not a warning about a
   * planning somebody is reading.
   */
  nonCommuniquee: boolean;
}

/**
 * Admin review of the demandes d'échange (issue #165), opening on the queue.
 * For each pending demande: its fresh impact against the current persisted
 * planning (score delta, hard constraints newly broken, croisé or simple
 * takeover), then two explicit decisions — accept (applies the swap exactly as
 * simulated and pins both animateurs on the créneau) or refuse (with a comment
 * sent back to the animateur).
 *
 * <p>An accepted swap offers its two follow-ups on the spot, on its card:
 * « Prévenir les 2 personnes » — a publication aimed at those two — and
 * « Corriger le reste », the incremental solve, with the day it touches one
 * click away. The count of requests arrived since this browser's last visit
 * sits in the header.</p>
 *
 * <p>The foire itself — its switch and its dates — is configured on
 * Paramètres › Édition; this screen keeps one line of its state (issue #720).</p>
 *
 * <p>Two tabs, `?onglet=stats` for the second: the requests, and the
 * statistics of the foire (`echanges-statistiques`). Every figure of the
 * latter links back to the first, narrowed by the URL — the creation period
 * (`du`, `au`) and the measure (`mesure`), both applied by the server, and the
 * criteria of `ListFilter` — and the narrowing shows as chips, each removable.
 * The page is the one writer of the URL: the tab, the period of the
 * statistics, the list's filters. The list is read only once its tab shows:
 * opened on the statistics, the screen loads no name.</p>
 */
@Component({
  selector: 'app-echanges-page',
  imports: [
    HhmmPipe,
    DatePipe,
    EchangeStatisticsTab,
    FilterChips,
    GuichetEtat,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './echanges-page.html',
  styleUrl: '../../../styles/demandes.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EchangesPage implements OnInit {
  private readonly echangesApi = inject(EchangesApi);
  private readonly planningApi = inject(PlanningApi);
  private readonly jobs = inject(SolverJobService);
  private readonly visitStorage = defaultVisitStorage();
  /** This browser's previous visit, read once: the count below is against it. */
  private readonly lastVisit = readLastVisit(this.visitStorage, 'echanges');
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  private readonly locale = inject(LOCALE_ID);
  /**
   * A solve holding the edition refuses this write in 409 — its landing
   * rewrites every seat from the plan it started on: the buttons wait for it.
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly chargement = signal(false);
  protected readonly demandes = signal<DemandeEchangeView[]>([]);
  /** Fresh simulations, keyed by demande id — loaded on demand, one at a time. */
  protected readonly impacts = signal<Record<string, EchangeSimulation>>({});
  protected readonly impactEnCours = signal<string | null>(null);
  protected readonly decisionEnCours = signal<string | null>(null);
  /**
   * « À arbitrer seulement »: the requests only the admin's word is missing
   * from — the ones « À traiter aujourd'hui » counts — the longest waiting
   * first, and nothing else on screen. `?statut=a-arbitrer`, which is what
   * the home screen links to; unticking it is the reset.
   */
  protected readonly toArbitrateOnly = signal(false);

  /** `?onglet=stats`: the statistics of the foire; absent, the requests. */
  protected readonly tab = signal<EchangesTab>('liste');
  /** The list narrowed by a figure of the statistics. */
  protected readonly listFilter = signal<ListFilter>(NO_LIST_FILTER);
  /** The creation period and the measure of the list, applied by the server; what it was loaded for. */
  protected readonly createdFrom = signal<string | null>(null);
  protected readonly createdTo = signal<string | null>(null);
  protected readonly measure = signal<ListMeasure | null>(null);
  private loadedScope: string | null = null;
  /** The visit is the list seen: a look at the statistics alone leaves the count of new requests for later. */
  private visitRecorded = false;
  protected readonly statisticsPeriod = signal<StatisticsPeriod>({ kind: 'foire' });
  private readonly statisticsTab = viewChild(EchangeStatisticsTab);

  protected readonly filtered = computed(
    () =>
      isListFiltered(this.listFilter()) ||
      this.createdFrom() !== null ||
      this.createdTo() !== null ||
      this.measure() !== null,
  );
  /** The header's « Recharger » waits for whichever tab is on screen. */
  protected readonly reloading = computed(() =>
    this.tab() === 'stats' ? (this.statisticsTab()?.loading() ?? false) : this.chargement(),
  );

  /** Swaps just told to their two people, or whose incremental solve just left: said on their card. */
  protected readonly followUpDone = signal<Record<string, string>>({});
  protected readonly followUpBusy = signal<string | null>(null);

  /** Requests submitted since this browser's previous visit — zero on a first one. */
  protected readonly nouvelles = computed(() =>
    countSince(
      this.demandes()
        .filter((demande) => demande.statut === 'PROPOSEE' || demande.statut === 'EN_ATTENTE_CIBLE')
        .map((demande) => demande.creeLe),
      this.lastVisit,
    ),
  );

  protected readonly rows = computed<DemandeRow[]>(() =>
    this.demandes()
      .filter((demande) => matchesListFilter(demande, this.listFilter()))
      .map((demande) => ({
        ...demande,
        statutLabel: statutDemandeLabel(demande.statut),
        statutClasse: statutDemandeClasse(demande.statut),
        nonCommuniquee: decisionNonCommuniquee(demande),
      })),
  );

  /** Actionable queue: the colleague already agreed, only the admin's word is missing. */
  protected readonly awaitingDecision = computed(() => {
    const proposed = this.rows().filter((row) => row.statut === 'PROPOSEE');
    return this.toArbitrateOnly() ? oldestWaitingFirst(proposed) : proposed;
  });
  /** Still waiting for the targeted colleague: informative — refusable, but not acceptable yet. */
  protected readonly enAttenteCible = computed(() =>
    this.rows().filter((row) => row.statut === 'EN_ATTENTE_CIBLE'),
  );
  protected readonly decidees = computed(() =>
    this.rows().filter((row) => row.statut !== 'PROPOSEE' && row.statut !== 'EN_ATTENTE_CIBLE'),
  );

  /** The active narrowing as chips, each removable; the creation period is one chip. */
  protected readonly chips = computed<FilterChip[]>(() => {
    const filter = this.listFilter();
    const chips: FilterChip[] = [];
    const from = this.createdFrom();
    const to = this.createdTo();
    if (from !== null || to !== null) {
      const day = (iso: string | null) =>
        iso === null ? null : formatDate(iso, 'd MMM y', this.locale);
      chips.push({ key: 'periode', label: createdLabel(day(from), day(to)) });
    }
    const measure = this.measure();
    if (measure !== null) {
      chips.push({ key: 'mesure', label: measureLabel(measure) });
    }
    if (filter.statuts.length > 0) {
      chips.push({ key: 'statuts', label: filter.statuts.map(statutDemandeLabel).join(', ') });
    }
    if (filter.directed) {
      chips.push({
        key: 'dirigees',
        label: $localize`:@@echanges.filtre.dirigees:Échanges dirigés`,
      });
    }
    if (filter.prevalidated !== null) {
      chips.push({
        key: 'prevalidee',
        label:
          filter.prevalidated === 'oui'
            ? $localize`:@@echanges.filtre.prevalidees:Prévalidées`
            : $localize`:@@echanges.filtre.nonPrevalidees:Non prévalidées`,
      });
    }
    if (filter.day !== null) {
      chips.push({
        key: 'jour',
        label:
          filter.day === REMOVED_TIMESLOT
            ? $localize`:@@echanges.filtre.creneauRetire:Créneau retiré`
            : $localize`:@@echanges.filtre.jour:Créneau du ${formatDate(filter.day, 'd MMM y', this.locale)}:jour:`,
      });
    }
    if (filter.stand !== null) {
      const stand = filter.stand;
      const name = this.demandes().find((demande) => demande.standId === stand)?.standNom ?? stand;
      chips.push({ key: 'stand', label: $localize`:@@echanges.filtre.stand:Stand ${name}:stand:` });
    }
    if (filter.constraint !== null) {
      chips.push({ key: 'contrainte', label: filter.constraint });
    }
    return chips;
  });

  constructor() {
    // Followed rather than read once: a figure of the statistics links to
    // this very route, which reuses the component.
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        const stats = readEchangesTab(params.get('onglet')) === 'stats';
        this.tab.set(stats ? 'stats' : 'liste');
        this.toArbitrateOnly.set(!stats && readToArbitrate(params.get('statut')));
        this.listFilter.set(stats ? NO_LIST_FILTER : readListFilter(params));
        const from = readDay(params.get('du'));
        const to = readDay(params.get('au'));
        this.statisticsPeriod.set(readStatisticsPeriod(params.get('periode'), from, to));
        this.createdFrom.set(stats ? null : from);
        this.createdTo.set(stats ? null : to);
        this.measure.set(stats ? null : readMeasure(params.get('mesure')));
        this.reloadIfScopeChanged();
      });
    keepViewInQueryParams(() =>
      this.tab() === 'stats'
        ? {
            onglet: 'stats',
            statut: null,
            ...listFilterParams(NO_LIST_FILTER),
            mesure: null,
            ...statisticsPeriodParams(this.statisticsPeriod()),
          }
        : {
            onglet: null,
            periode: null,
            statut: this.toArbitrateOnly() ? TO_ARBITRATE : null,
            ...listFilterParams(this.listFilter()),
            du: this.createdFrom(),
            au: this.createdTo(),
            mesure: this.measure(),
          },
    );
  }

  ngOnInit(): void {
    this.reloadIfScopeChanged();
  }

  /** « Recharger »: the data of the tab on screen — the statistics, or the list. */
  protected refresh(): void {
    if (this.tab() === 'stats') {
      this.statisticsTab()?.reload();
    } else {
      void this.reload();
    }
  }

  /** A tab opens clean: the other tab's narrowing does not follow it. */
  protected changeTab(tab: EchangesTab): void {
    this.tab.set(tab);
    this.toArbitrateOnly.set(false);
    this.listFilter.set(NO_LIST_FILTER);
    this.statisticsPeriod.set({ kind: 'foire' });
    this.createdFrom.set(null);
    this.createdTo.set(null);
    this.measure.set(null);
    this.reloadIfScopeChanged();
  }

  protected removeFilter(key: string): void {
    if (key === 'periode') {
      this.createdFrom.set(null);
      this.createdTo.set(null);
      this.reloadIfScopeChanged();
      return;
    }
    if (key === 'mesure') {
      this.measure.set(null);
      this.reloadIfScopeChanged();
      return;
    }
    const filter = this.listFilter();
    this.listFilter.set({
      statuts: key === 'statuts' ? [] : filter.statuts,
      directed: key === 'dirigees' ? false : filter.directed,
      prevalidated: key === 'prevalidee' ? null : filter.prevalidated,
      day: key === 'jour' ? null : filter.day,
      stand: key === 'stand' ? null : filter.stand,
      constraint: key === 'contrainte' ? null : filter.constraint,
    });
  }

  protected clearFilters(): void {
    this.listFilter.set(NO_LIST_FILTER);
    this.createdFrom.set(null);
    this.createdTo.set(null);
    this.measure.set(null);
    this.reloadIfScopeChanged();
  }

  /**
   * The list is read again only when what the server narrows it by moved —
   * its creation period, its measure — the other criteria filter what is
   * loaded; and never while the statistics are on screen, which show no name.
   */
  private reloadIfScopeChanged(): void {
    if (this.tab() !== 'liste') {
      return;
    }
    if (!this.visitRecorded) {
      this.visitRecorded = true;
      writeLastVisit(this.visitStorage, 'echanges', new Date().toISOString());
    }
    const scope = `${this.createdFrom() ?? ''}..${this.createdTo() ?? ''}|${this.measure() ?? ''}`;
    if (scope !== this.loadedScope) {
      this.loadedScope = scope;
      void this.reload();
    }
  }

  /** An accepted swap whose announcement has not left: the two follow-ups are offered on its card. */
  protected offersFollowUps(demande: DemandeRow): boolean {
    return demande.statut === 'ACCEPTEE' && demande.nonCommuniquee;
  }

  /** « Prévenir les 2 personnes »: a publication aimed at the two people of the swap. */
  protected async prevenir(demande: DemandeRow): Promise<void> {
    const cibles = [demande.demandeurId, demande.cibleId];
    const confirmed = await this.confirm.ask({
      title: $localize`:@@echanges.prevenir.titre:Prévenir ${demande.demandeurNom}:demandeur: et ${demande.cibleNom}:cible: ?`,
      message: $localize`:@@echanges.prevenir.message:Ces deux personnes reçoivent leur planning à jour et leur espace l'affiche. Les autres personnes à prévenir le seront à la prochaine publication.`,
      confirmLabel: $localize`:@@echanges.prevenir.confirmer:Prévenir`,
    });
    if (!confirmed) {
      return;
    }
    this.followUpBusy.set(demande.id);
    try {
      const rapport = await this.planningApi.publishTo(cibles);
      const resume = resumePublication(rapport);
      this.followUpDone.set({ ...this.followUpDone(), [demande.id]: resume.titre });
      this.notifications.notify({
        title: resume.titre,
        message: resume.details,
        variant: rapport.echecs.length > 0 ? 'error' : 'success',
      });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.followUpBusy.set(null);
    }
  }

  /** « Corriger le reste »: the incremental solve, which re-fills what the swap left and keeps the rest. */
  protected async corriger(demande: DemandeRow): Promise<void> {
    this.followUpBusy.set(demande.id);
    try {
      await this.jobs.submitSolveIncremental(
        { animateurIds: [], jours: [], standIds: [] },
        undefined,
        this.jobs.solverBusy(),
      );
      this.followUpDone.set({
        ...this.followUpDone(),
        [demande.id]: $localize`:@@echanges.corriger.lance:Replanification incrémentale lancée.`,
      });
    } catch (error) {
      this.report(error);
    } finally {
      this.followUpBusy.set(null);
    }
  }

  protected async reload(): Promise<void> {
    this.chargement.set(true);
    const scope = this.loadedScope;
    try {
      const demandes = await this.echangesApi.list(
        this.createdFrom(),
        this.createdTo(),
        this.measure(),
      );
      // The scope changed while this read was in flight: its answer is no longer the one on screen.
      if (scope === this.loadedScope) {
        this.demandes.set(demandes);
      }
    } catch (error) {
      this.report(error);
    } finally {
      this.chargement.set(false);
    }
  }

  protected impactDe(demande: DemandeRow): EchangeSimulation | null {
    return this.impacts()[demande.id] ?? null;
  }

  /** The delta is a score object — rendered through the shared formatter, never interpolated raw. */
  protected formatDelta(delta: HardMediumSoftScore): string {
    return formatDeltaScore(delta);
  }

  protected async chargerImpact(demande: DemandeRow): Promise<void> {
    this.impactEnCours.set(demande.id);
    try {
      const impact = await this.echangesApi.impact(demande.id);
      this.impacts.set({ ...this.impacts(), [demande.id]: impact });
    } catch (error) {
      this.report(error);
    } finally {
      this.impactEnCours.set(null);
    }
  }

  protected async accepter(demande: DemandeRow): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@echanges.accepterTitre:Accepter l'échange de ${demande.demandeurNom}:demandeur: ?`,
      message: $localize`:@@echanges.accepterMessage:L'échange avec ${demande.cibleNom}:cible: sera appliqué immédiatement au planning et verrouillé sur ce créneau. La demande ne pourra plus être refusée ensuite.`,
      confirmLabel: $localize`:@@echanges.accepterConfirm:Accepter`,
    });
    if (!confirmed) {
      return;
    }
    await this.decider(
      demande,
      'acceptation',
      null,
      $localize`:@@echanges.acceptee2:Échange appliqué et verrouillé. Prévenez les deux personnes ou corrigez le reste depuis sa carte.`,
    );
  }

  protected async refuser(demande: DemandeRow): Promise<void> {
    const commentaire = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@echanges.refuserTitre:Refuser la demande de ${demande.demandeurNom}:demandeur:`,
      label: $localize`:@@echanges.refuserLabel:Motif du refus (transmis à l'animateur)`,
      confirmLabel: $localize`:@@echanges.refuserConfirm:Refuser`,
    });
    if (commentaire === null) {
      return;
    }
    await this.decider(
      demande,
      'refus',
      commentaire,
      $localize`:@@echanges.refusee:Demande refusée, le planning reste inchangé.`,
    );
  }

  private async decider(
    demande: DemandeRow,
    action: 'acceptation' | 'refus',
    commentaire: string | null,
    confirmation: string,
  ): Promise<void> {
    this.decisionEnCours.set(demande.id);
    try {
      await this.echangesApi.decide(demande.id, action, commentaire);
      this.notifications.notify({ title: confirmation, variant: 'success', timeout: 5000 });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  private report(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}

/** The creation period of the list, as its chip reads it; the days already worded. */
function createdLabel(from: string | null, to: string | null): string {
  if (from !== null && to !== null) {
    return from === to
      ? $localize`:@@echanges.filtre.creeesLe:Créées le ${from}:jour:`
      : $localize`:@@echanges.filtre.creeesEntre:Créées du ${from}:du: au ${to}:au:`;
  }
  return from !== null
    ? $localize`:@@echanges.filtre.creeesDepuis:Créées depuis le ${from}:du:`
    : $localize`:@@echanges.filtre.creeesJusquau:Créées jusqu'au ${to}:au:`;
}

/** The measure of the list, as its chip reads it: the figure of the statistics it came from. */
function measureLabel(measure: ListMeasure): string {
  switch (measure) {
    case 'repondues':
      return $localize`:@@echanges.filtre.mesure.repondues:Réponse du collègue reçue`;
    case 'accordees':
      return $localize`:@@echanges.filtre.mesure.accordees:Accord du collègue`;
    case 'delai-reponse':
      return $localize`:@@echanges.filtre.mesure.delaiReponse:Délai de réponse mesuré`;
    case 'delai-arbitrage':
      return $localize`:@@echanges.filtre.mesure.delaiArbitrage:Délai d'arbitrage mesuré`;
    case 'delai-communication':
      return $localize`:@@echanges.filtre.mesure.delaiCommunication:Délai de communication mesuré`;
    case 'delai-annulation':
      return $localize`:@@echanges.filtre.mesure.delaiAnnulation:Délai d'annulation mesuré`;
  }
}
