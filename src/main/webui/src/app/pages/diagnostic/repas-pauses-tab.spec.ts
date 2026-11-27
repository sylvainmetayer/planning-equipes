// The Repas et pauses tab: the day it opens on, the one the address names and
// the walks of that day. The three reports are stubbed; their own specs cover them.

import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { WalkSequenceReport } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { RepasPausesTab } from './repas-pauses-tab';

const WALKS: WalkSequenceReport = {
  walkingSpeedKmH: 4,
  detourFactor: 1.3,
  toleranceMinutes: 0,
  geolocated: true,
  walks: [
    {
      animateurId: 'alice',
      date: '2026-07-10',
      fromPosteId: 'a',
      toPosteId: 'b',
      fromStandId: 'TIR',
      toStandId: 'JEUX',
      end: '12:00:00',
      start: '12:05:00',
      distanceMetres: 500,
      walkMinutes: 7,
      gapMinutes: 5,
      missingMinutes: 2,
      walkOnBreak: false,
    },
    {
      animateurId: 'alice',
      date: '2026-07-11',
      fromPosteId: 'c',
      toPosteId: 'd',
      fromStandId: 'JEUX',
      toStandId: 'TIR',
      end: '14:00:00',
      start: '14:05:00',
      distanceMetres: 500,
      walkMinutes: 4,
      gapMinutes: 5,
      missingMinutes: 0,
      walkOnBreak: false,
    },
  ],
};

async function monter(
  path: string,
  walks: WalkSequenceReport = WALKS,
): Promise<ComponentFixture<RepasPausesTab>> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      provideLocationMocks(),
      {
        provide: AnalysesApi,
        useValue: {
          breaks: () => Promise.resolve(null),
          walks: () => Promise.resolve(walks),
          intendance: () => Promise.resolve(null),
        },
      },
      {
        provide: ReferenceDataStore,
        useValue: {
          animateurs: () => [{ id: 'alice', prenom: 'Alice', nom: 'Martin' }],
          stands: () => [
            { id: 'TIR', nom: 'Tir à l’arc' },
            { id: 'JEUX', nom: 'Village des jeux' },
          ],
        },
      },
    ],
  });
  TestBed.inject(Location).replaceState(path);
  const fixture = TestBed.createComponent(RepasPausesTab);
  await fixture.whenStable();
  return fixture;
}

describe('RepasPausesTab', () => {
  it('opens on the first day, and names stands and people in the walks', async () => {
    const fixture = await monter('/diagnostic?onglet=repas-pauses');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(fixture.componentInstance['jour']()).toBe('2026-07-10');
    expect(text).toContain('Tir à l’arc → Village des jeux');
    expect(text).toContain('Alice Martin');
    expect(text).toContain('2 min');
  });

  it('follows the day the address names, and writes it back', async () => {
    const fixture = await monter('/diagnostic?onglet=repas-pauses&jour=2026-07-11');
    const tab = fixture.componentInstance;

    expect(tab['jour']()).toBe('2026-07-11');
    expect(tab['enchainements']()).toHaveLength(1);

    tab['choisir']('2026-07-10');
    TestBed.tick();
    await fixture.whenStable();
    expect(TestBed.inject(Location).path()).toContain('jour=2026-07-10');
  });

  it('falls back on the first day when the one asked for is unknown', async () => {
    const fixture = await monter('/diagnostic?onglet=repas-pauses&jour=2030-01-01');
    expect(fixture.componentInstance['jour']()).toBe('2026-07-10');
  });

  it('says so when no stand is geolocated', async () => {
    const fixture = await monter('/diagnostic?onglet=repas-pauses', {
      ...WALKS,
      geolocated: false,
      walks: [],
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('géolocalisé');
  });

  it('links to the placement setting and to the rule', async () => {
    const fixture = await monter('/diagnostic?onglet=repas-pauses');
    const racine = fixture.nativeElement as HTMLElement;
    expect(racine.querySelector('a[href="/parametres?onglet=organisation"]')).not.toBeNull();
    expect(racine.querySelector('a[href="/regles?regle=coupureRepasObligatoire"]')).not.toBeNull();
  });
});
