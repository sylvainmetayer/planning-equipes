import { describe, expect, it } from 'vitest';
import { Creneau, Emplacement, PosteAffectation, Stand } from '../../core/models';
import { buildJourneesCarte, instantCarte } from './carte-jour';
import {
  TAILLE_PASTILLE_MAX,
  TAILLE_PASTILLE_MIN,
  loadByEmplacement,
  grilleCharge,
  grilleChargeEvenement,
  niveauDensite,
  readPorteeCharge,
  taillePastille,
} from './charge-emplacement';

const PLACE: Emplacement = {
  id: 'PLACE',
  nom: 'Place du Drapeau',
  latitude: 46.6,
  longitude: -0.2,
};
const HALLE: Emplacement = { id: 'HALLE', nom: 'Halle', latitude: null, longitude: null };

function creneau(id: number, heureDebut: string, heureFin: string, jour = 1): Creneau {
  return { id, jour, date: `2026-08-0${jour}`, heureDebut, heureFin };
}

function stand(id: string, lieu: Emplacement | null): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 3,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: lieu,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

let compteur = 0;

function poste(
  standDuPoste: Stand,
  creneauDuPoste: Creneau,
  pourvu: boolean,
  overrides: Partial<PosteAffectation> = {},
): PosteAffectation {
  compteur += 1;
  return {
    id: `P${compteur}`,
    stand: standDuPoste,
    creneau: creneauDuPoste,
    animateur: pourvu
      ? {
          id: `A${compteur}`,
          prenom: 'X',
          nom: 'Y',
          dateNaissance: '1990-01-01',
          manager: false,
          competences: {},
          souhaits: [],
          joursIndisponibles: [],
        }
      : null,
    ...overrides,
  };
}

const TIR = stand('Tir', PLACE);
const DIXIT = stand('Dixit', PLACE);
const BUVETTE = stand('Buvette', HALLE);
const ERRANT = stand('Errant', null);
const MATIN = creneau(1, '10:00', '12:00');
const APREM = creneau(2, '14:00', '18:00');

/**
 * Place du Drapeau: Tir 2/3 the morning, 1/1 the afternoon; Dixit 1/1 the
 * afternoon but only from 16:00 (a partial closure). Halle (no coordinates):
 * Buvette 1/2 the afternoon. Errant (no emplacement): 1/1 the morning.
 */
function journee() {
  return buildJourneesCarte([
    poste(TIR, MATIN, true),
    poste(TIR, MATIN, true),
    poste(TIR, MATIN, false),
    poste(TIR, APREM, true),
    poste(DIXIT, APREM, true, { heureDebutEffective: '16:00' }),
    poste(BUVETTE, APREM, true),
    poste(BUVETTE, APREM, false),
    poste(ERRANT, MATIN, true),
  ])[0];
}

describe('loadByEmplacement', () => {
  it('counts on a place the filled seats covering the instant, stand by stand', () => {
    const charges = loadByEmplacement(journee(), 16 * 60 + 30, [PLACE, HALLE]);
    const place = charges.find((charge) => charge.emplacementId === 'PLACE')!;
    expect(place.presents).toBe(2);
    expect(place.sieges).toBe(2);
    expect(place.parStand).toEqual([
      { standId: 'Dixit', nom: 'Dixit', presents: 1, sieges: 1 },
      { standId: 'Tir', nom: 'Tir', presents: 1, sieges: 1 },
    ]);
  });

  it('counts an empty seat as a seat and nobody, and a window as half-open', () => {
    const place = (minutes: number) =>
      loadByEmplacement(journee(), minutes).find((c) => c.emplacementId === 'PLACE')!;
    expect(place(10 * 60)).toMatchObject({ presents: 2, sieges: 3 });
    // Closing at 12:00 means gone at 12:00.
    expect(place(12 * 60)).toMatchObject({ presents: 0, sieges: 0 });
  });

  it('keeps the stands with no emplacement in a row of their own, last', () => {
    const charges = loadByEmplacement(journee(), 10 * 60, [PLACE, HALLE]);
    expect(charges.map((charge) => charge.emplacementId)).toEqual(['HALLE', 'PLACE', null]);
    expect(charges[2]).toMatchObject({ nom: 'Sans emplacement', presents: 1, situe: false });
  });

  it('says a place without coordinates is off the map, and still counts it', () => {
    const halle = loadByEmplacement(journee(), 15 * 60, [PLACE, HALLE]).find(
      (charge) => charge.emplacementId === 'HALLE',
    )!;
    expect(halle).toMatchObject({ situe: false, presents: 1, sieges: 2 });
  });

  it('matches what the map puts on each located marker', () => {
    const jour = journee();
    for (const minutes of [10 * 60, 11 * 60 + 45, 14 * 60, 16 * 60, 17 * 60 + 59]) {
      const carte = instantCarte(jour, minutes, [PLACE, HALLE]);
      const charges = loadByEmplacement(jour, minutes, [PLACE, HALLE]);
      carte.marqueurs.forEach((marqueur) => {
        const charge = charges.find((each) => each.emplacementId === marqueur.emplacementId)!;
        expect(charge.presents).toBe(marqueur.pourvus);
        expect(charge.sieges).toBe(marqueur.sieges);
      });
    }
  });

  it('has nothing to count without a plan', () => {
    expect(loadByEmplacement(null, 600)).toEqual([]);
  });
});

describe('grilleCharge', () => {
  it('cuts the day where somebody arrives or leaves, and drops the spans holding no seat', () => {
    const grille = grilleCharge(journee(), [PLACE, HALLE]);
    expect(grille.tranches.map((tranche) => tranche.libelle)).toEqual([
      '10:00 – 12:00',
      '14:00 – 16:00',
      '16:00 – 18:00',
    ]);
  });

  it('agrees with the instant reading anywhere inside each span', () => {
    const jour = journee();
    const grille = grilleCharge(jour, [PLACE, HALLE]);
    grille.tranches.forEach((tranche, colonne) => {
      for (let minutes = tranche.debutMinutes; minutes < tranche.finMinutes; minutes += 15) {
        const charges = loadByEmplacement(jour, minutes, [PLACE, HALLE]);
        grille.lignes.forEach((ligne, index) => {
          expect(ligne.cellules[colonne]).toEqual({
            presents: charges[index].presents,
            sieges: charges[index].sieges,
          });
        });
      }
    });
  });

  it('adds up the places into the total on site', () => {
    const grille = grilleCharge(journee(), [PLACE, HALLE]);
    expect(grille.total).toEqual([
      { presents: 3, sieges: 4 },
      { presents: 2, sieges: 3 },
      { presents: 3, sieges: 4 },
    ]);
    expect(grille.presentsMax).toBe(2);
  });

  it('merges two neighbouring spans that read the same everywhere', () => {
    const jour = buildJourneesCarte([
      poste(TIR, creneau(1, '10:00', '12:00'), true),
      poste(TIR, creneau(2, '12:00', '14:00'), true),
    ])[0];
    expect(grilleCharge(jour).tranches.map((tranche) => tranche.libelle)).toEqual([
      '10:00 – 14:00',
    ]);
  });

  it('is empty without a plan', () => {
    expect(grilleCharge(null)).toEqual({ tranches: [], lignes: [], total: [], presentsMax: 0 });
  });
});

describe('grilleChargeEvenement', () => {
  it('gives each place its peak of the day, and the minute it starts', () => {
    const jours = buildJourneesCarte([
      poste(TIR, MATIN, true),
      poste(TIR, MATIN, true),
      poste(TIR, APREM, true),
      poste(TIR, creneau(3, '09:00', '11:00', 2), true),
    ]);
    const grille = grilleChargeEvenement(jours, [PLACE]);
    expect(grille.jours.map((jour) => jour.jour)).toEqual([1, 2]);
    expect(grille.lignes[0].cellules).toEqual([
      { presents: 2, sieges: 2, minutes: 600 },
      { presents: 1, sieges: 1, minutes: 540 },
    ]);
    expect(grille.presentsMax).toBe(2);
  });

  it('leaves a day a place holds nobody on without a peak', () => {
    const jours = buildJourneesCarte([
      poste(TIR, MATIN, true),
      poste(BUVETTE, creneau(3, '09:00', '11:00', 2), true),
    ]);
    const tir = grilleChargeEvenement(jours).lignes.find(
      (ligne) => ligne.emplacementId === 'PLACE',
    )!;
    expect(tir.cellules[1]).toEqual({ presents: 0, sieges: 0, minutes: null });
  });
});

describe('niveauDensite', () => {
  it('shades nobody at zero and the fullest cell at four', () => {
    expect(niveauDensite(0, 10)).toBe(0);
    expect(niveauDensite(1, 10)).toBe(1);
    expect(niveauDensite(6, 10)).toBe(3);
    expect(niveauDensite(10, 10)).toBe(4);
  });
});

describe('taillePastille', () => {
  it('grows the area, not the diameter, with the headcount', () => {
    expect(taillePastille(0, 20)).toBe(TAILLE_PASTILLE_MIN);
    expect(taillePastille(20, 20)).toBe(TAILLE_PASTILLE_MAX);
    // A quarter of the people: half the growth, since the area is what scales.
    expect(taillePastille(5, 20)).toBe(
      Math.round(TAILLE_PASTILLE_MIN + (TAILLE_PASTILLE_MAX - TAILLE_PASTILLE_MIN) / 2),
    );
  });
});

describe('readPorteeCharge', () => {
  it('reads the event-wide grid and falls back on the day for anything else', () => {
    expect(readPorteeCharge('evenement')).toBe('evenement');
    expect(readPorteeCharge(null)).toBe('jour');
    expect(readPorteeCharge('n-importe')).toBe('jour');
  });
});
