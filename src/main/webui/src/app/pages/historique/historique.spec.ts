import { describe, expect, it } from 'vitest';
import { EntreeHistorique } from '../../core/models';
import {
  entitesPresentes,
  filtrer,
  lireFiltreActeur,
  lireFiltreResultat,
  parJournee,
  qui,
  surQuoi
} from './historique';

function entree(partial: Partial<EntreeHistorique> = {}): EntreeHistorique {
  return {
    id: 1,
    survenuLe: '2026-09-07T14:32:00Z',
    acteur: 'ADMIN',
    acteurId: 'admin',
    acteurNom: null,
    action: 'ANIMATEUR_MODIFIE',
    libelle: 'Fiche animateur modifiée',
    entite: 'ANIMATEUR',
    entiteId: 'A1',
    entiteNom: 'Alice Martin',
    champs: ['nom', 'email'],
    resultat: 'SUCCES',
    statut: 200,
    ...partial
  };
}

describe('filtrer', () => {
  it('garde tout par défaut', () => {
    const entrees = [entree(), entree({ id: 2, acteur: 'ANIMATEUR' })];
    expect(filtrer(entrees, 'TOUS', 'TOUS', '', '')).toHaveLength(2);
  });

  it('filtre par auteur, par résultat et par objet', () => {
    const entrees = [
      entree(),
      entree({ id: 2, acteur: 'ASSISTANT', acteurId: 'mcp' }),
      entree({ id: 3, resultat: 'REFUS', statut: 409 }),
      entree({ id: 4, entite: 'STAND', entiteId: 'S1', entiteNom: null })
    ];
    expect(filtrer(entrees, 'ASSISTANT', 'TOUS', '', '').map((e) => e.id)).toEqual([2]);
    expect(filtrer(entrees, 'TOUS', 'REFUS', '', '').map((e) => e.id)).toEqual([3]);
    expect(filtrer(entrees, 'TOUS', 'TOUS', 'STAND', '').map((e) => e.id)).toEqual([4]);
  });

  /** The search covers what the row shows — and only that, or it would look broken. */
  it('cherche dans la phrase, la personne, l’objet et les champs', () => {
    const entrees = [entree(), entree({ id: 2, libelle: 'Stand ajouté', entite: 'STAND', entiteId: 'S1', entiteNom: null, champs: [] })];
    expect(filtrer(entrees, 'TOUS', 'TOUS', '', 'alice').map((e) => e.id)).toEqual([1]);
    expect(filtrer(entrees, 'TOUS', 'TOUS', '', 'email').map((e) => e.id)).toEqual([1]);
    expect(filtrer(entrees, 'TOUS', 'TOUS', '', 'stand').map((e) => e.id)).toEqual([2]);
    // Accent- and case-insensitive, like every other quick filter here.
    expect(filtrer(entrees, 'TOUS', 'TOUS', '', 'MODIFIEE').map((e) => e.id)).toEqual([1]);
  });
});

describe('qui', () => {
  it('nomme l’animateur encore au référentiel, et son identifiant sinon', () => {
    expect(qui(entree({ acteur: 'ANIMATEUR', acteurId: 'A1', acteurNom: 'Alice Martin' }))).toBe('Alice Martin');
    expect(qui(entree({ acteur: 'ANIMATEUR', acteurId: 'A1', acteurNom: null }))).toBe('A1');
  });

  it('nomme les trois autres auteurs sans inventer d’identité', () => {
    expect(qui(entree({ acteur: 'SYSTEME', acteurId: null }))).toBe('Application');
    expect(qui(entree({ acteur: 'ASSISTANT', acteurId: 'mcp' }))).toBe('Assistant (MCP)');
    expect(qui(entree({ acteur: 'ADMIN', acteurId: 'admin' }))).toBe('admin');
  });
});

describe('surQuoi', () => {
  it('montre le nom quand il est connu, l’identifiant seul sinon', () => {
    expect(surQuoi(entree())).toBe('Alice Martin (A1)');
    expect(surQuoi(entree({ entiteNom: null }))).toBe('A1');
    expect(surQuoi(entree({ entiteId: null, entiteNom: null }))).toBe('');
  });
});

describe('parJournee', () => {
  it('regroupe par jour civil dans l’ordre reçu', () => {
    const journees = parJournee([
      entree({ id: 1, survenuLe: '2026-09-07T14:32:00Z' }),
      entree({ id: 2, survenuLe: '2026-09-07T09:05:00Z' }),
      entree({ id: 3, survenuLe: '2026-09-06T18:00:00Z' })
    ]);
    expect(journees.map((j) => j.jour)).toEqual(['2026-09-07', '2026-09-06']);
    expect(journees[0].entrees.map((e) => e.id)).toEqual([1, 2]);
  });
});

describe('lecture des filtres depuis l’URL', () => {
  it('retombe sur le défaut pour une valeur inconnue', () => {
    expect(lireFiltreActeur('ASSISTANT')).toBe('ASSISTANT');
    expect(lireFiltreActeur('inventé')).toBe('TOUS');
    expect(lireFiltreActeur(null)).toBe('TOUS');
    expect(lireFiltreResultat('REFUS')).toBe('REFUS');
    expect(lireFiltreResultat('peut-être')).toBe('TOUS');
  });
});

describe('entitesPresentes', () => {
  it('ne propose que les objets réellement présents, triés', () => {
    const entrees = [entree({ entite: 'STAND' }), entree({ id: 2 }), entree({ id: 3, entite: null })];
    expect(entitesPresentes(entrees)).toEqual(['ANIMATEUR', 'STAND']);
  });
});
