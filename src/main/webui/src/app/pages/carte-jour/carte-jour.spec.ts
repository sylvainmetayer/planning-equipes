import { describe, expect, it } from 'vitest';
import { Creneau, Emplacement, PosteAffectation, Stand } from '../../core/models';
import {
  MarqueurJour,
  buildJourneesCarte,
  comptePastille,
  etatStandInstant,
  formatMinutes,
  instantCarte,
} from './carte-jour';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function emplacement(id: string, overrides: Partial<Emplacement> = {}): Emplacement {
  return { id, nom: id, latitude: 46.65, longitude: -0.25, ...overrides };
}

function stand(id: string, lieu: Emplacement | null = null): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
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
  standDuPoste: Stand | null,
  creneauDuPoste: Creneau | null,
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
          id: 'A1',
          prenom: 'Alice',
          nom: 'Test',
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

describe('buildJourneesCarte', () => {
  it('groups the postes by event day and by stand, days in chronological order', () => {
    const jour1 = creneau({ id: 1, jour: 1, date: '2026-08-01' });
    const jour2 = creneau({
      id: 2,
      jour: 2,
      date: '2026-08-02',
      heureDebut: '14:00',
      heureFin: '18:00',
    });
    const jours = buildJourneesCarte([
      poste(stand('S2'), jour2, true),
      poste(stand('S1'), jour1, true),
      poste(stand('S1'), jour1, false),
    ]);

    expect(jours.map((jour) => jour.jour)).toEqual([1, 2]);
    expect(jours[0].stands).toHaveLength(1);
    expect(jours[0].stands[0].postes).toHaveLength(2);
  });

  it('rounds the day bounds outwards to whole hours', () => {
    const jours = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '09:30', heureFin: '12:45' }), true),
    ]);

    expect(jours[0].debutMinutes).toBe(9 * 60);
    expect(jours[0].finMinutes).toBe(13 * 60);
  });

  it('narrows a poste to its effective window when a partial closure set one', () => {
    const jours = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true, {
        heureDebutEffective: '11:00',
        heureFinEffective: '12:00',
      }),
    ]);

    expect(jours[0].stands[0].postes[0].debutMinutes).toBe(11 * 60);
  });

  it('accepts the HH:mm:ss the API sends and trims it', () => {
    const jours = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00:00', heureFin: '12:00:00' }), true),
    ]);

    expect(jours[0].stands[0].postes[0].heureDebut).toBe('10:00');
    expect(jours[0].stands[0].postes[0].finMinutes).toBe(12 * 60);
  });

  it('reads midnight as the end of the day, never as the start of the next one', () => {
    const jours = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '22:00', heureFin: '00:00' }), true),
    ]);

    expect(jours[0].finMinutes).toBe(24 * 60);
  });

  it('holds no day at all when the plan holds no poste', () => {
    expect(buildJourneesCarte([])).toEqual([]);
  });

  it('skips a poste that names neither a day nor a place', () => {
    expect(
      buildJourneesCarte([poste(stand('S1'), null, true), poste(null, creneau({ id: 1 }), true)]),
    ).toEqual([]);
  });
});

describe('etatStandInstant', () => {
  const journee = buildJourneesCarte([
    poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), false),
  ])[0];

  it('is open and short-staffed while only part of its seats are filled', () => {
    const instant = etatStandInstant(journee.stands[0], 11 * 60);
    expect(instant.etat).toBe('partiel');
    expect(instant.sieges).toBe(2);
    expect(instant.pourvus).toBe(1);
    expect(instant.horaire).toBe('10:00 – 12:00');
  });

  it('is closed before it opens and at the very minute it closes', () => {
    expect(etatStandInstant(journee.stands[0], 9 * 60 + 59).etat).toBe('ferme');
    expect(etatStandInstant(journee.stands[0], 10 * 60).etat).toBe('partiel');
    expect(etatStandInstant(journee.stands[0], 12 * 60).etat).toBe('ferme');
  });

  it('is « pourvu » when every seat covering the instant is filled', () => {
    const complet = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];
    expect(etatStandInstant(complet.stands[0], 11 * 60).etat).toBe('pourvu');
  });

  it('is « decouvert » when it is open with not one seat filled — the case the screen exists for', () => {
    const vide = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), false),
    ])[0];
    expect(etatStandInstant(vide.stands[0], 11 * 60).etat).toBe('decouvert');
  });
});

describe('instantCarte', () => {
  const place = emplacement('PLACE', { nom: 'Place du Drapeau' });
  const mairie = emplacement('MAIRIE', { nom: 'Mairie' });

  it('draws one marker per emplacement, whatever the number of stands on it', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
      poste(stand('S2', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), false),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, [place]);

    expect(instant.marqueurs).toHaveLength(1);
    expect(instant.marqueurs[0].stands.map((stand) => stand.standId)).toEqual(['S2', 'S1']);
    expect(instant.marqueurs[0].ouverts).toBe(2);
  });

  it('colours a marker after the worst of its stands', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
      poste(stand('S2', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), false),
    ])[0];

    expect(instantCarte(journee, 11 * 60, [place]).marqueurs[0].etat).toBe('decouvert');
  });

  it('closes a marker once every stand of the place has closed', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];

    const instant = instantCarte(journee, 13 * 60, [place]);
    expect(instant.marqueurs[0].etat).toBe('ferme');
    expect(instant.compteurs.standsOuverts).toBe(0);
  });

  it('lists a stand with no emplacement next to the map instead of dropping it', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1'), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, []);
    expect(instant.marqueurs).toEqual([]);
    expect(instant.nonSitues.map((stand) => stand.standId)).toEqual(['S1']);
    expect(instant.compteurs.nonSitues).toBe(1);
  });

  it('names an emplacement saved without one by its id, so the reason stays the right one', () => {
    // An empty name used to collapse to `null`, and the list then told the
    // operator to attach an emplacement to a stand that already had one.
    const anonyme = emplacement('FLOU', { nom: '', latitude: null, longitude: null });
    const journee = buildJourneesCarte([
      poste(stand('S1', anonyme), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, [anonyme]);
    expect(instant.nonSitues[0].emplacementNom).toBe('FLOU');
    expect(instant.nonSitues[0].resume).toContain("qui n'a pas de coordonnées");
    expect(instant.nonSitues[0].resume).not.toContain('rattaché à aucun emplacement');
  });

  it('does the same for a stand tied to an emplacement nobody geolocated', () => {
    const sansPoint = emplacement('FLOU', { latitude: null, longitude: null });
    const journee = buildJourneesCarte([
      poste(
        stand('S1', sansPoint),
        creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }),
        true,
      ),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, [sansPoint]);
    expect(instant.marqueurs).toEqual([]);
    expect(instant.nonSitues[0].emplacementNom).toBe('FLOU');
  });

  it('falls back on the coordinates the plan carries when the referential could not be loaded', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, []);
    expect(instant.marqueurs).toHaveLength(1);
    expect(instant.marqueurs[0].latitude).toBe(place.latitude);
  });

  it('still draws an emplacement holding no stand that day, and counts it', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, [place, mairie]);
    const vide = instant.marqueurs.find((marqueur) => marqueur.emplacementId === 'MAIRIE');
    expect(vide?.etat).toBe('sansStand');
    expect(instant.compteurs.emplacementsSansStand).toBe(1);
  });

  it('orders the markers worst first, so the list opens on what needs a decision', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
      poste(stand('S2', mairie), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), false),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, [place, mairie]);
    expect(instant.marqueurs.map((marqueur) => marqueur.etat)).toEqual(['decouvert', 'pourvu']);
  });

  it('answers an empty screen, not a crash, when there is no day to replay', () => {
    const instant = instantCarte(null, 600, [emplacement('PLACE')]);
    expect(instant.marqueurs).toEqual([]);
    expect(instant.compteurs.standsTotal).toBe(0);
    expect(instant.heure).toBe('10:00');
  });

  it('counts the seats and the filled ones over the whole day, not per marker', () => {
    const journee = buildJourneesCarte([
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
      poste(stand('S1', place), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), false),
      poste(stand('S2', mairie), creneau({ id: 1, heureDebut: '10:00', heureFin: '12:00' }), true),
    ])[0];

    const instant = instantCarte(journee, 11 * 60, [place, mairie]);
    expect(instant.compteurs.sieges).toBe(3);
    expect(instant.compteurs.pourvus).toBe(2);
    expect(instant.compteurs.partiels).toBe(1);
    expect(instant.compteurs.decouverts).toBe(0);
  });
});

describe('comptePastille', () => {
  function marqueur(overrides: Partial<MarqueurJour>): MarqueurJour {
    return {
      emplacementId: 'PLACE',
      nom: 'Place',
      latitude: 46.65,
      longitude: -0.25,
      etat: 'pourvu',
      stands: [],
      ouverts: 0,
      sieges: 0,
      pourvus: 0,
      resume: '',
      ...overrides,
    };
  }

  it('writes the number of open stands, which is what the badge promises', () => {
    expect(comptePastille(marqueur({ etat: 'partiel', ouverts: 2 }))).toBe('2');
  });

  it('writes zero on a closed place instead of the number of stands attached to it', () => {
    // The bug this pins: a place holding three stands, all closed, showed « 3 »
    // on a grey badge whose own tooltip said no stand was open.
    const ferme = etatStandInstant(
      { standId: 'S1', nom: 'S1', emplacement: null, postes: [] },
      600,
    );
    expect(
      comptePastille(marqueur({ etat: 'ferme', ouverts: 0, stands: [ferme, ferme, ferme] })),
    ).toBe('0');
  });

  it('writes nothing on a place holding no stand at all that day', () => {
    expect(comptePastille(marqueur({ etat: 'sansStand', ouverts: 0 }))).toBe('');
  });
});

describe('formatMinutes', () => {
  it('writes the cursor position as a time of day', () => {
    expect(formatMinutes(0)).toBe('00:00');
    expect(formatMinutes(9 * 60 + 5)).toBe('09:05');
    expect(formatMinutes(1440)).toBe('24:00');
  });

  it('clamps anything outside the day rather than writing a negative hour', () => {
    expect(formatMinutes(-30)).toBe('00:00');
    expect(formatMinutes(5000)).toBe('24:00');
  });
});
