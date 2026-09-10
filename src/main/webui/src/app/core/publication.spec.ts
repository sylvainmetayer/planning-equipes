import { describe, expect, it } from 'vitest';
import {
  libelleDernierePublication,
  libellePublier,
  raisonIndisponible,
  resumePublication,
} from './publication';
import type { ApercuPublication } from './models';

function apercu(patch: Partial<ApercuPublication> = {}): ApercuPublication {
  return {
    jamaisPublie: false,
    planVide: false,
    solveEnCours: false,
    dernierePublicationLe: '2026-08-25T12:30:00Z',
    nombreConcernes: 0,
    destinataires: [],
    ...patch,
  };
}

describe('libellePublier', () => {
  it('porte le décompte des personnes concernées', () => {
    expect(libellePublier(apercu({ nombreConcernes: 3 }))).toContain('3');
  });

  it('accorde au singulier', () => {
    expect(libellePublier(apercu({ nombreConcernes: 1 }))).toContain('1 personne concernée');
  });

  it('dit que tout le monde est à jour plutôt que de proposer un clic refusé', () => {
    expect(libellePublier(apercu({ nombreConcernes: 0 }))).toBe('Tout le monde est à jour');
  });

  it("ne suppose rien quand l'aperçu n'a pas pu être lu", () => {
    expect(libellePublier(null)).toBe('Tout le monde est à jour');
  });
});

describe('raisonIndisponible', () => {
  it('explique le refus pendant une résolution', () => {
    expect(raisonIndisponible(apercu({ solveEnCours: true, nombreConcernes: 4 }))).toContain(
      'résolution',
    );
  });

  it('explique un planning absent', () => {
    expect(raisonIndisponible(apercu({ planVide: true }))).toContain('Aucun planning');
  });

  it('se tait quand la publication est possible', () => {
    expect(raisonIndisponible(apercu({ nombreConcernes: 2 }))).toBe('');
  });

  it("donne la priorité au solve : c'est lui qui va tout réécrire", () => {
    expect(raisonIndisponible(apercu({ solveEnCours: true, planVide: true }))).toContain(
      'résolution',
    );
  });
});

describe('libelleDernierePublication', () => {
  it('date la dernière publication', () => {
    const libelle = libelleDernierePublication(apercu(), 'fr-FR');
    expect(libelle).toContain('Dernière publication le');
    expect(libelle).toContain('2026');
  });

  it("dit « jamais » plutôt que d'afficher une date vide", () => {
    expect(libelleDernierePublication(apercu({ dernierePublicationLe: null }), 'fr-FR')).toBe(
      'Jamais publié',
    );
    expect(libelleDernierePublication(null, 'fr-FR')).toBe('Jamais publié');
  });
});

describe('resumePublication', () => {
  it('résume un envoi sans incident en une ligne', () => {
    const resume = resumePublication({
      snapshotId: 7,
      publieLe: '2026-08-25T12:30:00Z',
      envoyes: 3,
      sansEmail: [],
      echecs: [],
    });

    expect(resume.titre).toContain('3');
    expect(resume.details).toBeUndefined();
  });

  it("nomme les manqués, parce que l'admin agit sur des noms", () => {
    const resume = resumePublication({
      snapshotId: 7,
      publieLe: '2026-08-25T12:30:00Z',
      envoyes: 1,
      sansEmail: ['Bruno Petit'],
      echecs: ['Chloé Durand'],
    });

    expect(resume.details).toContain('Bruno Petit');
    expect(resume.details).toContain('Chloé Durand');
  });
});
