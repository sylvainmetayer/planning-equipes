import { describe, expect, it } from 'vitest';
import { Animateur, AnimateurProfile, ProfileAdjustment } from '../../core/models';
import {
  adjustmentScope,
  availabilityStrip,
  competenceRows,
  daysOffOutsideEvent,
  regimeChanges,
  regimeLabel,
  upcomingCount,
} from './animateur-fiche';

function animateur(partial: Partial<Animateur> = {}): Animateur {
  return {
    id: 'a1',
    prenom: 'Camille',
    nom: 'Durand',
    dateNaissance: '2008-07-12',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...partial,
  };
}

function adjustment(partial: Partial<ProfileAdjustment> = {}): ProfileAdjustment {
  return {
    id: 'adj',
    type: 'INDISPONIBILITE_FORCEE',
    creneauId: null,
    date: null,
    heureDebut: null,
    heureFin: null,
    standId: null,
    standNom: null,
    autres: [],
    raison: null,
    ...partial,
  };
}

function profile(partial: Partial<AnimateurProfile> = {}): AnimateurProfile {
  return {
    animateur: animateur(),
    joursEvenement: ['2026-07-11', '2026-07-12', '2026-07-13'],
    regimeDebut: { date: '2026-07-11', age: 17, regime: 'MINEUR' },
    regimeFin: { date: '2026-07-13', age: 18, regime: 'MAJEUR' },
    planCalcule: true,
    equite: {
      heureDebutSoiree: '20:00:00',
      semaines: [],
      lignes: [],
      syntheses: {},
      colonnesSolveur: [],
    },
    fragilite: null,
    competencesRares: [],
    affectations: [],
    confirmation: null,
    dernierePublicationLe: null,
    echangesEnCours: [],
    ajustements: [],
    verrous: [],
    declarationEnAttente: null,
    ...partial,
  };
}

describe('regime', () => {
  it('words each of the three regimes with the age', () => {
    expect(regimeLabel({ date: '2026-07-11', age: 15, regime: 'MOINS_DE_16' })).toContain('16');
    expect(regimeLabel({ date: '2026-07-11', age: 17, regime: 'MINEUR' })).toContain('17');
    expect(regimeLabel({ date: '2026-07-11', age: 30, regime: 'MAJEUR' })).toContain('Majeur');
  });

  it('says the regime changes when a birthday falls during the event', () => {
    expect(regimeChanges(profile())).toBe(true);
    expect(
      regimeChanges(profile({ regimeFin: { date: '2026-07-13', age: 17, regime: 'MINEUR' } })),
    ).toBe(false);
    expect(regimeChanges(profile({ regimeDebut: null, regimeFin: null }))).toBe(false);
  });
});

describe('availabilityStrip', () => {
  it('marks the declared days off and the days a forced unavailability rules out', () => {
    const strip = availabilityStrip(
      profile({
        animateur: animateur({ joursIndisponibles: ['2026-07-12'] }),
        ajustements: [
          adjustment({ date: '2026-07-13', creneauId: 4 }),
          adjustment({ id: 'aff', type: 'AFFINITE', date: '2026-07-11' }),
        ],
      }),
    );
    expect(strip).toEqual([
      { date: '2026-07-11', declaredOff: false, forcedOff: false },
      { date: '2026-07-12', declaredOff: true, forcedOff: false },
      { date: '2026-07-13', declaredOff: false, forcedOff: true },
    ]);
  });

  it('keeps apart the days off declared outside the event', () => {
    const fiche = profile({
      animateur: animateur({ joursIndisponibles: ['2026-08-01', '2026-07-12', '2026-06-30'] }),
    });
    expect(daysOffOutsideEvent(fiche)).toEqual(['2026-06-30', '2026-08-01']);
  });
});

describe('competenceRows', () => {
  it('puts appreciations and wishes side by side, flagging a wish nobody appreciated', () => {
    const rows = competenceRows(
      animateur({ competences: { JEU: 'AUTONOME' }, souhaits: ['JEU', 'CUBE'] }),
      [
        { id: 'JEU', label: 'Jeux de société' },
        { id: 'CUBE', label: 'Casse-tête' },
      ],
    );
    expect(rows).toEqual([
      {
        typologieId: 'CUBE',
        label: 'Casse-tête',
        niveau: null,
        wished: true,
        wishedNotAppreciated: true,
      },
      {
        typologieId: 'JEU',
        label: 'Jeux de société',
        niveau: 'AUTONOME',
        wished: true,
        wishedNotAppreciated: false,
      },
    ]);
  });
});

describe('adjustmentScope', () => {
  it('names the stand and the timeslot, or the whole event', () => {
    expect(
      adjustmentScope(
        adjustment({
          standNom: 'Échecs',
          date: '2026-07-11',
          heureDebut: '10:00:00',
          heureFin: '12:00:00',
        }),
      ),
    ).toBe('Échecs · 2026-07-11 10:00→12:00');
    expect(adjustmentScope(adjustment())).toBe("Tout l'événement");
  });
});

describe('upcomingCount', () => {
  it('counts only the seats not yet started', () => {
    const seat = {
      posteId: 'p',
      standId: 's',
      standNom: 'S',
      creneauId: 1,
      date: '2026-07-11',
      heureDebut: '10:00:00',
      heureFin: '12:00:00',
      emplacementId: null,
      emplacementNom: null,
      verrouille: false,
    };
    expect(
      upcomingCount(
        profile({
          affectations: [
            { ...seat, posteId: 'p1', passe: true },
            { ...seat, posteId: 'p2', passe: false },
          ],
        }),
      ),
    ).toBe(1);
  });
});
