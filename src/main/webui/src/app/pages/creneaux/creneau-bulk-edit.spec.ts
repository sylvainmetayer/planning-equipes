import { describe, expect, it } from 'vitest';
import { Creneau, GroupeCreneau } from '../../core/models';
import {
  CreneauBulkPatch,
  appliquerPatchCreneau,
  creneauxAvecHorairesInvalides,
  patchCreneauEstVide,
  patchCreneauVide
} from './creneau-bulk-edit';

const nominal: GroupeCreneau = { id: 'nominal', nom: 'Nominal', actif: true };
const secours: GroupeCreneau = { id: 'secours', nom: 'Repli pluie', actif: false };

function creneau(overrides: Partial<Creneau> = {}): Creneau {
  return { id: 1, jour: 1, date: '2026-08-01', heureDebut: '09:00', heureFin: '12:00', groupe: nominal, ...overrides };
}

function patch(overrides: Partial<CreneauBulkPatch> = {}): CreneauBulkPatch {
  return { ...patchCreneauVide(), ...overrides };
}

describe('patchCreneauEstVide', () => {
  it('considère le formulaire neutre comme vide', () => {
    expect(patchCreneauEstVide(patchCreneauVide())).toBe(true);
  });

  it('n’est plus vide dès qu’un champ est renseigné', () => {
    expect(patchCreneauEstVide(patch({ groupeId: 'secours' }))).toBe(false);
    expect(patchCreneauEstVide(patch({ heureFin: '13:00' }))).toBe(false);
  });
});

describe('appliquerPatchCreneau', () => {
  const groupes = [nominal, secours];

  it('ne touche à rien avec un patch neutre', () => {
    expect(appliquerPatchCreneau(creneau(), patchCreneauVide(), groupes)).toEqual(creneau());
  });

  it('déplace les créneaux vers un autre groupe', () => {
    expect(appliquerPatchCreneau(creneau(), patch({ groupeId: 'secours' }), groupes).groupe).toEqual(secours);
  });

  it('ne modifie que l’heure renseignée', () => {
    const resultat = appliquerPatchCreneau(creneau(), patch({ heureFin: '13:00' }), groupes);

    expect(resultat.heureDebut).toBe('09:00');
    expect(resultat.heureFin).toBe('13:00');
  });

  // Jour et date restent propres à chaque créneau : seul l'horaire et le
  // rattachement au groupe sont modifiables en masse.
  it('conserve le jour et la date', () => {
    const resultat = appliquerPatchCreneau(creneau(), patch({ groupeId: 'secours' }), groupes);

    expect(resultat.jour).toBe(1);
    expect(resultat.date).toBe('2026-08-01');
  });
});

describe('creneauxAvecHorairesInvalides', () => {
  it('repère un créneau qui finirait avant de commencer', () => {
    const creneaux = [creneau({ id: 1 }), creneau({ id: 2, heureDebut: '14:00', heureFin: '18:00' })];

    const invalides = creneauxAvecHorairesInvalides(creneaux, patch({ heureFin: '12:00' }), [nominal]);

    expect(invalides.map((c) => c.id)).toEqual([2]);
  });

  it('ne signale rien quand les horaires restent cohérents', () => {
    expect(creneauxAvecHorairesInvalides([creneau()], patch({ heureDebut: '08:00' }), [nominal])).toEqual([]);
  });
});
