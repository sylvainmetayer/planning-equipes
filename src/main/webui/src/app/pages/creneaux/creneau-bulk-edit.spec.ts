import { describe, expect, it } from 'vitest';
import { Creneau } from '../../core/models';
import {
  CreneauBulkPatch,
  appliquerPatchCreneau,
  creneauxFranchissantMinuit,
  patchCreneauEstVide,
  patchCreneauVide
} from './creneau-bulk-edit';

function creneau(overrides: Partial<Creneau> = {}): Creneau {
  return { id: 1, jour: 1, date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00', ...overrides };
}

function patch(overrides: Partial<CreneauBulkPatch> = {}): CreneauBulkPatch {
  return { ...patchCreneauVide(), ...overrides };
}

describe('patchCreneauEstVide', () => {
  it('considère le formulaire neutre comme vide', () => {
    expect(patchCreneauEstVide(patchCreneauVide())).toBe(true);
  });

  it('n’est plus vide dès qu’un champ est renseigné', () => {
    expect(patchCreneauEstVide(patch({ heureDebut: '08:00' }))).toBe(false);
    expect(patchCreneauEstVide(patch({ heureFin: '13:00' }))).toBe(false);
  });
});

describe('appliquerPatchCreneau', () => {
  it('ne touche à rien avec un patch neutre', () => {
    expect(appliquerPatchCreneau(creneau(), patchCreneauVide())).toEqual(creneau());
  });

  it('ne modifie que l’heure renseignée', () => {
    const resultat = appliquerPatchCreneau(creneau(), patch({ heureFin: '13:00' }));

    expect(resultat.heureDebut).toBe('09:00');
    expect(resultat.heureFin).toBe('13:00');
  });

  // Jour et date restent propres à chaque créneau : seul l'horaire est
  // modifiable en masse.
  it('conserve le jour et la date', () => {
    const resultat = appliquerPatchCreneau(creneau(), patch({ heureFin: '13:00' }));

    expect(resultat.jour).toBe(1);
    expect(resultat.date).toBe('2026-08-01');
  });
});

describe('creneauxFranchissantMinuit', () => {
  it('repère un créneau qui se terminerait le lendemain', () => {
    const creneaux = [creneau({ id: 1 }), creneau({ id: 2, heureDebut: '14:00', heureFin: '18:00' })];

    const deNuit = creneauxFranchissantMinuit(creneaux, patch({ heureFin: '12:00' }));

    expect(deNuit.map((c) => c.id)).toEqual([2]);
  });

  it('ne signale rien quand chaque créneau reste dans sa journée', () => {
    expect(creneauxFranchissantMinuit([creneau()], patch({ heureDebut: '08:00' }))).toEqual([]);
  });

  // Le cas archétypal : une soirée saisie en masse, 20:00 → 00:00.
  it('compte le créneau qui se termine à minuit pile', () => {
    const deNuit = creneauxFranchissantMinuit([creneau({ id: 3 })], patch({ heureDebut: '20:00', heureFin: '00:00' }));

    expect(deNuit.map((c) => c.id)).toEqual([3]);
  });
});
