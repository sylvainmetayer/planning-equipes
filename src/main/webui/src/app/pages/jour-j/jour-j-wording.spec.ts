import { describe, expect, it } from 'vitest';
import type {
  AnimateurAffecte,
  CreneauJourJ,
  EtatJourJ,
  PosteAPourvoir,
  SuggestionsReparation,
} from '../../core/models';
import {
  alerteLibelle,
  aucuneSuggestion,
  blocageDuPoste,
  chargeRestante,
  searchPeople,
  dejaDeService,
  dayHeader,
  mergeSuggestions,
  heure,
  heureDe,
  libelleCreneau,
  nextBatch,
  nomDuCandidat,
  nonPublieLibelle,
  nobodyForTheSeat,
  plage,
  SEARCH_FURTHER_CAP,
  suggestionsScope,
  prevenirLibelle,
  hasUnseenCandidates,
  resumeDuJour,
  RESULTATS_MAX,
} from './jour-j-wording';

function etat(overrides: Partial<EtatJourJ> = {}): EtatJourJ {
  return {
    date: '2026-07-08',
    maintenant: '2026-07-08T13:30:00',
    creneauxDuJour: 4,
    creneauxRestants: [],
    animateursDeService: [],
    postesAPourvoir: [],
    absences: [],
    animateurs: [],
    consigne: null,
    signalements: [],
    jourNumero: 5,
    standsOuverts: 60,
    alertes: [],
    aPrevenir: [],
    echangesAArbitrer: 0,
    ...overrides,
  };
}

function creneau(overrides: Partial<CreneauJourJ> = {}): CreneauJourJ {
  return {
    id: 2,
    date: '2026-07-08',
    heureDebut: '14:00:00',
    heureFin: '18:00:00',
    enCours: false,
    ...overrides,
  };
}

function animateur(overrides: Partial<AnimateurAffecte> = {}): AnimateurAffecte {
  return {
    animateurId: 'A1',
    nomAffiche: 'Alice Referente',
    postesRestants: 2,
    absent: false,
    ...overrides,
  };
}

function poste(overrides: Partial<PosteAPourvoir> = {}): PosteAPourvoir {
  return {
    posteId: 'P2',
    standId: 'STAND-STRAT',
    standNom: 'Stand stratégie',
    creneauId: 2,
    heureDebut: '14:00:00',
    heureFin: '18:00:00',
    verrouille: false,
    nouveau: false,
    resteDuCreneau: false,
    ...overrides,
  };
}

function suggestions(overrides: Partial<SuggestionsReparation> = {}): SuggestionsReparation {
  return {
    posteId: 'P2',
    animateurActuelId: null,
    scoreAvant: { hardScore: 0, mediumScore: 0, softScore: 0 },
    contraintesVioleesAvant: [],
    candidatsEligibles: 20,
    depuis: 0,
    candidatsEvalues: 20,
    plafond: 20,
    suggestions: [],
    ...overrides,
  };
}

describe('heure / plage', () => {
  it('trims the seconds the server sends', () => {
    expect(heure('09:05:00')).toBe('09:05');
    expect(heure(null)).toBe('');
    expect(plage('14:00:00', '18:00:00')).toBe('14:00 – 18:00');
  });

  /** The reference moment is an instant, since a journée can run past midnight. */
  it('reads the time of day off a full instant', () => {
    expect(heureDe('2026-07-09T01:00:00')).toBe('01:00');
    expect(heureDe(null)).toBe('');
  });
});

describe('resumeDuJour', () => {
  it('states the remaining count against the whole day', () => {
    const text = resumeDuJour(etat({ creneauxRestants: [creneau(), creneau({ id: 3 })] }));
    expect(text).toContain('2');
    expect(text).toContain('4');
    expect(text).toContain('13:30');
  });

  it('says the day is over rather than showing a zero', () => {
    expect(resumeDuJour(etat({ creneauxDuJour: 4 }))).toContain('terminée');
  });

  /**
   * A date carrying no timeslot has no first one, so no journée at all. Calling
   * that "over" would be a lie the operator acts on.
   */
  it('tells an empty journée from a finished one', () => {
    const text = resumeDuJour(etat({ creneauxDuJour: 0 }));
    expect(text).toContain('Aucun créneau');
    expect(text).not.toContain('terminée');
  });

  it('says nothing before the state has loaded', () => {
    expect(resumeDuJour(null)).toBe('');
  });
});

describe('dayHeader', () => {
  it('states the rank, the open stands and the shifts left', () => {
    expect(dayHeader(etat({ creneauxRestants: [creneau(), creneau({ id: 3 })] }))).toBe(
      'J5 · 60 stands ouverts · 2 vacations restantes',
    );
  });

  it('drops the rank of a day before the event', () => {
    expect(dayHeader(etat({ jourNumero: 0, standsOuverts: 1 }))).toBe(
      '1 stand ouvert · 0 vacations restantes',
    );
  });
});

describe('prevenirLibelle / nonPublieLibelle', () => {
  it('counts the people the targeted publication would write to', () => {
    expect(prevenirLibelle(0)).toBe('');
    expect(prevenirLibelle(2)).toBe('Prévenir les 2 personnes');
    expect(nonPublieLibelle(0)).toContain('dernière version');
    expect(nonPublieLibelle(3)).toContain('3 personnes');
  });
});

describe('searchPeople', () => {
  const roster = etat({
    animateursDeService: [animateur({ animateurId: 'A87', nomAffiche: 'Hélène Martin' })],
    animateurs: Array.from({ length: 153 }, (_, rang) => ({
      animateurId: `A${rang + 1}`,
      nomAffiche: rang === 86 ? 'Hélène Martin' : `Personne ${rang + 1}`,
      telephone: rang === 86 ? '06 12 34 56 78' : null,
    })),
  });

  /** Finding A87 among 153 on a phone: a search, never a list to scroll. */
  it('finds one person among 153, accents and case aside', () => {
    const trouves = searchPeople(roster, 'helene');
    expect(trouves.map((personne) => personne.animateurId)).toEqual(['A87']);
    expect(trouves[0].telephone).toBe('06 12 34 56 78');
    expect(trouves[0].postesRestants).toBe(2);
  });

  it('reads the id too, and every word typed', () => {
    expect(searchPeople(roster, 'a87').map((personne) => personne.animateurId)).toEqual(['A87']);
    expect(searchPeople(roster, 'martin hel')).toHaveLength(1);
  });

  /** The number of a missed call finds who it was, spaces typed or not. */
  it('reads the phone number too', () => {
    expect(searchPeople(roster, '0612345678').map((personne) => personne.animateurId)).toEqual([
      'A87',
    ]);
    expect(searchPeople(roster, '06 12 34').map((personne) => personne.animateurId)).toEqual([
      'A87',
    ]);
  });

  it('lists nothing below two characters, and never more than a screen', () => {
    expect(searchPeople(roster, 'p')).toEqual([]);
    expect(searchPeople(roster, 'personne')).toHaveLength(RESULTATS_MAX);
  });
});

describe('alerteLibelle', () => {
  it('words the three alerts of the wall display', () => {
    const base = {
      standNom: 'Cirque',
      start: '2026-07-08T14:00:00',
      end: '2026-07-08T18:00:00',
      count: 2,
      nom: null,
    };
    expect(alerteLibelle({ ...base, type: 'NEW_EMPTY_SEATS' })).toContain('depuis ce matin');
    expect(alerteLibelle({ ...base, type: 'STARTING_SOON' })).toContain('14:00');
    expect(alerteLibelle({ ...base, type: 'BREAK_WITHOUT_RELAY', nom: 'Léa M.' })).toContain(
      'Léa M.',
    );
  });
});

describe('suggestionsScope', () => {
  /**
   * The decision behind this screen's plafond: a truncated list shown bare
   * reads as "there is nobody else", which is the one thing it must not say.
   */
  it('names both counts when the search stopped at the plafond', () => {
    const text = suggestionsScope(suggestions({ candidatsEligibles: 137, candidatsEvalues: 20 }));
    expect(text).toContain('20');
    expect(text).toContain('137');
    expect(text).toContain('pas tout le vivier');
  });

  it('says so plainly when the whole pool was evaluated', () => {
    const text = suggestionsScope(suggestions({ candidatsEligibles: 12, candidatsEvalues: 12 }));
    expect(text).toContain('12');
    expect(text).not.toContain('pas tout le vivier');
  });

  it('says nothing before a search has run', () => {
    expect(suggestionsScope(null)).toBe('');
  });
});

describe('« Chercher plus loin »', () => {
  const viable = (animateurId: string, softScore: number) => ({
    animateurId,
    scoreApres: { hardScore: 0, mediumScore: 0, softScore },
    delta: { hardScore: 1, mediumScore: 0, softScore },
    violationsResolues: [],
    violationsIntroduites: [],
  });

  it('counts every candidate simulated so far, from the first one', () => {
    const text = suggestionsScope(
      suggestions({ candidatsEligibles: 153, depuis: 20, candidatsEvalues: 100 }),
    );
    expect(text).toContain('120');
    expect(text).toContain('153');
  });

  it('asks for the next batch from where the searches stopped, as many as the server allows', () => {
    const premier = suggestions({ candidatsEligibles: 153, candidatsEvalues: 20 });

    expect(hasUnseenCandidates(premier)).toBe(true);
    expect(nextBatch(premier)).toEqual({ depuis: 20, plafond: SEARCH_FURTHER_CAP });
    expect(
      hasUnseenCandidates(
        suggestions({ candidatsEligibles: 153, depuis: 120, candidatsEvalues: 33 }),
      ),
    ).toBe(false);
    expect(hasUnseenCandidates(null)).toBe(false);
  });

  it('merges a further batch as one search, best first and nobody twice', () => {
    const deja = suggestions({
      candidatsEligibles: 153,
      candidatsEvalues: 20,
      suggestions: [viable('A2', -5)],
    });
    const suite = suggestions({
      candidatsEligibles: 153,
      depuis: 20,
      candidatsEvalues: 100,
      plafond: 100,
      suggestions: [viable('A2', -5), viable('A9', 0)],
    });

    const fusion = mergeSuggestions(deja, suite);

    expect(fusion.depuis).toBe(0);
    expect(fusion.candidatsEvalues).toBe(120);
    expect(fusion.suggestions.map((suggestion) => suggestion.animateurId)).toEqual(['A9', 'A2']);
  });

  it('says nobody among the whole pool can take the seat, once every candidate was tried', () => {
    expect(nobodyForTheSeat(suggestions({ candidatsEligibles: 153, candidatsEvalues: 20 }))).toBe(
      '',
    );
    expect(
      nobodyForTheSeat(
        suggestions({ candidatsEligibles: 3, candidatsEvalues: 3, suggestions: [viable('A2', 0)] }),
      ),
    ).toBe('');
    const verdict = nobodyForTheSeat(suggestions({ candidatsEligibles: 30, candidatsEvalues: 30 }));
    expect(verdict).toContain('30');
    expect(verdict).toContain('règle dure');
  });
});

describe('aucuneSuggestion', () => {
  it('tells an empty answer from no answer at all', () => {
    expect(aucuneSuggestion(null)).toBe(false);
    expect(aucuneSuggestion(suggestions())).toBe(true);
  });
});

describe('blocageDuPoste', () => {
  it('explains a lock instead of offering a button that would be refused', () => {
    expect(blocageDuPoste(poste())).toBe('');
    expect(blocageDuPoste(poste({ verrouille: true }))).toContain('verrou');
  });
});

describe('libelleCreneau', () => {
  it('marks the timeslot under way', () => {
    expect(libelleCreneau(creneau())).toBe('14:00 – 18:00');
    expect(libelleCreneau(creneau({ enCours: true }))).toContain('en cours');
  });
});

describe('chargeRestante', () => {
  it('says how much of the day the person still holds', () => {
    expect(chargeRestante(animateur({ postesRestants: 1 }))).toContain('1 créneau restant');
    expect(chargeRestante(animateur({ postesRestants: 3 }))).toContain('3');
    expect(chargeRestante({ postesRestants: 0 })).toContain('Pas de service');
  });
});

describe('nomDuCandidat', () => {
  const charge = etat({
    animateursDeService: [animateur()],
    animateurs: [
      { animateurId: 'A1', nomAffiche: 'Alice Referente', telephone: null },
      { animateurId: 'A2', nomAffiche: 'Bruno Autonome', telephone: null },
    ],
  });

  it('names people from the roster', () => {
    expect(nomDuCandidat(charge, 'A1')).toBe('Alice Referente');
  });

  /**
   * The regression that made this a roster lookup: the best replacement is
   * somebody *free* at that hour, so they are precisely the one missing from
   * the on-duty list — and the main action button read « A2 ».
   */
  it('names a candidate who is not on duty at all', () => {
    expect(nomDuCandidat(charge, 'A2')).toBe('Bruno Autonome');
  });

  it('falls back to the id rather than hiding a real candidate', () => {
    expect(nomDuCandidat(charge, 'A9')).toBe('A9');
    expect(nomDuCandidat(null, 'A9')).toBe('A9');
  });
});

describe('dejaDeService', () => {
  it('flags a candidate already working the remaining timeslots', () => {
    const charge = etat({ animateursDeService: [animateur()] });
    expect(dejaDeService(charge, 'A1')).toBe(true);
    expect(dejaDeService(charge, 'A2')).toBe(false);
    expect(dejaDeService(null, 'A1')).toBe(false);
  });
});
