// The fiche page over its one call: the seven sections for an assigned
// animateur, the dependent sections announcing an empty plan, and an unknown
// id answered with a sentence rather than a blank card.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AnimateursApi } from '../../core/api/animateurs-api';
import { ApiError } from '../../core/api.service';
import { AnimateurProfile } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { AnimateurFichePage } from './animateur-fiche-page';

function profile(partial: Partial<AnimateurProfile> = {}): AnimateurProfile {
  return {
    animateur: {
      id: 'a1',
      prenom: 'Camille',
      nom: 'Durand',
      dateNaissance: '1990-01-01',
      manager: false,
      competences: { JEU: 'REFERENT' },
      souhaits: ['CUBE'],
      joursIndisponibles: ['2026-07-12'],
      email: null,
      accessToken: 'tok',
    },
    joursEvenement: ['2026-07-11', '2026-07-12'],
    regimeDebut: { date: '2026-07-11', age: 36, regime: 'MAJEUR' },
    regimeFin: { date: '2026-07-12', age: 36, regime: 'MAJEUR' },
    planCalcule: true,
    equite: {
      heureDebutSoiree: '20:00:00',
      semaines: [],
      lignes: [
        {
          animateurId: 'a1',
          nom: 'Camille Durand',
          heuresTotal: 6,
          heuresParSemaine: {},
          heuresSoiree: 2,
          heuresWeekEnd: 6,
          heuresJourFerie: 0,
          postes: 2,
          postesPenibles: 0,
          standsDistincts: 1,
          typologiesDistinctes: 1,
          emplacementsDistinctsParJourMax: 1,
          tauxSouhaits: 0.5,
          tauxAppreciation: 1,
          joursTravailles: 1,
          joursRepos: 1,
          plusLongueSerie: 1,
        },
      ],
      syntheses: { heuresTotal: { mediane: 4, min: 2, max: 6, ecartType: 1 } },
      colonnesSolveur: [],
    },
    fragilite: {
      animateurId: 'a1',
      nom: 'Camille Durand',
      ninja: false,
      affectations: 2,
      postesEffondres: 1,
      postesIrremplacables: 1,
      competencesRares: 0,
      severite: 'CRITIQUE',
      postes: [
        {
          standId: 's1',
          standNom: 'Échecs',
          creneauId: 1,
          date: '2026-07-11',
          jour: 1,
          heureDebut: '14:00:00',
          heureFin: '18:00:00',
          effectifMin: 2,
          couverturePause: false,
          siegesRequis: 2,
          siegesPourvus: 2,
          siegesLiberes: 1,
          remplacants: 0,
          irremplacable: true,
        },
      ],
      postesNonDetailles: 0,
    },
    competencesRares: [],
    affectations: [
      {
        posteId: 'p1',
        standId: 's1',
        standNom: 'Échecs',
        creneauId: 1,
        date: '2026-07-11',
        heureDebut: '14:00:00',
        heureFin: '18:00:00',
        emplacementId: 'e1',
        emplacementNom: 'Grande salle',
        passe: true,
        verrouille: false,
      },
    ],
    confirmation: null,
    dernierePublicationLe: null,
    echangesEnCours: [],
    ajustements: [],
    verrous: [],
    declarationEnAttente: null,
    ...partial,
  };
}

describe('AnimateurFichePage', () => {
  const api = { profile: vi.fn() };
  let fixture: ComponentFixture<AnimateurFichePage>;

  beforeEach(() => {
    api.profile.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: AnimateursApi, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: {
            paramMap: of(convertToParamMap({ id: 'a1' })),
            snapshot: { paramMap: convertToParamMap({ id: 'a1' }) },
          },
        },
        {
          provide: ReferenceDataStore,
          useValue: {
            typologies: signal([
              { id: 'JEU', label: 'Jeux' },
              { id: 'CUBE', label: 'Casse-tête' },
            ]),
            reload: vi.fn(async () => undefined),
          },
        },
        { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      ],
    });
  });

  async function render(): Promise<string> {
    fixture = TestBed.createComponent(AnimateurFichePage);
    fixture.detectChanges();
    await vi.waitFor(() => {
      fixture.detectChanges();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text.includes('Identité et régime') || text.includes('Aucun animateur')).toBe(true);
    });
    return ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
  }

  it('shows the seven sections of an assigned animateur', async () => {
    api.profile.mockResolvedValue(profile());
    const text = await render();

    expect(api.profile).toHaveBeenCalledWith('a1');
    for (const section of [
      'Identité et régime',
      'Disponibilités',
      'Compétences et souhaits',
      'Équité',
      'Fragilité',
      'Affectations',
      'Suivi',
    ]) {
      expect(text).toContain(section);
    }
    expect(text).toContain('Camille Durand');
    expect(text).toContain('souhaitée sans être appréciée');
    expect(text).toContain('+2');
    expect(text).toContain('Grande salle');
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('tr.fiche-passe')).not.toBeNull();
    expect(element.querySelector('a[href="/timeline?animateur=a1"]')).not.toBeNull();
  });

  it('says so in the dependent sections when no plan was computed', async () => {
    api.profile.mockResolvedValue(
      profile({
        planCalcule: false,
        equite: { ...profile().equite, lignes: [] },
        fragilite: null,
        affectations: [],
      }),
    );
    const text = await render();

    expect(text.match(/Aucun planning calculé/g)?.length).toBe(3);
  });

  it('answers an unknown id with a sentence, not an empty page', async () => {
    api.profile.mockRejectedValue(new ApiError(404, 'notFound', 'Animateur inconnu : a1'));
    const text = await render();

    expect(text).toContain("Aucun animateur ne porte l'identifiant « a1 »");
    expect(text).not.toContain('Identité et régime');
  });
});
