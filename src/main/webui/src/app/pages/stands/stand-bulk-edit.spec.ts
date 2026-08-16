import { describe, expect, it } from 'vitest';
import { Emplacement, Stand } from '../../core/models';
import {
  StandBulkPatch,
  appliquerPatchStand,
  patchStandEstVide,
  patchStandVide,
  standsAvecEffectifInvalide
} from './stand-bulk-edit';

const kiosque: Emplacement = { id: 'kiosque', nom: 'Kiosque', latitude: 47.2, longitude: -1.55 };
const mairie: Emplacement = { id: 'mairie', nom: 'Mairie', latitude: 47.21, longitude: -1.56 };

function stand(overrides: Partial<Stand> = {}): Stand {
  return {
    id: 'tir',
    nom: 'Tir à la corde',
    typologiesProposees: ['jeuxDeSociete'],
    effectifMin: 2,
    effectifMax: 4,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: kiosque,
    indisponibilites: [],
    ouvertures: [],
    ...overrides
  };
}

function patch(overrides: Partial<StandBulkPatch> = {}): StandBulkPatch {
  return { ...patchStandVide(), ...overrides };
}

describe('patchStandEstVide', () => {
  it('considère le formulaire neutre comme vide', () => {
    expect(patchStandEstVide(patchStandVide())).toBe(true);
  });

  it('reste vide tant qu’aucun emplacement n’est choisi', () => {
    expect(patchStandEstVide(patch({ emplacement: { mode: 'DEFINIR', emplacementId: null } }))).toBe(true);
  });

  it('n’est plus vide dès qu’un champ est renseigné', () => {
    expect(patchStandEstVide(patch({ effectifMax: 6 }))).toBe(false);
    expect(patchStandEstVide(patch({ emplacement: { mode: 'EFFACER', emplacementId: null } }))).toBe(false);
  });
});

describe('appliquerPatchStand', () => {
  const emplacements = [kiosque, mairie];

  it('ne touche à rien avec un patch neutre', () => {
    expect(appliquerPatchStand(stand(), patchStandVide(), emplacements)).toEqual(stand());
  });

  // Le cas d'usage principal : rattacher d'un coup plusieurs stands au même
  // point GPS du plan.
  it('affecte le même emplacement à tous les stands', () => {
    const resultat = appliquerPatchStand(stand(), patch({ emplacement: { mode: 'DEFINIR', emplacementId: 'mairie' } }), emplacements);

    expect(resultat.emplacement).toEqual(mairie);
  });

  it('détache les stands de leur emplacement', () => {
    const resultat = appliquerPatchStand(stand(), patch({ emplacement: { mode: 'EFFACER', emplacementId: null } }), emplacements);

    expect(resultat.emplacement).toBeNull();
  });

  it('remplace les typologies proposées', () => {
    const resultat = appliquerPatchStand(
      stand(),
      patch({ typologies: { mode: 'REMPLACER', typologies: ['jeuxDeRole'] } }),
      emplacements
    );

    expect(resultat.typologiesProposees).toEqual(['jeuxDeRole']);
  });

  it('ne modifie que la borne d’effectif renseignée', () => {
    const resultat = appliquerPatchStand(stand(), patch({ effectifMax: 6 }), emplacements);

    expect(resultat.effectifMin).toBe(2);
    expect(resultat.effectifMax).toBe(6);
  });

  it('force les indicateurs booléens et le niveau d’effort', () => {
    const resultat = appliquerPatchStand(
      stand(),
      patch({ reserveMajeurs: 'OUI', premium: 'OUI', niveauEffort: 'EPUISANT' }),
      emplacements
    );

    expect(resultat.reserveMajeurs).toBe(true);
    expect(resultat.premium).toBe(true);
    expect(resultat.niveauEffort).toBe('EPUISANT');
  });

  // Les fermetures/ouvertures sont propres à chaque stand : jamais touchées.
  it('conserve fermetures et ouvertures', () => {
    const avecFermeture = stand({
      indisponibilites: [{ id: 1, date: '2026-08-01', heureDebut: '14:00', heureFin: '16:00', motif: null }]
    });

    const resultat = appliquerPatchStand(avecFermeture, patch({ premium: 'OUI' }), emplacements);

    expect(resultat.indisponibilites).toEqual(avecFermeture.indisponibilites);
  });
});

describe('standsAvecEffectifInvalide', () => {
  it('repère les stands dont le maximum passerait sous le minimum', () => {
    const stands = [stand({ id: 'petit', effectifMin: 1 }), stand({ id: 'grand', effectifMin: 5, effectifMax: 8 })];

    const invalides = standsAvecEffectifInvalide(stands, patch({ effectifMax: 3 }), []);

    expect(invalides.map((s) => s.id)).toEqual(['grand']);
  });

  it('ne signale rien quand les bornes restent cohérentes', () => {
    expect(standsAvecEffectifInvalide([stand()], patch({ effectifMin: 1 }), [])).toEqual([]);
  });
});
