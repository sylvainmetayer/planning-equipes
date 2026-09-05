import { describe, expect, it } from 'vitest';
import { Emplacement, HoraireStand, Stand } from '../../core/models';
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
    horaires: [],
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

  // Les fermetures/ouvertures ponctuelles sont propres à chaque stand : jamais touchées.
  it('conserve fermetures et ouvertures', () => {
    const avecFermeture = stand({
      indisponibilites: [{ id: 1, date: '2026-08-01', heureDebut: '14:00', heureFin: '16:00', motif: null }]
    });

    const resultat = appliquerPatchStand(avecFermeture, patch({ premium: 'OUI' }), emplacements);

    expect(resultat.indisponibilites).toEqual(avecFermeture.indisponibilites);
  });

  it('laisse les horaires intacts en mode INCHANGE', () => {
    const avecHoraire = stand({ horaires: [regleQuotidienne()] });

    const resultat = appliquerPatchStand(avecHoraire, patch({ premium: 'OUI' }), emplacements);

    expect(resultat.horaires).toEqual(avecHoraire.horaires);
  });

  // Le gain visé : les trente stands en « 14h → fermeture » réglés d'un coup.
  it('remplace les horaires et repart d’un id vierge', () => {
    const avecHoraire = stand({ horaires: [{ ...regleQuotidienne(), id: 7 }] });

    const resultat = appliquerPatchStand(
      avecHoraire,
      patch({ horaires: { mode: 'REMPLACER', horaires: [regleQuotidienne()] } }),
      emplacements
    );

    expect(resultat.horaires).toHaveLength(1);
    expect(resultat.horaires[0].id).toBeNull();
    // Normalised like the single-stand form: an absent effectif is sent as null, never as a missing key.
    expect(resultat.horaires[0].fenetres).toEqual([{ heureDebut: '14:00', heureFin: null, effectif: null }]);
  });

  it('ajoute une règle sans écraser celles du stand', () => {
    const avecHoraire = stand({ horaires: [regleQuotidienne()] });

    const resultat = appliquerPatchStand(
      avecHoraire,
      patch({ horaires: { mode: 'AJOUTER', horaires: [regleQuotidienne()] } }),
      emplacements
    );

    expect(resultat.horaires).toHaveLength(2);
  });

  it('efface les horaires sans toucher aux exceptions datées', () => {
    const avecTout = stand({
      horaires: [regleQuotidienne()],
      indisponibilites: [{ id: 1, date: '2026-08-01', heureDebut: '14:00', heureFin: null, motif: null }]
    });

    const resultat = appliquerPatchStand(
      avecTout,
      patch({ horaires: { mode: 'EFFACER', horaires: [] } }),
      emplacements
    );

    expect(resultat.horaires).toEqual([]);
    expect(resultat.indisponibilites).toEqual(avecTout.indisponibilites);
  });

  /**
   * Les objets de règle sont copiés par stand : partager la même instance
   * enverrait à cinquante stands l'id de l'un d'eux.
   */
  it('ne partage pas les objets de règle entre deux stands', () => {
    const modele = regleQuotidienne();
    const patchCommun = patch({ horaires: { mode: 'REMPLACER', horaires: [modele] } });

    const premier = appliquerPatchStand(stand({ id: 'A' }), patchCommun, emplacements);
    const second = appliquerPatchStand(stand({ id: 'B' }), patchCommun, emplacements);

    expect(premier.horaires[0]).not.toBe(second.horaires[0]);
    expect(premier.horaires[0].fenetres[0]).not.toBe(second.horaires[0].fenetres[0]);
  });
});

function regleQuotidienne(): HoraireStand {
  return {
    id: null,
    mode: 'OUVERTURE',
    jours: 'TOUS',
    joursSemaine: [],
    dateDebut: null,
    dateFin: null,
    dates: [],
    fenetres: [{ heureDebut: '14:00', heureFin: null }],
    motif: null
  };
}

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
