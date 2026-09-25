// The wiring of « Comparer »: what differs is `comparaison-ouvertures.ts`'s
// call and tested there; here, the selection moves, the filter, and the copy
// handing over to the bulk edit without writing anything itself.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ConsignesStore } from '../../core/consignes.store';
import { RapportOuvertures, Stand } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ComparaisonOuverturesVue } from './comparaison-vue';

function rapport(): RapportOuvertures {
  const ligne = (standId: string, soir: number | null) => ({
    standId,
    nom: `Buvette ${standId}`,
    effectifMin: 1,
    jours: ['2026-07-08', '2026-07-09'].map((date, index) => ({
      date,
      etat: 'OUVERT_TOTAL' as const,
      source: 'REGLE' as const,
      fenetres: [],
      minutesOuvertes: 0,
      minutesAmplitude: 0,
      postes: 0,
      creneaux: [
        {
          creneauId: index + 1,
          tranche: 0,
          effectif: index === 0 ? 2 : soir,
          partiel: false,
          segments: [],
        },
      ],
    })),
    minutesOuvertes: 0,
    postes: 0,
    modifieLe: null,
  });
  return {
    jours: ['2026-07-08', '2026-07-09'].map((date, index) => ({
      date,
      jour: index + 1,
      heureDebut: '18:00',
      heureFin: '22:00',
      minutes: 240,
      nombreCreneaux: 1,
      creneaux: [
        {
          id: index + 1,
          tranche: 0,
          heureDebut: '18:00',
          heureFin: '22:00',
          couverturePause: false,
        },
      ],
    })),
    stands: [ligne('1', 3), ligne('2', 3), ligne('3', 2)],
    standsJamaisOuverts: 0,
    postesTotal: 0,
    anomalies: [],
  };
}

function stand(id: string, typologies: string[] = []): Stand {
  return {
    id,
    nom: `Buvette ${id}`,
    typologiesProposees: typologies,
    effectifMin: 1,
    effectifMax: 3,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

async function mount(standIds: string[], referenceId: string | null = null) {
  const open = vi.fn(() => ({ afterClosed: () => of(true) }));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      {
        provide: ReferenceDataStore,
        useValue: {
          stands: signal([
            stand('1', ['buvette']),
            stand('2', ['buvette']),
            stand('3', ['buvette']),
          ]),
          typologies: signal([{ id: 'buvette', label: 'Buvette' }]),
        },
      },
      { provide: ConsignesStore, useValue: { consigneOf: () => null } },
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: MatDialog, useValue: { open } },
    ],
  });
  const fixture = TestBed.createComponent(ComparaisonOuverturesVue);
  fixture.componentRef.setInput('rapport', rapport());
  fixture.componentRef.setInput('standIds', standIds);
  fixture.componentRef.setInput('referenceId', referenceId);
  await fixture.whenStable();
  return { fixture, open };
}

function root(fixture: ComponentFixture<ComparaisonOuverturesVue>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

describe('ComparaisonOuverturesVue', () => {
  it('asks for two stands before comparing anything', async () => {
    const { fixture } = await mount(['1']);
    expect(root(fixture).querySelector('[data-test="comparaison-vide"]')).not.toBeNull();
    expect(root(fixture).querySelector('[data-test="comparaison-jour"]')).toBeNull();
  });

  it('frames a differing cell and names the difference in words', async () => {
    const { fixture } = await mount(['1', '3']);
    const ecart = root(fixture).querySelector('.comparaison-case-ecart')!;
    expect(ecart.textContent).toContain('effectif');
    expect(ecart.textContent).toContain('effectif 2 au lieu de 3');
    expect(
      root(fixture).querySelector('[data-test="comparaison-synthese"]')!.textContent,
    ).toContain('diffère sur 1 jour(s)');
  });

  it('hides the identical days on demand', async () => {
    const { fixture } = await mount(['1', '3']);
    expect(root(fixture).querySelectorAll('[data-test="comparaison-jour"]')).toHaveLength(2);

    fixture.componentInstance.ecartsSeulement.set(true);
    await fixture.whenStable();

    const jours = root(fixture).querySelectorAll('[data-test="comparaison-jour"]');
    expect(jours).toHaveLength(1);
    expect(jours[0].querySelector('caption')!.textContent).toContain('09/07');
  });

  it('hands the reference to the next stand when it is removed', async () => {
    const { fixture } = await mount(['1', '2', '3'], '2');
    (fixture.componentInstance as unknown as { retirer(id: string): void }).retirer('2');
    await fixture.whenStable();

    expect(fixture.componentInstance.standIds()).toEqual(['1', '3']);
    expect(fixture.componentInstance.referenceId()).toBe('3');
  });

  it('adds every stand of a game category at once', async () => {
    const { fixture } = await mount([]);
    (
      fixture.componentInstance as unknown as { ajouterTypologie(id: string): void }
    ).ajouterTypologie('buvette');
    await fixture.whenStable();
    expect(fixture.componentInstance.standIds()).toEqual(['1', '2', '3']);
  });

  it('opens the bulk edit preset on the reference, and writes nothing itself', async () => {
    const { fixture, open } = await mount(['1', '2', '3'], '2');
    const emis = vi.fn();
    fixture.componentInstance.modifie.subscribe(emis);

    (root(fixture).querySelector('[data-test="comparaison-copier"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(open).toHaveBeenCalledTimes(1);
    const [, config] = open.mock.calls[0] as unknown as [
      unknown,
      { data: { stands: Stand[]; modele: Stand } },
    ];
    expect(config.data.modele.id).toBe('2');
    expect(config.data.stands.map((each) => each.id)).toEqual(['1', '3']);
    // Saved in the dialog: the page is asked to re-read the report.
    expect(emis).toHaveBeenCalledTimes(1);
  });
});
