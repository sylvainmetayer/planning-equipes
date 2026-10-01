import { DatePipe, formatDate } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  LOCALE_ID,
  output,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Params, RouterLink } from '@angular/router';
import { EchangesApi, ListMeasure, StatisticsPeriod } from '../../core/api/echanges-api';
import {
  EchangeDelay,
  EchangeRate,
  EchangeStatistics,
  StatutDemandeEchange,
} from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { StatusMessage } from '../../shared/status-message';
import { ListFilter, REMOVED_TIMESLOT } from './echanges-filter';
import {
  HISTOGRAM_HEIGHT,
  HistogramBar,
  describePeriod,
  formatDelay,
  histogramBars,
  histogramPeak,
  histogramWidth,
  ListLinkCriteria,
  listLink,
  readRate,
} from './statistics-format';

/** How many stands are listed before « Voir tous les stands ». */
const STANDS_SHOWN = 10;

/** The statuts a rate is counted over, where the statut alone says it. */
const DECIDED: StatutDemandeEchange[] = ['ACCEPTEE', 'REFUSEE'];
const FINISHED: StatutDemandeEchange[] = ['ACCEPTEE', 'REFUSEE', 'REFUSEE_CIBLE', 'ANNULEE'];

/**
 * One rate tile: what it says, its two counts and the requests behind each;
 * `percent` is null on a population too small for one (« 2 sur 3 » alone).
 */
interface RateTile {
  label: string;
  percent: number | null;
  numerator: number;
  denominator: number;
  numeratorLink: Params;
  denominatorLink: Params;
}

/** One delay tile: what it measures, its figures in words, and the requests it was measured over — those alone. */
interface DelayTile {
  label: string;
  delay: EchangeDelay;
  median: string | null;
  tail: string | null;
  mean: string | null;
  link: Params;
}

/**
 * The Statistiques tab of the swap requests screen: whether the foire works —
 * volumes, the three rates, the four delays and the distributions — over a
 * period of creation days. Aggregates only; every figure opens the List tab
 * narrowed to the requests it counts, which is where the names are.
 *
 * <p>The period is the page's to keep in the URL: this tab reads it as an
 * input and says when the person changes it.</p>
 */
@Component({
  selector: 'app-echanges-statistiques',
  imports: [
    DatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './echanges-statistiques.html',
  styleUrl: './echanges-statistiques.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EchangeStatisticsTab {
  private readonly api = inject(EchangesApi);
  private readonly locale = inject(LOCALE_ID);

  readonly period = input<StatisticsPeriod>({ kind: 'foire' });
  readonly periodChange = output<StatisticsPeriod>();

  private readonly statistics = resource({
    params: () => this.period(),
    loader: ({ params }) => this.api.statistics(params),
  });
  protected readonly stats = retainedValue(this.statistics);
  /** Read by the page, whose « Recharger » reloads the tab on screen. */
  readonly loading = this.statistics.isLoading;
  protected readonly error = errorText(this.statistics);

  protected readonly histogramHeight = HISTOGRAM_HEIGHT;
  protected readonly directedOnly: Partial<ListFilter> = { directed: true };
  protected readonly waitingForColleague: Partial<ListFilter> = { statuts: ['EN_ATTENTE_CIBLE'] };
  protected readonly waitingForOrganisation: Partial<ListFilter> = { statuts: ['PROPOSEE'] };
  protected readonly showAllStands = signal(false);

  protected readonly dates = computed(() => {
    const period = this.period();
    return period.kind === 'dates' ? period : null;
  });
  protected readonly periodText = computed(() => {
    const stats = this.stats();
    return stats ? describePeriod(stats, (day) => formatDate(day, 'd MMMM y', this.locale)) : '';
  });
  protected readonly bars = computed(() => histogramBars(this.stats()?.parJourCreation ?? []));
  protected readonly width = computed(() => histogramWidth(this.stats()?.parJourCreation ?? []));
  /** The legend under the bars: as wide as they are, but never too narrow to read. */
  protected readonly scaleWidth = computed(() => Math.max(this.width(), 240));
  protected readonly peak = computed(() => histogramPeak(this.stats()?.parJourCreation ?? []));
  protected readonly stands = computed(() => {
    const all = this.stats()?.parStand ?? [];
    return this.showAllStands() ? all : all.slice(0, STANDS_SHOWN);
  });
  protected readonly hiddenStands = computed(
    () => (this.stats()?.parStand.length ?? 0) - this.stands().length,
  );

  protected readonly rates = computed<RateTile[]>(() => {
    const stats = this.stats();
    if (!stats) {
      return [];
    }
    return [
      this.rateTile(
        stats,
        $localize`:@@echanges.stats.taux.collegues:Accord des collègues`,
        stats.accordCollegues,
        // Read off the colleague's answer, not a statut: the server's measure.
        { measure: 'accordees' },
        { measure: 'repondues' },
      ),
      this.rateTile(
        stats,
        $localize`:@@echanges.stats.taux.organisation:Acceptation par l'organisation`,
        stats.acceptationOrganisation,
        { statuts: ['ACCEPTEE'] },
        { statuts: DECIDED },
      ),
      this.rateTile(
        stats,
        $localize`:@@echanges.stats.taux.aboutissement:Aboutissement`,
        stats.aboutissement,
        { statuts: ['ACCEPTEE'] },
        { statuts: FINISHED },
      ),
      this.rateTile(
        stats,
        $localize`:@@echanges.stats.taux.prevalidees:Prévalidées à l'envoi`,
        stats.prevalidees,
        { prevalidated: 'oui' },
        {},
      ),
      this.rateTile(
        stats,
        $localize`:@@echanges.stats.taux.nonPrevalidees:Acceptation des non prévalidées`,
        stats.acceptationNonPrevalidees,
        { prevalidated: 'non', statuts: ['ACCEPTEE'] },
        { prevalidated: 'non', statuts: DECIDED },
      ),
    ];
  });

  protected readonly delays = computed<DelayTile[]>(() => {
    const stats = this.stats();
    if (!stats) {
      return [];
    }
    return [
      this.delayTile(
        stats,
        $localize`:@@echanges.stats.delai.collegue:Réponse du collègue`,
        stats.delaiReponseCollegue,
        'delai-reponse',
      ),
      this.delayTile(
        stats,
        $localize`:@@echanges.stats.delai.arbitrage:Arbitrage de l'organisation`,
        stats.delaiArbitrage,
        'delai-arbitrage',
      ),
      this.delayTile(
        stats,
        $localize`:@@echanges.stats.delai.communication:Communication de la décision`,
        stats.delaiCommunication,
        'delai-communication',
      ),
      this.delayTile(
        stats,
        $localize`:@@echanges.stats.delai.annulation:Annulation par le demandeur`,
        stats.delaiAnnulation,
        'delai-annulation',
      ),
    ];
  });

  /** Reads the statistics again, for the same period: « Recharger » of the page. */
  reload(): void {
    this.statistics.reload();
  }

  protected choosePeriod(kind: StatisticsPeriod['kind']): void {
    if (kind === 'dates') {
      // Starts from the period on screen, so « Choisir les dates » edits it rather than wiping it.
      const stats = this.stats();
      this.periodChange.emit({ kind, du: stats?.du ?? null, au: stats?.au ?? null });
    } else {
      this.periodChange.emit({ kind });
    }
  }

  protected setBound(bound: 'du' | 'au', event: Event): void {
    const dates = this.dates();
    if (!dates || !(event.target instanceof HTMLInputElement)) {
      return;
    }
    this.periodChange.emit({ ...dates, [bound]: event.target.value || null });
  }

  protected link(stats: EchangeStatistics, criteria: Partial<ListFilter> = {}): Params {
    return listLink(stats, criteria);
  }

  /** One creation day: the list of that day alone, whatever the period. */
  protected dayLink(stats: EchangeStatistics, day: string): Params {
    return listLink(stats, {}, { du: day, au: day });
  }

  protected eventDayLink(stats: EchangeStatistics, day: string): Params {
    return listLink(stats, { day });
  }

  protected standLink(stats: EchangeStatistics, stand: string): Params {
    return listLink(stats, { stand });
  }

  protected constraintLink(stats: EchangeStatistics, constraint: string): Params {
    return listLink(stats, { prevalidated: 'non', constraint });
  }

  protected removedTimeslotLink(stats: EchangeStatistics): Params {
    return listLink(stats, { day: REMOVED_TIMESLOT });
  }

  protected barLabel(bar: HistogramBar): string {
    const day = formatDate(bar.day, 'EEEE d MMMM', this.locale);
    return $localize`:@@echanges.stats.histogramme.barre:${day}:jour: : ${bar.count}:nombre: demande(s)`;
  }

  private rateTile(
    stats: EchangeStatistics,
    label: string,
    rate: EchangeRate,
    numerator: ListLinkCriteria,
    denominator: ListLinkCriteria,
  ): RateTile {
    const reading = readRate(rate);
    return {
      label,
      percent: reading.kind === 'percent' ? reading.percent : null,
      numerator: rate.numerateur,
      denominator: rate.denominateur,
      numeratorLink: listLink(stats, numerator),
      denominatorLink: listLink(stats, denominator),
    };
  }

  private delayTile(
    stats: EchangeStatistics,
    label: string,
    delay: EchangeDelay,
    measure: ListMeasure,
  ): DelayTile {
    const words = (seconds: number | null) => (seconds === null ? null : formatDelay(seconds));
    const mean = words(delay.moyenneSecondes);
    return {
      label,
      delay,
      median: words(delay.medianeSecondes),
      tail: words(delay.centile90Secondes),
      mean:
        mean === null
          ? null
          : $localize`:@@echanges.stats.delai.moyenne:Moyenne : ${mean}:moyenne:`,
      // Exactly the requests measured: neither the unstamped ones nor a decision not yet published.
      link: listLink(stats, { measure }),
    };
  }
}
