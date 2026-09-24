// The « à arbitrer » view of the swap requests screen: what the home screen's
// « À traiter aujourd'hui » counts and links to (`?statut=a-arbitrer`) —
// the requests only the admin's word is missing from, the longest waiting
// first, and nothing else.

import { Location } from '@angular/common';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EchangesApi } from '../../core/api/echanges-api';
import { DemandeEchangeView } from '../../core/models';
import { EchangesPage } from './echanges-page';
import { oldestWaitingFirst, readToArbitrate, waitingSince } from './echanges-filter';

function demande(
  id: string,
  statut: string,
  creeLe: string,
  cibleDecideLe: string | null = null,
): DemandeEchangeView {
  return {
    id,
    statut,
    creeLe,
    cibleDecideLe,
    decideLe: null,
    communiqueeLe: null,
    demandeurNom: `Demandeur ${id}`,
    cibleNom: `Cible ${id}`,
    date: '2026-07-10',
    heureDebut: '10:00',
    heureFin: '12:00',
    standNom: 'Stand',
  } as unknown as DemandeEchangeView;
}

// Agreed by the colleague on the 3rd, although created before the other one.
const RECENTE = demande('recente', 'PROPOSEE', '2026-07-01T08:00:00Z', '2026-07-03T08:00:00Z');
const ANCIENNE = demande('ancienne', 'PROPOSEE', '2026-07-02T08:00:00Z');
const WITH_COLLEAGUE = demande('cible', 'EN_ATTENTE_CIBLE', '2026-06-01T08:00:00Z');
const DECIDEE = demande('decidee', 'ACCEPTEE', '2026-06-01T08:00:00Z');

describe('echanges-filter', () => {
  it('reads `statut=a-arbitrer` and nothing else', () => {
    expect(readToArbitrate('a-arbitrer')).toBe(true);
    expect(readToArbitrate(null)).toBe(false);
    expect(readToArbitrate('toutes')).toBe(false);
  });

  it("dates a request from the colleague's agreement, its creation without one", () => {
    expect(waitingSince(RECENTE)).toBe('2026-07-03T08:00:00Z');
    expect(waitingSince(ANCIENNE)).toBe('2026-07-02T08:00:00Z');
  });

  it('puts the request waiting the longest first', () => {
    expect(oldestWaitingFirst([RECENTE, ANCIENNE]).map((each) => each.id)).toEqual([
      'ancienne',
      'recente',
    ]);
  });
});

function setUp(queryParams: Record<string, string>) {
  const replaceState = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: EchangesApi,
        useValue: {
          list: vi.fn(async () => [RECENTE, ANCIENNE, WITH_COLLEAGUE, DECIDEE]),
          configuration: vi.fn(async () => ({
            foireOuverte: true,
            ouverteAujourdhui: true,
            debut: null,
            fin: null,
          })),
        },
      },
      { provide: MatDialog, useValue: { open: vi.fn() } },
      { provide: Location, useValue: { path: () => '/echanges', replaceState } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(EchangesPage);
  return { fixture, replaceState };
}

function cards(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll('.echanges-demande strong:first-of-type')).map(
    (each) => each.textContent?.trim() ?? '',
  );
}

describe('EchangesPage « à arbitrer »', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('opens on the requests to arbitrate only, the longest waiting first', async () => {
    const { fixture } = setUp({ statut: 'a-arbitrer' });
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(cards(root)).toEqual(['Demandeur ancienne', 'Demandeur recente']);
    expect(root.textContent).not.toContain("En attente de l'accord du collègue");
    expect(root.textContent).not.toContain('Décidées');
  });

  it('shows every section without the param, and writes the filter back to the URL when ticked', async () => {
    const { fixture, replaceState } = setUp({});
    await fixture.whenStable();

    const root = fixture.nativeElement as HTMLElement;
    expect(cards(root)).toHaveLength(4);
    expect(replaceState).toHaveBeenLastCalledWith('/echanges');

    (
      fixture.componentInstance as unknown as { toArbitrateOnly: { set(v: boolean): void } }
    ).toArbitrateOnly.set(true);
    await fixture.whenStable();
    expect(replaceState).toHaveBeenLastCalledWith('/echanges?statut=a-arbitrer');
    expect(cards(root)).toHaveLength(2);
  });
});
