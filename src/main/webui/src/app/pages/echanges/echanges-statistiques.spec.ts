// The Statistiques tab of the swap requests screen: the figures read off the
// server's aggregates, a small population said « 2 sur 3 » rather than as a
// percentage, the mean of a delay kept for the tooltip, every figure a link
// to the list narrowed on what it counts, and no tile at all on an empty period.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EchangesApi, StatisticsPeriod } from '../../core/api/echanges-api';
import { EchangeDelay, EchangeStatistics } from '../../core/models';
import { EchangeStatisticsTab } from './echanges-statistiques';
import {
  describePeriod,
  formatDelay,
  histogramBars,
  listLink,
  readRate,
  readStatisticsPeriod,
  statisticsPeriodParams,
} from './statistics-format';

const NOTHING_MEASURED: EchangeDelay = {
  mesurees: 0,
  sansHorodatage: 0,
  medianeSecondes: null,
  centile90Secondes: null,
  moyenneSecondes: null,
};

const STATISTICS: EchangeStatistics = {
  du: '2026-06-01',
  au: '2026-06-30',
  fenetreFoire: true,
  fuseau: 'Europe/Paris',
  creees: 12,
  dirigees: 3,
  enAttenteCible: 2,
  enAttenteOrganisation: 1,
  acceptees: 6,
  refusees: 2,
  refuseesCible: 1,
  annulees: 0,
  accordCollegues: { numerateur: 9, denominateur: 10 },
  acceptationOrganisation: { numerateur: 6, denominateur: 8 },
  aboutissement: { numerateur: 6, denominateur: 9 },
  prevalidees: { numerateur: 10, denominateur: 12 },
  acceptationNonPrevalidees: { numerateur: 1, denominateur: 2 },
  delaiReponseCollegue: {
    mesurees: 9,
    sansHorodatage: 1,
    medianeSecondes: 7200,
    centile90Secondes: 86400,
    moyenneSecondes: 21600,
  },
  delaiArbitrage: NOTHING_MEASURED,
  delaiCommunication: NOTHING_MEASURED,
  delaiAnnulation: NOTHING_MEASURED,
  parJourCreation: [
    { jour: '2026-06-10', nombre: 4 },
    { jour: '2026-06-11', nombre: 0 },
    { jour: '2026-06-12', nombre: 8 },
  ],
  parJourEvenement: [{ jour: '2026-07-11', nombre: 12 }],
  creneauRetire: 0,
  parStand: [{ standId: 'S1', standNom: 'Stand un', nombre: 12 }],
  contraintesViolees: [{ contrainte: 'Repos quotidien', nombre: 2 }],
};

const EMPTY: EchangeStatistics = {
  ...STATISTICS,
  creees: 0,
  parJourCreation: [],
  parJourEvenement: [],
  parStand: [],
  contraintesViolees: [],
};

describe('statistics-format', () => {
  it('says a small population as two counts, a larger one as a percentage, nothing as nothing', () => {
    expect(readRate({ numerateur: 0, denominateur: 0 })).toEqual({ kind: 'empty' });
    expect(readRate({ numerateur: 2, denominateur: 3 })).toEqual({
      kind: 'raw',
      numerator: 2,
      denominator: 3,
    });
    expect(readRate({ numerateur: 6, denominateur: 8 })).toEqual({
      kind: 'percent',
      percent: 75,
      numerator: 6,
      denominator: 8,
    });
  });

  it('words a delay in minutes, hours, then days', () => {
    expect(formatDelay(600)).toBe('10 min');
    expect(formatDelay(7200)).toBe('2 h');
    expect(formatDelay(3 * 86400)).toBe('3 j');
  });

  it('describes the period, the foire window apart', () => {
    const day = (iso: string) => iso;
    expect(describePeriod(STATISTICS, day)).toBe(
      'Fenêtre de la foire, du 2026-06-01 au 2026-06-30',
    );
    expect(describePeriod({ ...STATISTICS, fenetreFoire: false, au: null }, day)).toBe(
      'Demandes créées depuis le 2026-06-01',
    );
    expect(describePeriod({ ...STATISTICS, du: null, au: null }, day)).toBe("Toute l'édition");
  });

  it('draws the highest day full height and leaves an empty day without a bar', () => {
    const bars = histogramBars(STATISTICS.parJourCreation);
    expect(bars.map((bar) => bar.height)).toEqual([60, 0, 120]);
    expect(bars[2].y).toBe(0);
  });

  it('links a figure to the list of the same period, narrowed by its criteria', () => {
    expect(listLink(STATISTICS, { statuts: ['ACCEPTEE', 'REFUSEE'] })).toEqual({
      statuts: 'ACCEPTEE,REFUSEE',
      du: '2026-06-01',
      au: '2026-06-30',
    });
    expect(listLink({ ...STATISTICS, du: null, au: null }, { directed: true })).toEqual({
      dirigees: '1',
    });
  });

  it('carries a measure to the list for the server to apply', () => {
    expect(listLink({ ...STATISTICS, du: null, au: null }, { measure: 'delai-arbitrage' })).toEqual(
      { mesure: 'delai-arbitrage' },
    );
  });

  it('reads and writes the period of the tab, the foire window being the default', () => {
    expect(readStatisticsPeriod(null, '2026-06-01', null)).toEqual({ kind: 'foire' });
    expect(readStatisticsPeriod('dates', '2026-06-01', null)).toEqual({
      kind: 'dates',
      du: '2026-06-01',
      au: null,
    });
    expect(statisticsPeriodParams({ kind: 'edition' })).toEqual({
      periode: 'edition',
      du: null,
      au: null,
    });
  });
});

async function render(statistics: EchangeStatistics, period: StatisticsPeriod = { kind: 'foire' }) {
  const load = vi.fn<EchangesApi['statistics']>(async () => statistics);
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: EchangesApi, useValue: { statistics: load } },
    ],
  });
  const fixture = TestBed.createComponent(EchangeStatisticsTab);
  fixture.componentRef.setInput('period', period);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { root: fixture.nativeElement as HTMLElement, load };
}

function text(root: HTMLElement): string {
  return (root.textContent ?? '').replace(/\s+/g, ' ');
}

/** Each tile as one line, its parts separated as a reader hears them. */
function tiles(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('.echanges-stats-tuile')).map((tile) =>
    Array.from(tile.children)
      .map((part) => (part.textContent ?? '').replace(/\s+/g, ' ').trim())
      .join(' | '),
  );
}

describe('EchangeStatisticsTab', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('asks for the period it is given', async () => {
    const { load } = await render(STATISTICS, { kind: 'edition' });
    expect(load).toHaveBeenCalledWith({ kind: 'edition' });
  });

  it('shows volumes, rates and delays, a small population as « 1 sur 2 »', async () => {
    const { root } = await render(STATISTICS);

    expect(tiles(root)).toEqual([
      '12 | demande(s) créée(s) | dont 3 dirigée(s)',
      '2 | en attente du collègue',
      "1 | en attente de l'organisation",
      'Accord des collègues | 90 % | 9 sur 10',
      "Acceptation par l'organisation | 75 % | 6 sur 8",
      'Aboutissement | 67 % | 6 sur 9',
      "Prévalidées à l'envoi | 83 % | 10 sur 12",
      // Two non-prevalidated requests decided: two counts, no percentage.
      'Acceptation des non prévalidées | 1 sur 2',
      'Réponse du collègue | 2 h en médiane | 9 sur 10 en moins de 24 h | sur 9 demande(s) | 1 demande(s) sans horodatage',
      "Arbitrage de l'organisation | — | aucune mesure",
      'Communication de la décision | — | aucune mesure',
      'Annulation par le demandeur | — | aucune mesure',
    ]);
  });

  it('opens the list narrowed on what each figure counts', async () => {
    const { root } = await render(STATISTICS);
    const hrefs = Array.from(root.querySelectorAll('a')).map((link) =>
      decodeURIComponent(link.getAttribute('href') ?? ''),
    );

    expect(hrefs).toContain('/echanges?du=2026-06-01&au=2026-06-30');
    expect(hrefs).toContain('/echanges?dirigees=1&du=2026-06-01&au=2026-06-30');
    expect(hrefs).toContain('/echanges?statuts=ACCEPTEE,REFUSEE&du=2026-06-01&au=2026-06-30');
    expect(hrefs).toContain('/echanges?stand=S1&du=2026-06-01&au=2026-06-30');
    expect(hrefs).toContain(
      '/echanges?prevalidee=non&contrainte=Repos quotidien&du=2026-06-01&au=2026-06-30',
    );
    // A bar of the histogram: that creation day alone.
    expect(hrefs).toContain('/echanges?du=2026-06-12&au=2026-06-12');
  });

  it('opens exactly what a delay measured and what the colleague answered, through the measure', async () => {
    const { root } = await render(STATISTICS);
    const hrefs = Array.from(root.querySelectorAll('a')).map((link) =>
      decodeURIComponent(link.getAttribute('href') ?? ''),
    );

    // « sur 9 demande(s) »: the nine measured, not every request of the statuts.
    expect(hrefs).toContain('/echanges?mesure=delai-reponse&du=2026-06-01&au=2026-06-30');
    expect(hrefs).toContain('/echanges?mesure=accordees&du=2026-06-01&au=2026-06-30');
    expect(hrefs).toContain('/echanges?mesure=repondues&du=2026-06-01&au=2026-06-30');
    expect(hrefs.some((href) => href.includes('statuts=PROPOSEE,ACCEPTEE'))).toBe(false);
  });

  it('reads the statistics again when the page reloads it', async () => {
    const load = vi.fn<EchangesApi['statistics']>(async () => STATISTICS);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: EchangesApi, useValue: { statistics: load } },
      ],
    });
    const fixture = TestBed.createComponent(EchangeStatisticsTab);
    fixture.detectChanges();
    await fixture.whenStable();

    fixture.componentInstance.reload();
    await fixture.whenStable();

    expect(load).toHaveBeenCalledTimes(2);
  });

  it('keeps the mean of a delay for its tooltip', async () => {
    const { root } = await render(STATISTICS);
    const median = Array.from(root.querySelectorAll('.echanges-stats-valeur')).find((each) =>
      each.textContent?.includes('en médiane'),
    );
    expect(median?.getAttribute('tabindex')).toBe('0');
    expect(median?.classList).toContain('mat-mdc-tooltip-trigger');
  });

  it('says there is nothing yet rather than drawing tiles at zero', async () => {
    const { root } = await render(EMPTY);

    expect(text(root)).toContain('Pas encore de demande sur cette période.');
    expect(root.querySelector('.echanges-stats-tuile')).toBeNull();
  });
});
