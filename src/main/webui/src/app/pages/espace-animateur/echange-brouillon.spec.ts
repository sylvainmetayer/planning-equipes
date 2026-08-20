import { describe, expect, it } from 'vitest';
import { PosteAnimateurView } from '../../core/models';
import {
  BrouillonDemande,
  ajouterBrouillon,
  brouillonComplet,
  retirerBrouillon,
  versNouvellesDemandes
} from './echange-brouillon';

function poste(creneauId: number, standId: string): PosteAnimateurView {
  return {
    creneauId,
    date: '2026-07-10',
    heureDebut: '10:00',
    heureFin: '12:00',
    standId,
    standNom: standId,
    coequipiers: []
  };
}

function brouillon(creneauId: number, standId: string, cibleId = 'a2', motif: string | null = null): BrouillonDemande {
  return {
    creneauId,
    standId,
    cibleId,
    motif,
    creneauLabel: '10:00–12:00',
    standNom: standId,
    cibleNom: 'Alice Blot'
  };
}

describe('brouillonComplet', () => {
  it('exige un poste et un collègue, le motif reste optionnel', () => {
    expect(brouillonComplet(null, 'a2')).toBe(false);
    expect(brouillonComplet(poste(1, 'S1'), '')).toBe(false);
    expect(brouillonComplet(poste(1, 'S1'), '  ')).toBe(false);
    expect(brouillonComplet(poste(1, 'S1'), 'a2')).toBe(true);
  });
});

describe('ajouterBrouillon', () => {
  it('ajoute en fin de liste', () => {
    const liste = ajouterBrouillon([brouillon(1, 'S1')], brouillon(2, 'S2'));
    expect(liste).toHaveLength(2);
    expect(liste[1].creneauId).toBe(2);
  });

  it('remplace un brouillon qui cède déjà le même poste', () => {
    const liste = ajouterBrouillon([brouillon(1, 'S1', 'a2')], brouillon(1, 'S1', 'a3'));
    expect(liste).toHaveLength(1);
    expect(liste[0].cibleId).toBe('a3');
  });

  it('distingue deux stands sur le même créneau', () => {
    const liste = ajouterBrouillon([brouillon(1, 'S1')], brouillon(1, 'S2'));
    expect(liste).toHaveLength(2);
  });
});

describe('retirerBrouillon', () => {
  it('retire la ligne visée sans toucher les autres', () => {
    const liste = retirerBrouillon([brouillon(1, 'S1'), brouillon(2, 'S2')], 0);
    expect(liste).toHaveLength(1);
    expect(liste[0].creneauId).toBe(2);
  });
});

describe('versNouvellesDemandes', () => {
  it('ne transmet que les champs du backend et normalise le motif', () => {
    const demandes = versNouvellesDemandes([brouillon(1, 'S1', 'a2', '  repos  '), brouillon(2, 'S2', 'a3', '   ')]);
    expect(demandes).toEqual([
      { creneauId: 1, standId: 'S1', cibleId: 'a2', motif: 'repos', creneauCibleId: null, standCibleId: null },
      { creneauId: 2, standId: 'S2', cibleId: 'a3', motif: null, creneauCibleId: null, standCibleId: null }
    ]);
  });

  it("transmet le créneau souhaité d'un échange dirigé, et l'ignore sans créneau cible", () => {
    const dirige = {
      ...brouillon(1, 'S1', 'a2', null),
      creneauCibleId: 7,
      standCibleId: 'S9',
      creneauCibleLabel: 'mar. 14 juil. 10:00–12:00 · Stand neuf'
    };
    expect(versNouvellesDemandes([dirige])).toEqual([
      { creneauId: 1, standId: 'S1', cibleId: 'a2', motif: null, creneauCibleId: 7, standCibleId: 'S9' }
    ]);
  });
});
