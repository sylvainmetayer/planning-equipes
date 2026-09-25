import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { dosageLines, dosageSummary, dosageToken } from '../../core/dosage';
import { EditionStore } from '../../core/edition.store';
import { RejeuPanel } from './rejeu-panel';
import { clampRank, resolutionsOfEdition } from './rejeu';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { StatusMessage } from '../../shared/status-message';
import { errorMessage } from '../../core/error-message';
import { errorText, retainedValue } from '../../core/resource-state';

/**
 * KPI history (issue #89): one row per completed solve, kept across editions
 * so a year can be compared to the previous one. Rows survive the deletion of
 * their edition (labels are denormalised server-side), and hold nothing
 * nominative — fairness is a dispersion of hours, never a ranking of people.
 */
@Component({
  selector: 'app-kpi-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
    RejeuPanel,
  ],
  templateUrl: './kpi-page.html',
  styleUrl: './kpi-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KpiPage {
  protected readonly columns = [
    'creeLe',
    'edition',
    'score',
    'scoreHorsPlancher',
    'couverture',
    'fairness',
    'modifications',
    'consignes',
    'duree',
    'dosage',
    'actions',
  ];

  private readonly analysesApi = inject(AnalysesApi);
  private readonly route = inject(ActivatedRoute);
  private readonly editionStore = inject(EditionStore);
  private readonly confirm = inject(ConfirmService);

  /**
   * Bumped by every refresh. Re-keying the resource rather than calling
   * `reload()`: `reload()` is a no-op while a request is in flight — a first
   * load or a refresh alike — and the deletion confirmed during a refresh
   * then never asked the server again, leaving the deleted row on screen.
   * A re-key clears the previous failure the moment the refresh starts,
   * where a reload kept it until the answer; the rows stay either way.
   */
  private readonly version = signal(0);
  private readonly history = resource({
    params: () => ({ version: this.version() }),
    loader: () => this.analysesApi.kpiHistory(),
  });
  // The list is kept across a failed refresh, unlike the report of /heures
  // which is dropped: this is history the server already holds, and losing
  // the screen to a network blip helps nobody. The error sits next to it, and
  // the operator just pressed « Actualiser », so nothing here passes for fresh.
  private readonly historyKept = retainedValue(this.history);
  protected readonly entries = computed(() => this.historyKept() ?? []);

  /**
   * « Résolutions sous le même dosage »: the token of the dosage the table is
   * narrowed to, `null` for every row. In the URL as `?dosage=`, so a
   * comparison at equal weights survives a refresh and can be shared.
   */
  protected readonly dosageFilter = signal<string | null>(
    this.route.snapshot.queryParamMap.get('dosage') || null,
  );
  /**
   * The edition the replay walks and the table narrows to: `?edition=` when
   * the operator chose one, `*` for every edition — no replay then — and,
   * absent, the edition being worked in.
   */
  private readonly editionChoice = signal<string | null>(
    this.route.snapshot.queryParamMap.get('edition') || null,
  );
  protected readonly replayEdition = computed(() => {
    const choice = this.editionChoice();
    if (choice === ALL_EDITIONS) {
      return null;
    }
    return choice ?? this.editionStore.courant()?.id ?? null;
  });
  /** The rank the replay points at, 0-based; `null` for the latest. `?rang=` is 1-based, as said on screen. */
  protected readonly replayRank = signal<number | null>(
    readRank(this.route.snapshot.queryParamMap.get('rang')),
  );
  private readonly replayResolutions = computed(() => {
    const edition = this.replayEdition();
    return edition === null ? [] : resolutionsOfEdition(this.entries(), edition);
  });
  /** The row the replay points at, highlighted in the table. */
  protected readonly pointedId = computed(() => {
    const resolutions = this.replayResolutions();
    return resolutions[clampRank(this.replayRank(), resolutions.length)]?.id ?? null;
  });

  /** The rows on screen: the replayed edition's (every one's without replay), narrowed to a dosage if asked. */
  protected readonly visibleEntries = computed(() => {
    const token = this.dosageFilter();
    const edition = this.replayEdition();
    return this.entries().filter(
      (entry) =>
        (edition === null || entry.editionId === edition) &&
        (token === null || dosageToken(entry.kpi.dosage) === token),
    );
  });

  constructor() {
    keepViewInQueryParams(() => ({
      dosage: optionalParam(this.dosageFilter()),
      edition: optionalParam(this.editionChoice()),
      rang: this.replayRank() === null ? null : String((this.replayRank() ?? 0) + 1),
    }));
  }

  /** A new edition replays from its latest solve. */
  protected chooseReplayEdition(editionId: string | null): void {
    this.editionChoice.set(editionId ?? ALL_EDITIONS);
    this.replayRank.set(null);
  }

  /** A click on a row puts the replay's cursor on it — in its own edition when the table shows them all. */
  protected pointAt(entry: KpiHistoriqueEntry): void {
    if (this.replayEdition() !== entry.editionId) {
      this.editionChoice.set(entry.editionId);
    }
    const rank = resolutionsOfEdition(this.entries(), entry.editionId).findIndex(
      (resolution) => resolution.id === entry.id,
    );
    this.replayRank.set(rank < 0 ? null : rank);
  }
  protected readonly chargement = this.history.isLoading;
  /** A deletion refused server-side; the next refresh clears it. */
  private readonly actionError = signal('');
  private readonly loadError = errorText(this.history, errorMessage);
  protected readonly error = computed(() => this.actionError() || this.loadError());

  protected recharger(): void {
    this.actionError.set('');
    this.version.update((version) => version + 1);
  }

  protected async supprimer(entry: KpiHistoriqueEntry): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@kpi.delete.title:Supprimer cette ligne d'historique ?`,
      message: $localize`:@@kpi.delete.message:La mesure du ${this.dateLabel(entry)}:date: sera définitivement perdue.`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    try {
      await this.analysesApi.deleteKpiEntry(entry.id);
      this.recharger();
    } catch (error) {
      this.actionError.set(errorMessage(error));
    }
  }

  protected dateLabel(entry: KpiHistoriqueEntry): string {
    return entry.creeLe ? new Date(entry.creeLe).toLocaleString(intlLocale()) : '';
  }

  protected editionLabel(entry: KpiHistoriqueEntry): string {
    return entry.editionNom ?? entry.editionId;
  }

  protected scoreLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.score ?? '—';
  }

  /**
   * The medium score net of its floor (issue #495), and a dash for a row
   * written before the floor was measured — unmeasured is not zero.
   */
  protected scoreHorsPlancherLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.scoreMediumHorsPlancher === null
      ? '—'
      : `${entry.kpi.scoreMediumHorsPlancher} medium`;
  }

  protected couvertureLabel(entry: KpiHistoriqueEntry): string {
    if (entry.kpi.postesTotal === 0) {
      return '—';
    }
    const pourcent = ((entry.kpi.postesPourvus / entry.kpi.postesTotal) * 100).toFixed(1);
    return `${entry.kpi.postesPourvus} / ${entry.kpi.postesTotal} (${pourcent} %)`;
  }

  protected fairnessLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.heuresEcartType === null ? '—' : `σ ${entry.kpi.heuresEcartType.toFixed(1)} h`;
  }

  protected modificationsLabel(entry: KpiHistoriqueEntry): string {
    if (entry.kpi.modificationsManuelles === null) {
      return '—';
    }
    const taux =
      entry.kpi.tauxModificationsManuelles === null
        ? ''
        : ` (${(entry.kpi.tauxModificationsManuelles * 100).toFixed(1)} %)`;
    return `${entry.kpi.modificationsManuelles}${taux}`;
  }

  /**
   * The days under a consigne and the seat-hours their bands took (issue #4);
   * a dash for a row older than the measure — unmeasured is not zero.
   */
  protected consignesLabel(entry: KpiHistoriqueEntry): string {
    const { journeesSousConsigne, heuresFermeesParConsigne } = entry.kpi;
    if (journeesSousConsigne === null && heuresFermeesParConsigne === null) {
      return '—';
    }
    const journees = journeesSousConsigne ?? 0;
    const heures = heuresFermeesParConsigne ?? 0;
    return $localize`:@@kpi.consignes.valeur:${journees}:journees: journée(s) · ${arrondiHeures(heures)}:heures: h fermées`;
  }

  /** « défaut », « N règle(s) repondérée(s) », « inconnu » for a row older than the figure. */
  protected dosageLabel(entry: KpiHistoriqueEntry): string {
    return dosageSummary(entry.kpi.dosage);
  }

  /** The rules the dosage moves, one per line, for the tooltip. */
  protected dosageDetail(entry: KpiHistoriqueEntry): string {
    return dosageLines(entry.kpi.dosage).join('\n');
  }

  /** Whether the row's dosage is known, hence something to filter on. */
  protected dosageKnown(entry: KpiHistoriqueEntry): boolean {
    return dosageToken(entry.kpi.dosage) !== null;
  }

  /** Narrows the table to the solves run under this row's dosage. */
  protected sameDosage(entry: KpiHistoriqueEntry): void {
    this.dosageFilter.set(dosageToken(entry.kpi.dosage));
  }

  protected allDosages(): void {
    this.dosageFilter.set(null);
  }

  protected dureeLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.dureeSolveSecondes === null ? '—' : `${entry.kpi.dureeSolveSecondes} s`;
  }
}

/** `?edition=*`: every edition in the table, and no replay. */
const ALL_EDITIONS = '*';

/** `?rang=` is 1-based, the way the screen counts; a hand-edited value falls back to the latest solve. */
function readRank(value: string | null): number | null {
  const rank = value === null ? NaN : Number.parseInt(value, 10);
  return Number.isFinite(rank) && rank >= 1 ? rank - 1 : null;
}

function arrondiHeures(heures: number): string {
  return Number.isInteger(heures) ? String(heures) : heures.toFixed(1);
}
