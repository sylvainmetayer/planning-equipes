import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { EntreeHistorique } from '../../core/models';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import {
  FiltreActeur,
  FiltreNature,
  FiltreResultat,
  cutPage,
  entitesPresentes,
  exportCodes,
  filter,
  historyQuery,
  instantFromLocalInput,
  endInstantFromLocalInput,
  localInputValue,
  readActorFilter,
  readInstant,
  readNatureFilter,
  readOutcomeFilter,
  natureQuery,
  parJournee,
  qui,
  resultOf,
  surQuoi,
} from './historique';

/** How long a period field rests before the server is asked for its period. */
const PERIOD_DEBOUNCE_MS = 600;

/**
 * Ce qui s'est passé dans cette édition, et par qui (issue #406).
 *
 * <p>Read-only by construction: the history has no button that changes it, and
 * what bounds it is a retention applied server-side, not a delete action. What
 * the table stores is an identifier and a list of field names — the identity is
 * joined by the server when it reads, so a fiche deleted since leaves a line
 * that names nobody.</p>
 */
@Component({
  selector: 'app-historique-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './historique-page.html',
  styleUrl: './historique-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoriquePage implements OnInit {
  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly entrees = signal<EntreeHistorique[]>([]);
  protected readonly chargement = signal(false);
  /** The cursor of the next page — the id of the last line shown —, `null` on the last one. */
  protected readonly suivant = signal<number | null>(null);
  protected readonly erreur = signal('');

  protected readonly acteur = signal<FiltreActeur>('TOUS');
  protected readonly resultat = signal<FiltreResultat>('TOUS');
  protected readonly nature = signal<FiltreNature>('TOUTES');
  /**
   * The period, as the server reads it: instants, `depuis` exclusive and
   * `jusqua` inclusive, `''` for no bound. Kept verbatim from the URL — the
   * Solveur's link carries the end of a solve to the microsecond.
   */
  protected readonly depuis = signal('');
  protected readonly jusqua = signal('');
  /** The two bounds as the `datetime-local` fields show them, on the reader's clock. */
  protected readonly depuisLocal = computed(() => localInputValue(this.depuis()));
  protected readonly jusquaLocal = computed(() => localInputValue(this.jusqua()));
  protected readonly entite = signal('');
  protected readonly recherche = signal('');
  /** The codes the server's catalogue flags as exports; empty until the inventory is read. */
  private readonly exports = signal<ReadonlySet<string>>(new Set());
  /** Which load the list shows: an answer to an older one never overwrites a newer one. */
  private currentLoad = 0;
  /** What a period field typed and the server was not asked yet; `null` for untouched. */
  private pendingDepuis: string | null = null;
  private pendingJusqua: string | null = null;
  private periodTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly entites = computed(() => entitesPresentes(this.entrees()));
  protected readonly filtrees = computed(() =>
    filter(this.entrees(), this.acteur(), this.resultat(), this.entite(), this.recherche()),
  );
  protected readonly journees = computed(() => parJournee(this.filtrees()));

  /** True as soon as the screen shows something other than its default view. */
  protected readonly viewChanged = computed(
    () =>
      this.acteur() !== 'TOUS' ||
      this.resultat() !== 'TOUS' ||
      this.nature() !== 'TOUTES' ||
      this.depuis() !== '' ||
      this.jusqua() !== '' ||
      this.entite() !== '' ||
      this.recherche().trim() !== '',
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cancelPendingTimer());
    const params = this.route.snapshot.queryParamMap;
    this.acteur.set(readActorFilter(params.get('acteur')));
    this.resultat.set(readOutcomeFilter(params.get('resultat')));
    this.nature.set(readNatureFilter(params.get('nature')));
    this.depuis.set(readInstant(params.get('depuis')));
    this.jusqua.set(readInstant(params.get('jusqua')));
    this.entite.set(params.get('entite') ?? '');
    this.recherche.set(params.get('q') ?? '');
    keepViewInQueryParams(() => ({
      acteur: this.acteur() === 'TOUS' ? null : this.acteur(),
      resultat: this.resultat() === 'TOUS' ? null : this.resultat(),
      nature: natureQuery(this.nature()),
      depuis: optionalParam(this.depuis()),
      jusqua: optionalParam(this.jusqua()),
      entite: optionalParam(this.entite()),
      q: optionalParam(this.recherche()),
    }));
  }

  ngOnInit(): void {
    void this.recharger();
    void this.loadActionInventory();
  }

  /** The first page again, under the filters as they stand. */
  protected async recharger(): Promise<void> {
    const ticket = ++this.currentLoad;
    this.chargement.set(true);
    this.erreur.set('');
    try {
      const page = cutPage(
        await this.analysesApi.actionHistory(
          historyQuery(this.nature(), this.depuis(), this.jusqua(), null),
        ),
      );
      if (ticket === this.currentLoad) {
        this.entrees.set(page.entrees);
        this.suivant.set(page.suivant);
      }
    } catch (error) {
      if (ticket === this.currentLoad) {
        // The lines on screen answered the filters as they were: kept under
        // the new ones, they would read as its answer — and « Charger plus »
        // would page the old selection under the new filters.
        this.entrees.set([]);
        this.suivant.set(null);
        this.erreur.set(errorPrefix(error));
      }
    } finally {
      if (ticket === this.currentLoad) {
        this.chargement.set(false);
      }
    }
  }

  /**
   * « Charger plus »: the page after the last line shown, under the same
   * filters. A reload asked meanwhile wins — its first page would not follow
   * this one.
   */
  protected async loadMore(): Promise<void> {
    const before = this.suivant();
    if (before === null) {
      return;
    }
    const ticket = this.currentLoad;
    this.chargement.set(true);
    this.erreur.set('');
    try {
      const page = cutPage(
        await this.analysesApi.actionHistory(
          historyQuery(this.nature(), this.depuis(), this.jusqua(), before),
        ),
      );
      if (ticket === this.currentLoad) {
        this.entrees.update((deja) => [...deja, ...page.entrees]);
        this.suivant.set(page.suivant);
      }
    } catch (error) {
      if (ticket === this.currentLoad) {
        this.erreur.set(errorPrefix(error));
      }
    } finally {
      if (ticket === this.currentLoad) {
        this.chargement.set(false);
      }
    }
  }

  /**
   * « Exports » and « Données » are questions put to the server — over the
   * whole retention, not over the lines already on screen — so changing the
   * nature reloads.
   */
  protected changeNature(nature: FiltreNature): void {
    if (nature === this.nature()) {
      return;
    }
    this.nature.set(nature);
    void this.recharger();
  }

  /**
   * A bound typed in its `datetime-local` field; the period is the server's to
   * apply, once the typing rests or the field is left (`commitPeriod`) — the
   * field reports every segment typed, and « 2026 » typed digit by digit is
   * four valid years.
   */
  protected changeDepuis(local: string): void {
    this.pendingDepuis = local;
    this.schedulePeriod();
  }

  protected changeJusqua(local: string): void {
    this.pendingJusqua = local;
    this.schedulePeriod();
  }

  private schedulePeriod(): void {
    this.cancelPendingTimer();
    this.periodTimer = setTimeout(() => this.commitPeriod(), PERIOD_DEBOUNCE_MS);
  }

  private cancelPendingTimer(): void {
    if (this.periodTimer !== null) {
      clearTimeout(this.periodTimer);
      this.periodTimer = null;
    }
  }

  /**
   * The period the fields hold now. A field that still shows its bound keeps
   * it as it was: the Solveur's `depuis` is exact to the microsecond, and the
   * field shows it to the minute — only an actual edit replaces it.
   * « Jusqu'à 14:32 » reaches the end of that minute: the server reads that
   * bound inclusive.
   */
  protected commitPeriod(): void {
    this.cancelPendingTimer();
    const local = { depuis: this.pendingDepuis, jusqua: this.pendingJusqua };
    this.pendingDepuis = null;
    this.pendingJusqua = null;
    const depuis =
      local.depuis === null || local.depuis === this.depuisLocal()
        ? this.depuis()
        : instantFromLocalInput(local.depuis);
    const jusqua =
      local.jusqua === null || local.jusqua === this.jusquaLocal()
        ? this.jusqua()
        : endInstantFromLocalInput(local.jusqua);
    this.changePeriod(depuis, jusqua);
  }

  private changePeriod(depuis: string, jusqua: string): void {
    if (depuis === this.depuis() && jusqua === this.jusqua()) {
      return;
    }
    this.depuis.set(depuis);
    this.jusqua.set(jusqua);
    void this.recharger();
  }

  /**
   * The server's classification of the actions. Only used to word a line, so
   * a failure leaves the list readable rather than showing an error.
   */
  private async loadActionInventory(): Promise<void> {
    try {
      this.exports.set(exportCodes(await this.analysesApi.actionInventory()));
    } catch {
      // Without it an export's names read as changed fields: worded less
      // precisely, never wrong about what the line records.
    }
  }

  /** Whether the line is a file leaving the application, as the server's catalogue says. */
  protected isExport(entree: EntreeHistorique): boolean {
    return this.exports().has(entree.action);
  }

  protected reinitialiser(): void {
    this.cancelPendingTimer();
    this.pendingDepuis = null;
    this.pendingJusqua = null;
    const serverSide = this.nature() !== 'TOUTES' || this.depuis() !== '' || this.jusqua() !== '';
    this.acteur.set('TOUS');
    this.resultat.set('TOUS');
    this.nature.set('TOUTES');
    this.depuis.set('');
    this.jusqua.set('');
    this.entite.set('');
    this.recherche.set('');
    if (serverSide) {
      void this.recharger();
    }
  }

  protected qui(entree: EntreeHistorique): string {
    return qui(entree);
  }

  protected surQuoi(entree: EntreeHistorique): string {
    return surQuoi(entree);
  }

  protected resultOf(entree: EntreeHistorique): string {
    return resultOf(entree);
  }

  /** « 14:32 » — the day is already the group's heading. */
  protected heure(iso: string): string {
    return new Date(iso).toLocaleTimeString(intlLocale(), { hour: '2-digit', minute: '2-digit' });
  }

  /**
   * `jour` is already a **local** calendar date (see `journeeLocale`), so it is
   * rebuilt from its parts: `new Date('2026-09-07')` is UTC midnight, which
   * renders as the 6th anywhere west of Greenwich.
   */
  protected jourLisible(jour: string): string {
    const [annee, mois, quantieme] = jour.split('-').map(Number);
    return new Date(annee, mois - 1, quantieme).toLocaleDateString(intlLocale(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  }
}
