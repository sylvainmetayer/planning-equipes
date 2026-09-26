// The grid of the fiche stand saves what the Horaires des stands grid saves: the same
// body — every cell of the stand, its stamp as the precondition — for the
// same cells typed. That is the non-regression the two entry points share.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { StandsApi } from '../../core/api/stands-api';
import { RapportOuvertures } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import {
  cellulesDepuis,
  colonnes,
  ecrireCellule,
  recopierJour,
  saisie,
} from '../ouvertures/grille-horaires';
import { StandGridEditor } from './stand-grid-editor';

/** Two days: 10-12 and 14-20, and a nocturne 20-00 on the first only. */
function rapport(): RapportOuvertures {
  const jour = (date: string, index: number, ids: number[]) => ({
    date,
    jour: index,
    heureDebut: '10:00',
    heureFin: '20:00',
    minutes: 480,
    nombreCreneaux: ids.length,
    ferie: null,
    creneaux: ids.map((id, rang) => ({
      id,
      tranche: 0,
      heureDebut: ['10:00:00', '14:00:00', '20:00:00'][rang],
      heureFin: ['12:00:00', '20:00:00', '00:00:00'][rang],
      couverturePause: false,
    })),
  });
  const cellule = (creneauId: number, effectif: number | null) => ({
    creneauId,
    tranche: 0,
    effectif,
    partiel: false,
    segments: [],
  });
  const jourStand = (date: string, creneaux: ReturnType<typeof cellule>[]) => ({
    date,
    etat: 'OUVERT_TOTAL' as const,
    source: 'REGLE' as const,
    fenetres: [],
    minutesOuvertes: 0,
    minutesAmplitude: 0,
    postes: 0,
    creneaux,
  });
  return {
    jours: [jour('2026-07-11', 1, [1, 2, 3]), jour('2026-07-12', 2, [4, 5])],
    stands: [
      {
        standId: 'S1',
        nom: 'Stand 1',
        effectifMin: 2,
        jours: [
          jourStand('2026-07-11', [cellule(1, 2), cellule(2, 4), cellule(3, null)]),
          jourStand('2026-07-12', [cellule(4, 3), cellule(5, 4)]),
        ],
        minutesOuvertes: 0,
        postes: 0,
        modifieLe: '2026-09-06T10:00:00Z',
      },
    ],
    standsJamaisOuverts: 0,
    postesTotal: 0,
    anomalies: [],
  };
}

describe('StandGridEditor', () => {
  const standsApi = { saveOpeningsGrid: vi.fn() };
  let fixture: ComponentFixture<StandGridEditor>;

  beforeEach(async () => {
    standsApi.saveOpeningsGrid.mockReset();
    standsApi.saveOpeningsGrid.mockResolvedValue({
      stands: [
        { standId: 'S1', regles: 1, exceptions: 0, effectifMin: 2, effectifMax: 4, compacte: true },
      ],
    });
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: StandsApi, useValue: standsApi },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      ],
    });
    fixture = TestBed.createComponent(StandGridEditor);
    fixture.componentRef.setInput('standId', 'S1');
    fixture.componentRef.setInput('rapport', rapport());
    await fixture.whenStable();
  });

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function button(label: string): HTMLButtonElement {
    return Array.from(root().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(label),
    ) as HTMLButtonElement;
  }

  it('lays the stand day by day, a window a day does not have left empty, and sums each day up', () => {
    const lignes = Array.from(root().querySelectorAll('tbody tr'));
    expect(lignes).toHaveLength(2);
    expect(lignes[0].querySelectorAll('input.stand-grille-case')).toHaveLength(3);
    expect(lignes[1].querySelectorAll('input.stand-grille-case')).toHaveLength(2);
    expect(lignes[0].querySelector('.stand-grille-apercu')!.textContent).toContain(
      '10:00–12:00 ×2, 14:00–20:00 ×4',
    );
    expect(button('Enregistrer').disabled).toBe(true);
  });

  it('saves exactly what the Horaires des stands grid saves for the same cell typed', async () => {
    const saisi = root().querySelectorAll<HTMLInputElement>('tbody tr')[1];
    const champ = saisi.querySelectorAll<HTMLInputElement>('input.stand-grille-case')[1];
    champ.value = '3';
    champ.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(root().textContent).toContain('modifié, non enregistré');

    button('Enregistrer').click();
    await fixture.whenStable();

    const reference = rapport();
    const cols = colonnes(reference);
    const attendu = saisie(
      ecrireCellule(cellulesDepuis(reference), { standId: 'S1', colonneId: cols[4].colonneId }, 3),
      ['S1'],
      cols,
      { modifieLeParStand: new Map([['S1', '2026-09-06T10:00:00Z']]) },
    );
    expect(standsApi.saveOpeningsGrid).toHaveBeenCalledExactlyOnceWith(attendu);
  });

  it('copies a day onto the others with the grid’s own move, nothing written before « Enregistrer »', async () => {
    button('Recopier ce jour').click();
    await fixture.whenStable();
    button('Enregistrer').click();
    await fixture.whenStable();

    const reference = rapport();
    const cols = colonnes(reference);
    const attendu = saisie(
      recopierJour(cellulesDepuis(reference), '2026-07-11', ['S1'], cols),
      ['S1'],
      cols,
      { modifieLeParStand: new Map([['S1', '2026-09-06T10:00:00Z']]) },
    );
    // The second day's 10-12 went from 3 to 2: the body is the grid's,
    // whichever entry point made the move.
    expect(standsApi.saveOpeningsGrid).toHaveBeenCalledExactlyOnceWith(attendu);
  });
});
