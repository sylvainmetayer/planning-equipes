// The panel under « des données de référence ont été modifiées » : it says
// how much moved, of what kind, and shows the last lines. `changements.spec.ts`
// pins the wording; what is checked here is that it asks the history from the
// moment the plan was solved, and shows nothing when nothing moved.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnalysesApi } from '../../core/api/analyses-api';
import { ChangementsDonnees, EntreeHistorique } from '../../core/models';
import { ChangementsDonneesPanel } from './changements-donnees';

function entree(partial: Partial<EntreeHistorique> & { id: number }): EntreeHistorique {
  return {
    survenuLe: '2026-09-12T12:32:00Z',
    acteur: 'ADMIN',
    acteurId: null,
    acteurNom: null,
    action: 'ANIMATEUR_MODIFIE',
    libelle: 'Fiche animateur modifiée',
    entite: 'ANIMATEUR',
    entiteId: null,
    entiteNom: null,
    champs: [],
    resultat: 'SUCCES',
    statut: 200,
    ...partial,
  };
}

describe('ChangementsDonneesPanel', () => {
  const analysesApi = { changesSince: vi.fn() };
  let fixture: ComponentFixture<ChangementsDonneesPanel>;

  beforeEach(() => {
    analysesApi.changesSince.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnalysesApi, useValue: analysesApi },
      ],
    });
  });

  async function monter(changements: ChangementsDonnees): Promise<string> {
    analysesApi.changesSince.mockResolvedValue(changements);
    fixture = TestBed.createComponent(ChangementsDonneesPanel);
    fixture.componentRef.setInput('depuis', '2026-09-12T10:00:00Z');
    await fixture.whenStable();
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  it('counts what moved per family and lists the most recent lines', async () => {
    const rendu = await monter({
      total: 4,
      parEntite: [
        { entite: 'ANIMATEUR', nombre: 3 },
        { entite: 'STAND', nombre: 1 },
      ],
      dernieres: [
        entree({ id: 2, entiteId: 'A1', entiteNom: 'Alice Martin' }),
        entree({
          id: 1,
          action: 'STAND_CREE',
          libelle: 'Stand ajouté',
          entite: 'STAND',
          entiteId: 'loup-garou',
        }),
      ],
    });

    expect(analysesApi.changesSince).toHaveBeenCalledWith('2026-09-12T10:00:00Z');
    expect(rendu).toContain('3 animateurs, 1 stand');
    expect(rendu).toContain('Fiche animateur modifiée');
    expect(rendu).toContain('Alice Martin (A1)');
    expect(rendu).toContain('Stand ajouté');
    // Four changes, two lines shown: the rest is said rather than hidden.
    expect(rendu).toContain('2');
    expect(rendu).toContain("Voir l'historique");
  });

  it('shows nothing at all when the history reports no change', async () => {
    const rendu = await monter({ total: 0, parEntite: [], dernieres: [] });

    expect(rendu.trim()).toBe('');
  });

  it("says the history could not be read rather than passing for « rien n'a changé »", async () => {
    analysesApi.changesSince.mockRejectedValue(new Error('Serveur indisponible.'));
    fixture = TestBed.createComponent(ChangementsDonneesPanel);
    fixture.componentRef.setInput('depuis', '2026-09-12T10:00:00Z');
    await vi.waitFor(() => expect(analysesApi.changesSince).toHaveBeenCalled());
    fixture.detectChanges();

    const rendu = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(rendu).toContain('Serveur indisponible.');
    expect(rendu).not.toContain("Voir l'historique");
  });
});
