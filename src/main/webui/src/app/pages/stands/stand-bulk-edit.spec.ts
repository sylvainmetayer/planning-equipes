import { describe, expect, it } from 'vitest';
import { Emplacement, HoraireStand, Stand } from '../../core/models';
import {
  StandBulkPatch,
  appliquerPatchStand,
  patchStandEstVide,
  patchStandVide,
  standsAvecEffectifInvalide,
  standsWithWindowBeyondMaximum,
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
    ...overrides,
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
    expect(
      patchStandEstVide(patch({ emplacement: { mode: 'DEFINIR', emplacementId: null } })),
    ).toBe(true);
  });

  // "Replace with another stand's" without naming which one changes nothing.
  // A model carrying nothing would erase the schedules of the whole selection,
  // silently and with no way back: it counts as « nothing to apply », like no
  // model at all. Erasing has its own mode, chosen on purpose.
  it('reste vide quand le stand modèle n’a ni règle ni exception', () => {
    const vide = stand({ id: 'neuf' });

    expect(
      patchStandEstVide(patch({ horaires: { mode: 'DEPUIS_STAND', horaires: [], source: vide } })),
    ).toBe(true);
  });

  it('reste vide tant qu’aucun stand modèle n’est choisi', () => {
    expect(
      patchStandEstVide(patch({ horaires: { mode: 'DEPUIS_STAND', horaires: [], source: null } })),
    ).toBe(true);
    expect(
      patchStandEstVide(
        patch({ horaires: { mode: 'DEPUIS_STAND', horaires: [], source: standModele() } }),
      ),
    ).toBe(false);
  });

  it('n’est plus vide dès qu’un champ est renseigné', () => {
    expect(patchStandEstVide(patch({ effectifMax: 6 }))).toBe(false);
    expect(
      patchStandEstVide(patch({ emplacement: { mode: 'EFFACER', emplacementId: null } })),
    ).toBe(false);
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
    const resultat = appliquerPatchStand(
      stand(),
      patch({ emplacement: { mode: 'DEFINIR', emplacementId: 'mairie' } }),
      emplacements,
    );

    expect(resultat.emplacement).toEqual(mairie);
  });

  it('détache les stands de leur emplacement', () => {
    const resultat = appliquerPatchStand(
      stand(),
      patch({ emplacement: { mode: 'EFFACER', emplacementId: null } }),
      emplacements,
    );

    expect(resultat.emplacement).toBeNull();
  });

  it('remplace les typologies proposées', () => {
    const resultat = appliquerPatchStand(
      stand(),
      patch({ typologies: { mode: 'REMPLACER', typologies: ['jeuxDeRole'] } }),
      emplacements,
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
      emplacements,
    );

    expect(resultat.reserveMajeurs).toBe(true);
    expect(resultat.premium).toBe(true);
    expect(resultat.niveauEffort).toBe('EPUISANT');
  });

  // Les fermetures/ouvertures ponctuelles sont propres à chaque stand : jamais touchées.
  it('conserve fermetures et ouvertures', () => {
    const avecFermeture = stand({
      indisponibilites: [
        { id: 1, date: '2026-08-01', heureDebut: '14:00', heureFin: '16:00', motif: null },
      ],
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
      patch({ horaires: { mode: 'REMPLACER', horaires: [regleQuotidienne()], source: null } }),
      emplacements,
    );

    expect(resultat.horaires).toHaveLength(1);
    expect(resultat.horaires[0].id).toBeNull();
    // Normalised like the single-stand form: an absent effectif is sent as null, never as a missing key.
    expect(resultat.horaires[0].fenetres).toEqual([
      { heureDebut: '14:00', heureFin: null, effectif: null },
    ]);
  });

  it('ajoute une règle sans écraser celles du stand', () => {
    const avecHoraire = stand({ horaires: [regleQuotidienne()] });

    const resultat = appliquerPatchStand(
      avecHoraire,
      patch({ horaires: { mode: 'AJOUTER', horaires: [regleQuotidienne()], source: null } }),
      emplacements,
    );

    expect(resultat.horaires).toHaveLength(2);
  });

  it('efface les horaires sans toucher aux exceptions datées', () => {
    const avecTout = stand({
      horaires: [regleQuotidienne()],
      indisponibilites: [
        { id: 1, date: '2026-08-01', heureDebut: '14:00', heureFin: null, motif: null },
      ],
    });

    const resultat = appliquerPatchStand(
      avecTout,
      patch({ horaires: { mode: 'EFFACER', horaires: [], source: null } }),
      emplacements,
    );

    expect(resultat.horaires).toEqual([]);
    expect(resultat.indisponibilites).toEqual(avecTout.indisponibilites);
  });

  /**
   * Une portée quittée laisse ses jours dans la saisie : la copie doit lire la
   * règle normalisée, sinon elle envoie à tous les stands des jours de semaine
   * que le solveur ignore mais que les avertissements d'écriture comptent
   * comme un horaire changé.
   */
  it('oublie les jours d’une portée quittée', () => {
    const quittee = {
      ...regleQuotidienne(),
      jours: 'TOUS' as const,
      joursSemaine: ['MONDAY' as const],
    };

    const resultat = appliquerPatchStand(
      stand(),
      patch({ horaires: { mode: 'REMPLACER', horaires: [quittee], source: null } }),
      emplacements,
    );

    expect(resultat.horaires[0].joursSemaine).toEqual([]);
  });

  /**
   * Les objets de règle sont copiés par stand : partager la même instance
   * enverrait à cinquante stands l'id de l'un d'eux.
   */
  it('ne partage pas les objets de règle entre deux stands', () => {
    const modele = regleQuotidienne();
    const patchCommun = patch({
      horaires: { mode: 'REMPLACER', horaires: [modele], source: null },
    });

    const premier = appliquerPatchStand(stand({ id: 'A' }), patchCommun, emplacements);
    const second = appliquerPatchStand(stand({ id: 'B' }), patchCommun, emplacements);

    expect(premier.horaires[0]).not.toBe(second.horaires[0]);
    expect(premier.horaires[0].fenetres[0]).not.toBe(second.horaires[0].fenetres[0]);
  });
});

/** The typical day copied by `DEPUIS_STAND`: one rule, one dated opening, one dated closure. */
function standModele(): Stand {
  return stand({
    id: 'PAVILLON',
    nom: 'Pavillon',
    effectifMax: 4,
    horaires: [
      {
        ...regleQuotidienne(),
        id: 7,
        fenetres: [{ heureDebut: '14:00', heureFin: null, effectif: 3 }],
      },
    ],
    ouvertures: [
      {
        id: 11,
        date: '2026-07-10',
        heureDebut: '10:00',
        heureFin: '12:00',
        motif: 'Inauguration',
        effectif: 2,
      },
    ],
    indisponibilites: [
      { id: 12, date: '2026-07-11', heureDebut: '18:00', heureFin: null, motif: 'Concert' },
    ],
  });
}

function depuisStand(source: Stand | null = standModele()): StandBulkPatch {
  return patch({ horaires: { mode: 'DEPUIS_STAND', horaires: [], source } });
}

describe('appliquerPatchStand — DEPUIS_STAND, la journée type d’un stand sur la sélection', () => {
  const emplacements = [kiosque, mairie];

  it('remplace règles, ouvertures et fermetures datées par celles du stand modèle', () => {
    const target = stand({
      horaires: [{ ...regleQuotidienne(), id: 3 }],
      indisponibilites: [
        { id: 1, date: '2026-08-01', heureDebut: '14:00', heureFin: null, motif: null },
      ],
    });

    const resultat = appliquerPatchStand(target, depuisStand(), emplacements);

    expect(resultat.horaires).toHaveLength(1);
    expect(resultat.horaires[0].fenetres).toEqual([
      { heureDebut: '14:00', heureFin: null, effectif: 3 },
    ]);
    expect(resultat.ouvertures).toHaveLength(1);
    expect(resultat.ouvertures[0]).toMatchObject({ date: '2026-07-10', effectif: 2 });
    expect(resultat.indisponibilites).toHaveLength(1);
    expect(resultat.indisponibilites[0]).toMatchObject({ date: '2026-07-11', motif: 'Concert' });
  });

  // The model's rows belong to the model: the target gets rows of its own.
  it('remet chaque id à zéro', () => {
    const resultat = appliquerPatchStand(stand(), depuisStand(), emplacements);

    expect(resultat.horaires[0].id).toBeNull();
    expect(resultat.ouvertures[0].id).toBeNull();
    expect(resultat.indisponibilites[0].id).toBeNull();
  });

  it('ne touche à rien tant qu’aucun stand modèle n’est choisi', () => {
    const target = stand({
      horaires: [regleQuotidienne()],
      ouvertures: [
        {
          id: 2,
          date: '2026-08-02',
          heureDebut: '10:00',
          heureFin: null,
          motif: null,
          effectif: null,
        },
      ],
    });

    const resultat = appliquerPatchStand(target, depuisStand(null), emplacements);

    expect(resultat).toEqual(target);
  });

  it('laisse les autres champs du stand target tels quels', () => {
    const target = stand({ effectifMin: 2, effectifMax: 5, premium: true, emplacement: mairie });

    const resultat = appliquerPatchStand(target, depuisStand(), emplacements);

    expect(resultat).toMatchObject({
      effectifMin: 2,
      effectifMax: 5,
      premium: true,
      emplacement: mairie,
    });
  });

  it('ne partage pas les objets copiés entre deux stands', () => {
    const commun = depuisStand();

    const premier = appliquerPatchStand(stand({ id: 'A' }), commun, emplacements);
    const second = appliquerPatchStand(stand({ id: 'B' }), commun, emplacements);

    expect(premier.horaires[0]).not.toBe(second.horaires[0]);
    expect(premier.ouvertures[0]).not.toBe(second.ouvertures[0]);
    expect(premier.indisponibilites[0]).not.toBe(second.indisponibilites[0]);
  });
});

describe('standsWithWindowBeyondMaximum', () => {
  it('nomme les stands dont une fenêtre copiée dépasse l’effectif maximum', () => {
    const stands = [stand({ id: 'petit', effectifMax: 2 }), stand({ id: 'grand', effectifMax: 6 })];

    const depasses = standsWithWindowBeyondMaximum(stands, depuisStand(), []);

    expect(depasses.map((s) => s.id)).toEqual(['petit']);
  });

  // The usual remedy is applied in the same dialog: read on the patched value.
  it('lit l’effectif maximum tel que le patch le laisse', () => {
    const stands = [stand({ id: 'petit', effectifMax: 2 })];

    expect(standsWithWindowBeyondMaximum(stands, { ...depuisStand(), effectifMax: 3 }, [])).toEqual(
      [],
    );
  });

  // The warning does not belong to the copy: lowering the maximum alone puts a
  // window already in place beyond it, and the server refuses that stand too.
  it('signale une fenêtre déjà en place que le nouveau maximum dépasse', () => {
    const stands = [stand({ id: 'petit', horaires: [regleEffectif(5)], effectifMax: 9 })];

    expect(
      standsWithWindowBeyondMaximum(stands, patch({ effectifMax: 1 }), []).map((each) => each.id),
    ).toEqual(['petit']);
  });

  it('ne signale rien quand le maximum couvre les fenêtres en place', () => {
    const stands = [stand({ id: 'large', horaires: [regleEffectif(5)], effectifMax: 9 })];

    expect(standsWithWindowBeyondMaximum(stands, patch({ premium: 'OUI' }), [])).toEqual([]);
  });
});

/** A daily rule naming a headcount, which is what the maximum is compared to. */
function regleEffectif(effectif: number): HoraireStand {
  const regle = regleQuotidienne();
  return { ...regle, fenetres: [{ ...regle.fenetres[0], effectif }] };
}

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
    motif: null,
  };
}

describe('standsAvecEffectifInvalide', () => {
  it('repère les stands dont le maximum passerait sous le minimum', () => {
    const stands = [
      stand({ id: 'petit', effectifMin: 1 }),
      stand({ id: 'grand', effectifMin: 5, effectifMax: 8 }),
    ];

    const invalides = standsAvecEffectifInvalide(stands, patch({ effectifMax: 3 }), []);

    expect(invalides.map((s) => s.id)).toEqual(['grand']);
  });

  it('ne signale rien quand les bornes restent cohérentes', () => {
    expect(standsAvecEffectifInvalide([stand()], patch({ effectifMin: 1 }), [])).toEqual([]);
  });
});
